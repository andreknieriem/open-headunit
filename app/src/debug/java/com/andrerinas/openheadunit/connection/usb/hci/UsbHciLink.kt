package com.andrerinas.openheadunit.connection.usb.hci

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale

/** Commands on endpoint 0, events read back from the interrupt endpoint [evt]. */
class UsbHciLink(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val evt: UsbEndpoint,
) : HciLink {

    private val assembler = HciCodec.EventAssembler()
    private val queue = ArrayDeque<HciCodec.Event>()
    private val buf = ByteArray(64)

    override fun send(cmd: ByteArray): Int {
        val ret = conn.controlTransfer(0x20, 0x00, 0, intf.id, cmd, cmd.size, 1000)
        AppLog.d(String.format(Locale.US, "UsbHciLink: tx op=%02x%02x len=%d ret=%d", cmd[1], cmd[0], cmd.size, ret))
        return ret
    }

    override fun read(timeoutMs: Int): HciCodec.Event? {
        queue.removeFirstOrNull()?.let { return it }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val n = conn.bulkTransfer(evt, buf, buf.size, 200)
            if (n < 0) SystemClock.sleep(20)
            if (n > 0) {
                queue.addAll(assembler.feed(buf, n))
                queue.removeFirstOrNull()?.let {
                    AppLog.d(String.format(Locale.US, "UsbHciLink: rx event=%02x len=%d", it.code, it.params.size))
                    return it
                }
            }
        }
        return null
    }
}
