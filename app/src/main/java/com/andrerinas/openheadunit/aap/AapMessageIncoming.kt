package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.MsgType
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Utils

internal class AapMessageIncoming(header: EncryptedHeader, ba: ByteArrayWithLimit)
    : AapMessage(header.chan, header.flags.toByte(),
        if (AapMessageFraming.carriesMessageType(header.flags) && ba.limit >= 2) Utils.bytesToInt(ba.data, 0, true) else -1,
        calcOffset(header), ba.limit, ba.data) {

    /**
     * Cleartext AAP envelope for an encrypted body; the header itself is not encrypted.
     *
     * Offset 0: channel (u8); 1: flags (u8); 2..3: body byte count (u16, big-endian).
     * FIRST without LAST adds a big-endian four-byte total plaintext length after this header.
     * That extra field is outside enc_len and counts the reassembled type plus service payload,
     * not TLS overhead. COMPLETE and continuation frames have no such extra field.
     *
     * Readers consume the envelope, optional total and exactly enc_len body bytes before unwrap.
     * A socket/USB read boundary is unrelated to an AAP frame or a complete service message.
     */
    internal class EncryptedHeader {

        var chan: Int = 0
        var flags: Int = 0
        var enc_len: Int = 0
        var msg_type: Int = 0
        var buf = ByteArray(SIZE)

        fun decode() {
            this.chan = buf[0].toInt() and 0xff
            this.flags = buf[1].toInt() and 0xff

            // TLS body length only; excludes this header and the optional four-byte total.
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
            // Complete TLS control frames may have no AAP message bytes. Continuations still
            // carry their fragment boundary: an empty LAST must finish the preceding assembly.
            if (ba.limit == 0 && AapMessageFraming.carriesMessageType(header.flags)) return null

            // A fragmented message may split even its type or timestamp across fragments.
            // Use ba.limit, not ba.data.size: TLS reuses a larger backing array, whose unused
            // tail is not received data. The constructor leaves an incomplete type unknown.
            // A video access unit can end in a legal one-byte tail with no repeated type.
            // A blanket two-byte guard here would discard legal short continuations and lose
            // the whole message. The reassembler checks the completed header before dispatch;
            // deferring that check does not permit a handler to read stale buffer contents.
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
