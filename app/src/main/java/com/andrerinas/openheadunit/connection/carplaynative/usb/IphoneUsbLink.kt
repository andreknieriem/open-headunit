package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/** One claimed iPhone USB function: control transfers and a bulk pair. The Android adapter wraps `UsbDeviceConnection`. */
interface IphoneUsbPipe {
    fun control(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int

    fun write(buffer: ByteArray, length: Int, timeoutMs: Int): Int

    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** Releases the claim and the connection. It must be safe to call twice. */
    fun close()
}

/** The answer to the CarPlay mode request. */
sealed class IphoneModeAnswer {
    object Accepted : IphoneModeAnswer()
    class Refused(val line: String) : IphoneModeAnswer()
}

/**
 * The reader and the writer of the iPhone usbmux pipe. Reads use 16384-byte buffers, writes go out in chunks of that size.
 * One instance serves one session: [close] ends the reader thread, and a new session makes a new link.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/IphoneUsbHost.kt` and LIVI (https://github.com/f-io/LIVI) crate iap2-usbmux `usb_pipe.rs`.
 */
class IphoneUsbLink(
    private val pipe: IphoneUsbPipe,
    private val clock: () -> Long,
    private val onBytes: (ByteArray) -> Unit,
    private val onEnded: (String) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private var reader: Thread? = null

    @Synchronized
    fun start() {
        check(reader == null && !closed.get()) { "IphoneUsbLink is single use" }
        reader = Thread({ readLoop() }, "carplay-usb-reader").apply { isDaemon = true; start() }
    }

    /** Writes [data] in chunks of at most [CHUNK_BYTES]. A short write is a lost link. */
    fun write(data: ByteArray) = synchronized(writeLock) {
        var offset = 0
        while (offset < data.size) {
            if (closed.get()) throw IphoneUsbException.DeviceUnavailable("USB link is closed")
            val count = minOf(CHUNK_BYTES, data.size - offset)
            val chunk = if (offset == 0 && count == data.size) data else data.copyOfRange(offset, offset + count)
            val sent = pipe.write(chunk, count, WRITE_TIMEOUT_MS)
            if (sent != count) throw IphoneUsbException.DeviceUnavailable("USB write moved $sent of $count bytes")
            offset += count
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pipe.close()
        val thread = synchronized(this) { reader }
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(READ_TIMEOUT_MS + JOIN_MARGIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(CHUNK_BYTES)
        var fast = 0
        while (!closed.get()) {
            val started = clock()
            val transferred = try { pipe.read(buffer, READ_TIMEOUT_MS) } catch (e: RuntimeException) { -1 }
            if (closed.get()) return
            val step = UsbReadPolicy.decide(transferred, clock() - started, READ_TIMEOUT_MS, fast)
            fast = step.fastFailures
            when (step.verdict) {
                UsbReadPolicy.Verdict.Data -> try {
                    onBytes(buffer.copyOf(transferred))
                } catch (e: RuntimeException) {
                    end("CarPlayNative: USB reader stopped (${e.javaClass.simpleName})")
                    return
                }
                UsbReadPolicy.Verdict.Retry -> Unit
                UsbReadPolicy.Verdict.GiveUp -> {
                    end("CarPlayNative: USB read failed $fast times in a row")
                    return
                }
            }
        }
    }

    private fun end(reason: String) {
        if (closed.get()) return
        AppLog.w(reason)
        onEnded(reason)
    }

    companion object {
        const val CHUNK_BYTES = 16384
        const val READ_TIMEOUT_MS = 1000
        const val WRITE_TIMEOUT_MS = 2000
        private const val JOIN_MARGIN_MS = 500L
        private const val CONTROL_TIMEOUT_MS = 1000

        /** Asks for CarPlay mode with the vendor request. A refusal reads the mode with `0x45` and names its bytes. */
        fun requestMode(pipe: IphoneUsbPipe): IphoneModeAnswer {
            val answer = ByteArray(IphoneUsbPolicy.MODE_REQUEST_LENGTH)
            val moved = pipe.control(
                IphoneUsbPolicy.MODE_REQUEST_TYPE, IphoneUsbPolicy.MODE_REQUEST, 0, IphoneUsbPolicy.MODE_REQUEST_INDEX,
                answer, answer.size, CONTROL_TIMEOUT_MS,
            )
            if (!IphoneUsbPolicy.modeAnswerIsRefusal(moved)) return IphoneModeAnswer.Accepted
            val mode = ByteArray(IphoneUsbPolicy.MODE_READ_LENGTH)
            val read = pipe.control(
                IphoneUsbPolicy.MODE_REQUEST_TYPE, IphoneUsbPolicy.MODE_READ_REQUEST, 0, 0, mode, mode.size, CONTROL_TIMEOUT_MS,
            )
            return IphoneModeAnswer.Refused(IphoneUsbPolicy.refusalLine(if (read in 1..mode.size) mode.copyOf(read) else ByteArray(0)))
        }
    }
}
