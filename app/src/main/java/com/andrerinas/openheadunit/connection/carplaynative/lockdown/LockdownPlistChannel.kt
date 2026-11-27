package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistException
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.codec.XmlPlist
import com.andrerinas.openheadunit.connection.carplaynative.usb.BlockingDuplexByteStream
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbException

/** The lockdown frame: a four-byte big-endian length, then an XML plist. */
object LockdownFrame {
    const val LENGTH_BYTES = 4
    const val MAX_MESSAGE_BYTES = 1 shl 20

    fun encode(message: PlistValue.Dict): ByteArray {
        val xml = XmlPlist.encode(message)
        if (xml.size > MAX_MESSAGE_BYTES) throw IphoneUsbException.Protocol("lockdown message of ${xml.size} bytes is too large")
        val frame = ByteArray(LENGTH_BYTES + xml.size)
        for (i in 0 until LENGTH_BYTES) frame[i] = (xml.size ushr (24 - i * 8)).toByte()
        xml.copyInto(frame, LENGTH_BYTES)
        return frame
    }

    /** The body length a header declares, or null when it is zero or over the cap. */
    fun bodyLength(header: ByteArray): Int? {
        var length = 0L
        for (i in 0 until LENGTH_BYTES) length = (length shl 8) or (header[i].toLong() and 0xff)
        return length.takeIf { it in 1..MAX_MESSAGE_BYTES }?.toInt()
    }
}

/**
 * Lockdown requests and replies over one stream. Each receive has one deadline for the header and the body.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/LockdownPlistChannel.kt`.
 */
class LockdownPlistChannel(private val stream: BlockingDuplexByteStream, private val clock: () -> Long) {

    fun request(message: PlistValue.Dict, timeoutMs: Long): PlistValue.Dict {
        stream.send(LockdownFrame.encode(message))
        return receive(timeoutMs)
    }

    fun receive(timeoutMs: Long): PlistValue.Dict {
        val deadline = clock() + timeoutMs
        val header = readExactly(LockdownFrame.LENGTH_BYTES, deadline)
        val length = LockdownFrame.bodyLength(header) ?: throw IphoneUsbException.Protocol("lockdown reply length is out of range")
        return try {
            XmlPlist.parse(readExactly(length, deadline))
        } catch (e: PlistException) {
            throw IphoneUsbException.Protocol("lockdown reply is not a plist", e)
        }
    }

    private fun readExactly(count: Int, deadline: Long): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val remaining = deadline - clock()
            if (remaining <= 0) throw IphoneUsbException.TimedOut("lockdown reply timed out")
            val chunk = stream.recv(count - offset, remaining) ?: throw IphoneUsbException.TimedOut("lockdown reply timed out")
            if (chunk.isEmpty()) throw IphoneUsbException.Protocol("the iPhone closed the lockdown stream")
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }
}
