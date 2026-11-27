package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/** The claimed NCM data interface: its bulk pair. The Android adapter wraps `UsbDeviceConnection`. */
interface NcmPipe {
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    fun write(buffer: ByteArray, length: Int, timeoutMs: Int): Int

    /** Releases the interfaces this pipe claimed, not the connection. It must be safe to call twice. */
    fun close()
}

/** Ethernet frames to and from the iPhone. */
interface EthernetLink {
    /** The next whole frame, or null when [timeoutMs] passes with none. Throws [IphoneUsbException] once the pipe is dead. */
    fun recv(timeoutMs: Long): ByteArray?

    /** False when the iPhone did not take the frame in time. */
    fun send(frame: ByteArray, timeoutMs: Int): Boolean

    fun close()
}

/**
 * Ethernet over NCM: NTB16 blocks on a bulk pair, reads of 16384 bytes at most.
 * Until the iPhone takes its first block, a write that cannot go out at once is dropped after 20 ms.
 * Follows DiPlay (https://github.com/shihabal3amri/DiPlay) `transport/NcmUsbBridge.kt` and LIVI (https://github.com/f-io/LIVI) crate iap2-usbmux `ncm/android.rs`.
 */
class NcmUsbLink(
    private val pipe: NcmPipe,
    packetSize: Int,
    private val clock: () -> Long,
) : EthernetLink {
    private val closed = AtomicBoolean(false)
    private val readLock = Any()
    private val writeLock = Any()
    private val reassembler = NtbReassembler(packetSize, clock) { AppLog.i(it) }
    private val readBuffer = ByteArray(READ_BYTES)
    private var sequence = 0
    private var fastFailures = 0
    private var firstWriteAccepted = false
    private var notReadyLogged = false

    override fun recv(timeoutMs: Long): ByteArray? = synchronized(readLock) {
        reassembler.takeFrame()?.let { return it }
        val deadline = clock() + timeoutMs
        while (!closed.get()) {
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            val slice = minOf(remaining, READ_SLICE_MS.toLong()).toInt()
            val started = clock()
            val count = try { pipe.read(readBuffer, slice) } catch (_: RuntimeException) { -1 }
            if (closed.get()) return null
            // An unplugged pipe fails at once on every read: the same rule as the usbmux reader ends the link.
            val step = UsbReadPolicy.decide(count, clock() - started, slice, fastFailures)
            fastFailures = step.fastFailures
            when (step.verdict) {
                UsbReadPolicy.Verdict.Data -> {
                    reassembler.append(readBuffer, count)
                    reassembler.takeFrame()?.let { return it }
                }
                UsbReadPolicy.Verdict.Retry -> Unit
                UsbReadPolicy.Verdict.GiveUp -> throw IphoneUsbException.DeviceUnavailable("NCM read failed $fastFailures times in a row")
            }
        }
        return null
    }

    override fun send(frame: ByteArray, timeoutMs: Int): Boolean = synchronized(writeLock) {
        if (closed.get()) throw IphoneUsbException.DeviceUnavailable("NCM link is closed")
        val block = Ntb16Codec.build(frame, sequence)
        sequence = (sequence + 1) and 0xffff
        val moved = pipe.write(block, block.size, if (firstWriteAccepted) timeoutMs else minOf(timeoutMs, NOT_READY_WRITE_MS))
        if (moved <= 0) {
            if (!notReadyLogged) { notReadyLogged = true; AppLog.i("CarPlayNative: NCM bulk out not ready; frames are dropped until it is") }
            return false
        }
        if (moved != block.size) throw IphoneUsbException.DeviceUnavailable("NCM write moved $moved of ${block.size} bytes")
        if (!firstWriteAccepted) { firstWriteAccepted = true; AppLog.i("CarPlayNative: NCM bulk out accepted its first block") }
        notReadyLogged = false
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(writeLock) { pipe.close() }
    }

    companion object {
        const val READ_BYTES = 16384
        const val NOT_READY_WRITE_MS = 20
        private const val READ_SLICE_MS = 250
    }
}
