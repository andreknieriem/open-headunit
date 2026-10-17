package com.andrerinas.openheadunit.connection.wifi.scan

import com.andrerinas.openheadunit.utils.adb.AdbProtocol
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Identify a surviving ADB listener without authenticating or submitting a shell command. */
internal object AdbHelloProbe {
    fun validHeader(header: ByteArray): Boolean {
        if (header.size != AdbProtocol.ADB_HEADER_LENGTH) return false
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = fields.int
        val argument = fields.int
        fields.int // max-data on CNXN, zero on AUTH
        val length = fields.int
        fields.int // Payload checksum; this probe does not consume or interpret a payload.
        val magic = fields.int
        if (magic != command.inv()) return false
        return when (command) {
            AdbProtocol.CMD_AUTH -> argument == AdbProtocol.AUTH_TYPE_TOKEN && length == 20
            AdbProtocol.CMD_CNXN -> argument in 0x01000000..0x01000001 && length in 0..65536
            else -> false
        }
    }

    /** null means unknown/busy: retain rollback but do not touch the daemon. */
    fun isAdb(port: Int): Boolean? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 200)
            socket.getOutputStream().write(AdbProtocol.generateConnect())
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            val header = ByteArray(AdbProtocol.ADB_HEADER_LENGTH)
            var offset = 0
            while (offset < header.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
                val count = socket.getInputStream().read(header, offset, header.size - offset)
                if (count < 0) return null
                offset += count
            }
            // A fixed-size header avoids allocating memory from an unrelated listener's length.
            validHeader(header)
        }
    } catch (_: ConnectException) { false }
      catch (_: java.io.IOException) { null }
}
