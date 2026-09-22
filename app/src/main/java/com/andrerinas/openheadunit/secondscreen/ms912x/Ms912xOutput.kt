package com.andrerinas.openheadunit.secondscreen.ms912x

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.decoder.video.AuxDisplayProfilePolicy
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import com.andrerinas.openheadunit.secondscreen.SurfaceOutput
import com.andrerinas.openheadunit.secondscreen.UsbDisplayAdapterPolicy
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Shows the second stream on a MacroSilicon USB-HDMI adapter.
 *
 * The aux decoder renders into an [ImageReader]; each picture is converted to the adapter's format
 * and handed to a sender thread through a single slot, so a slow USB link skips frames rather than
 * queueing them.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class Ms912xOutput(
    private val context: Context,
    private val decoder: VideoDecoder,
    private val mode: Ms912xMode,
    private val format: Ms912xWireFormat,
) : SurfaceOutput {

    override val output = SecondScreenOutputPolicy.Output.MS912X
    override var onKeyframeNeeded: (() -> Unit)? = null

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var worker: HandlerThread? = null
    private var reader: ImageReader? = null
    private var sender: Thread? = null
    @Volatile private var device: Ms912xDevice? = null
    @Volatile private var stopped = false

    private val converter = Ms912xFrameConverter()
    private val frameBytes = Ms912xModes.frameBytes(mode, format)
    private val buffers = arrayOf(ByteArray(frameBytes), ByteArray(frameBytes))
    private val slotLock = Object()
    private var pending: ByteArray? = null
    private var sending: ByteArray? = null
    private var planeCopies = arrayOfNulls<ByteArray>(3)
    private var framesSent = 0
    private var framesSkipped = 0
    private var acquireFailureLogged = false

    override fun surface(): Surface? = reader?.surface

    override fun start() {
        stopped = false
        val thread = HandlerThread("SecondScreen:MS912x").also { it.start() }
        worker = thread
        val handler = Handler(thread.looper)
        // Sized to the stream the phone was asked for, which is at least the mode; three images let
        // one be converted while the decoder fills the next.
        val profile = AuxDisplayProfilePolicy.profileFor(mode.width, mode.height, Ms912xModes.densityFor(mode))
        val (streamWidth, streamHeight) = AuxDisplayProfilePolicy.dimensions(profile.resolution) ?: (mode.width to mode.height)
        val imageReader = ImageReader.newInstance(streamWidth, streamHeight, ImageFormat.YUV_420_888, 3)
        imageReader.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = imageReader
        decoder.setSurface(imageReader.surface)
        handler.post { connectAdapter() }
        sender = Thread({ sendLoop() }, "SecondScreen:MS912xSend").apply { isDaemon = true; start() }
    }

    private fun findAdapter(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        UsbDisplayAdapterPolicy.kindOf(it.vendorId, it.productId, emptyList()) == UsbDisplayAdapterPolicy.Kind.MS912X
    }

    /** Waits for the USB permission (asking once), then powers the adapter up. Runs on the worker. */
    private fun connectAdapter() {
        val usb = findAdapter()
        if (usb == null) {
            AppLog.w("SecondScreen: no MS912x adapter is attached")
            return
        }
        if (!usbManager.hasPermission(usb)) {
            AppLog.i("SecondScreen: asking for permission to use the MS912x adapter")
            usbManager.requestPermission(usb, UsbReceiver.createPermissionPendingIntent(context))
            val deadline = SystemClock.elapsedRealtime() + PERMISSION_WAIT_MS
            while (!stopped && !usbManager.hasPermission(usb) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(500)
            }
            if (!usbManager.hasPermission(usb)) {
                AppLog.w("SecondScreen: no permission for the MS912x adapter, so it stays dark")
                return
            }
        }
        val dev = Ms912xDevice(usbManager, usb, mode, format)
        if (stopped || !dev.open() || !dev.initDisplay() || stopped) {
            dev.close()
            return
        }
        device = dev
        // The picture already decoded is lost to whatever came before, so start on a keyframe.
        onKeyframeNeeded?.invoke()
    }

    private fun onImage(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: Exception) {
            // Most likely a decoder whose buffers are not the size asked for; say so once.
            if (!acquireFailureLogged) AppLog.w("SecondScreen: MS912x could not take a decoded picture: ${e.message}")
            acquireFailureLogged = true
            null
        } ?: return
        try {
            if (device == null) return
            val target = synchronized(slotLock) {
                // One pending frame is the whole backlog: while it waits, newer pictures are skipped.
                if (pending != null) null else buffers.first { it !== sending }
            }
            if (target == null) {
                framesSkipped++
                return
            }
            convert(image, target)
            synchronized(slotLock) {
                pending = target
                slotLock.notifyAll()
            }
        } catch (e: Exception) {
            AppLog.w("SecondScreen: MS912x conversion failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun convert(image: Image, out: ByteArray) {
        val planes = image.planes
        val copied = Array(3) { i ->
            val buffer = planes[i].buffer.duplicate().apply { rewind() }
            val array = planeCopies[i]?.takeIf { it.size >= buffer.remaining() } ?: ByteArray(buffer.remaining())
            planeCopies[i] = array
            buffer.get(array, 0, buffer.remaining())
            Ms912xFrameConverter.Plane(array, planes[i].rowStride, planes[i].pixelStride)
        }
        // Android Auto draws at the top-left and leaves any announced margin blank, so the picture
        // is the mode's size wherever the buffer is bigger.
        val crop = image.cropRect
        val srcWidth = minOf(crop.width(), mode.width)
        val srcHeight = minOf(crop.height(), mode.height)
        converter.convert(
            copied[0], copied[1], copied[2], srcWidth, srcHeight, format,
            out, Ms912xModes.transferWidth(mode, format), mode.width, mode.height, stretch = false,
        )
    }

    private fun sendLoop() {
        while (!stopped) {
            val frame = synchronized(slotLock) {
                while (!stopped && pending == null) slotLock.wait()
                pending.also { sending = it; pending = null }
            } ?: continue
            val dev = device
            if (dev != null && !dev.sendFrame(frame)) {
                AppLog.w("SecondScreen: MS912x stopped taking frames after $framesSent")
                device = null
                dev.close()
            } else {
                framesSent++
                if (framesSent % 300 == 0) {
                    AppLog.i("SecondScreen: MS912x sent $framesSent frames, skipped $framesSkipped")
                }
            }
            synchronized(slotLock) { sending = null }
        }
    }

    override fun stop() {
        stopped = true
        synchronized(slotLock) { slotLock.notifyAll() }
        reader?.surface?.let { decoder.stopIfCurrentSurface(it, "the MS912x output stopped") }
        worker?.quitSafely()
        sender?.join(2000)
        device?.close()
        device = null
        reader?.close()
        reader = null
        worker = null
        sender = null
    }

    private companion object {
        const val PERMISSION_WAIT_MS = 60_000L
    }
}
