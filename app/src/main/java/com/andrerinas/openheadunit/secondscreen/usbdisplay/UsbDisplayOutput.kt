package com.andrerinas.openheadunit.secondscreen.usbdisplay

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.secondscreen.EncodedOutput
import com.andrerinas.openheadunit.secondscreen.H264Units
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import com.andrerinas.openheadunit.secondscreen.StreamBacklog
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Forwards the second stream, undecoded, to a device speaking the Open Headunit USB display protocol.
 *
 * One sender thread drains a bounded [StreamBacklog]; a second thread listens on the device's IN
 * endpoint for keyframe requests.
 */
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class UsbDisplayOutput(private val context: Context) : EncodedOutput {

    override val output = SecondScreenOutputPolicy.Output.USB_DISPLAY
    override var onKeyframeNeeded: (() -> Unit)? = null

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Object()
    private val backlog = StreamBacklog(MAX_QUEUED_UNITS, MAX_QUEUED_BYTES)
    @Volatile private var stopped = false
    @Volatile private var ready = false
    @Volatile private var parameterSets: ByteArray? = null
    private var connection: UsbDeviceConnection? = null
    private var found: UsbDisplayProbe.Found? = null
    private var sender: Thread? = null
    private var listener: Thread? = null
    private val startedAt = SystemClock.elapsedRealtime()

    override fun start() {
        stopped = false
        sender = Thread({ run() }, "SecondScreen:UsbDisplay").apply { isDaemon = true; start() }
    }

    override fun onAccessUnit(buf: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val config = H264Units.parameterSets(buf, offset, length)
        if (config != null) parameterSets = config
        if (!ready) return
        val chunk = StreamBacklog.Chunk(
            buf.copyOfRange(offset, offset + length), H264Units.isKeyframe(buf, offset, length), config != null,
        )
        val result = synchronized(lock) { backlog.offer(chunk).also { lock.notifyAll() } }
        if (result == StreamBacklog.Offer.OVERFLOWED) {
            AppLog.w("SecondScreen: the USB display fell behind; resuming at the next keyframe")
            onKeyframeNeeded?.invoke()
        }
    }

    private fun run() {
        val (conn, outEp, inEp) = connect() ?: return
        parameterSets?.let { synchronized(lock) { backlog.offer(StreamBacklog.Chunk(it, keyframe = false, config = true)) } }
        ready = true
        if (inEp != null) {
            listener = Thread({ listen(conn, inEp) }, "SecondScreen:UsbDisplayIn").apply { isDaemon = true; start() }
        }
        onKeyframeNeeded?.invoke()
        var sent = 0
        while (!stopped) {
            val chunk = synchronized(lock) {
                while (!stopped && backlog.size == 0) lock.wait()
                backlog.poll()
            } ?: continue
            if (!send(conn, outEp, chunk)) {
                AppLog.w("SecondScreen: the USB display stopped taking frames after $sent")
                break
            }
            sent++
        }
        ready = false
    }

    private fun connect(): Triple<UsbDeviceConnection, UsbEndpoint, UsbEndpoint?>? {
        val display = UsbDisplayProbe.find(usbManager)
        if (display == null) {
            AppLog.w("SecondScreen: no Open Headunit USB display is attached")
            return null
        }
        found = display
        if (!usbManager.hasPermission(display.device)) {
            AppLog.i("SecondScreen: asking for permission to use the USB display")
            usbManager.requestPermission(display.device, UsbReceiver.createPermissionPendingIntent(context))
            val deadline = SystemClock.elapsedRealtime() + PERMISSION_WAIT_MS
            while (!stopped && !usbManager.hasPermission(display.device) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(500)
            }
            if (!usbManager.hasPermission(display.device)) {
                AppLog.w("SecondScreen: no permission for the USB display, so it stays dark")
                return null
            }
        }
        val conn = usbManager.openDevice(display.device) ?: return null
        if (!conn.claimInterface(display.iface, true)) {
            conn.close()
            return null
        }
        connection = conn
        var outEp: UsbEndpoint? = null
        var inEp: UsbEndpoint? = null
        for (i in 0 until display.iface.endpointCount) {
            val ep = display.iface.getEndpoint(i)
            val usable = ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK || ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
            if (!usable) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) outEp = outEp ?: ep
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = inEp ?: ep
        }
        val info = UsbDisplayProbe.readInfo(conn, display.iface)
        if (outEp == null || info == null || stopped) {
            AppLog.w("SecondScreen: the USB display has no bulk OUT endpoint or no usable info")
            return null
        }
        conn.controlTransfer(UsbDisplayProtocol.REQUEST_TYPE_OUT, UsbDisplayProtocol.REQ_START, 0, display.iface.id, null, 0, 1000)
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            "${display.device.manufacturerName ?: "?"} ${display.device.productName ?: "?"}"
        } else display.device.deviceName
        AppLog.i("SecondScreen: USB display ${info.widthPx}x${info.heightPx} attached ($name, " +
            "keyframe requests ${if (inEp != null && info.sendsKeyframeRequests) "on" else "off"})")
        return Triple(conn, outEp, inEp.takeIf { info.sendsKeyframeRequests })
    }

    /** Header and unit, in pieces no larger than every Android release accepts in one transfer. */
    private fun send(conn: UsbDeviceConnection, ep: UsbEndpoint, chunk: StreamBacklog.Chunk): Boolean {
        val header = UsbDisplayProtocol.frameHeader(
            chunk.bytes.size, SystemClock.elapsedRealtime() - startedAt, chunk.keyframe, chunk.config,
        )
        if (conn.bulkTransfer(ep, header, header.size, TIMEOUT_MS) != header.size) return false
        var off = 0
        while (off < chunk.bytes.size) {
            val len = minOf(MAX_TRANSFER, chunk.bytes.size - off)
            val sent = conn.bulkTransfer(ep, chunk.bytes, off, len, TIMEOUT_MS)
            if (sent <= 0) return false
            off += sent
        }
        // A payload that fills its last packet exactly never ends the device's read on its own.
        if (chunk.bytes.size % ep.maxPacketSize.coerceAtLeast(1) == 0) conn.bulkTransfer(ep, ByteArray(0), 0, TIMEOUT_MS)
        return true
    }

    private fun listen(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(8))
        while (!stopped) {
            val read = conn.bulkTransfer(ep, buf, buf.size, 500)
            if (read > 0 && buf[0].toInt() == UsbDisplayProtocol.DEVICE_REQUEST_KEYFRAME) {
                AppLog.i("SecondScreen: the USB display asked for a keyframe")
                onKeyframeNeeded?.invoke()
            }
        }
    }

    override fun stop() {
        stopped = true
        ready = false
        synchronized(lock) { lock.notifyAll() }
        sender?.join(2000)
        listener?.join(1000)
        val conn = connection
        val display = found
        if (conn != null && display != null) {
            try {
                conn.controlTransfer(UsbDisplayProtocol.REQUEST_TYPE_OUT, UsbDisplayProtocol.REQ_STOP, 0, display.iface.id, null, 0, 500)
                conn.releaseInterface(display.iface)
            } catch (_: Exception) {}
            conn.close()
        }
        connection = null
    }

    private companion object {
        const val MAX_QUEUED_UNITS = 90
        const val MAX_QUEUED_BYTES = 4 * 1024 * 1024

        /** Below API 28 a bulk transfer is capped at 16 KiB. */
        const val MAX_TRANSFER = 16384
        const val TIMEOUT_MS = 1000
        const val PERMISSION_WAIT_MS = 60_000L
    }
}
