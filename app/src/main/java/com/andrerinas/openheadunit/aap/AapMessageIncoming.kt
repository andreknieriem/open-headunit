package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.MsgType
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Utils

internal class AapMessageIncoming(header: EncryptedHeader, ba: ByteArrayWithLimit)
    : AapMessage(header.chan, header.flags.toByte(),
        if (AapMessageFraming.carriesMessageType(header.flags) && ba.limit >= 2) Utils.bytesToInt(ba.data, 0, true) else -1,
        calcOffset(header), ba.limit, ba.data) {

    internal class EncryptedHeader {

        var chan: Int = 0
        var flags: Int = 0
        var enc_len: Int = 0
        var msg_type: Int = 0
        var buf = ByteArray(SIZE)

        fun decode() {
            this.chan = buf[0].toInt() and 0xff
            this.flags = buf[1].toInt() and 0xff

            // Encoded length of bytes to be decrypted (minus 4/8 byte headers)
            this.enc_len = Utils.bytesToInt(buf, 2, true)
        }

        companion object {
            const val SIZE = 4
        }

    }

    companion object {

        fun decrypt(header: EncryptedHeader, offset: Int, buf: ByteArray, ssl: AapSsl): AapMessage? {
            if (header.flags and 0x08 != 0x08) {
                AppLog.e("WRONG FLAG: enc_len: %d  chan: %d %s flags: 0x%02x  msg_type: 0x%02x %s",
                        header.enc_len, header.chan, Channel.name(header.chan), header.flags, header.msg_type, MsgType.name(header.msg_type, header.chan))
                return null
            }

            val ba = ssl.decrypt(offset, header.enc_len, buf) ?: return null

            // A fragmented message may split even its type or timestamp across fragments.
            // The reassembler validates the completed header before a service can consume it.
            val msg = AapMessageIncoming(header, ba)

            if (AppLog.LOG_VERBOSE) {
                AppLog.d("RECV: %s", msg.toString())
            }
            return msg
        }

        fun calcOffset(header: EncryptedHeader): Int {
            return if (AapMessageFraming.carriesMessageType(header.flags)) 2 else 0
        }
    }
}
