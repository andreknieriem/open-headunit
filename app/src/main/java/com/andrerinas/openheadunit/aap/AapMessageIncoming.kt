package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.MsgType
import com.andrerinas.openheadunit.utils.AppLog

internal class AapMessageIncoming(header: EncryptedHeader, ba: ByteArrayWithLimit)
    : AapMessage(header.chan, header.flags.toByte(), Utils.bytesToInt(ba.data, 0, true), calcOffset(header), ba.limit, ba.data) {

    internal class EncryptedHeader {

        var chan: Int = 0
        var flags: Int = 0
        var enc_len: Int = 0
        var msg_type: Int = 0
        var buf = ByteArray(SIZE)

        fun decode() {
            this.chan = buf[0].toInt()
            this.flags = buf[1].toInt()

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

            // Two bytes are the message type read below, and only a message that *begins* an AAP
            // message has one - see AapMessageFraming. A middle or last fragment is raw payload from
            // its first byte, so any length is legal there and this guard must not touch it. It used
            // to, on every channel and every flag, and on the video channel dropping a run's last
            // fragment costs the whole access unit: the reassembler sees the next frame's first
            // fragment arrive with the run still open, reports TRUNCATED_PREVIOUS, and the picture
            // smears until a keyframe.
            //
            // The payload length is ba.limit, not ba.data.size: the SSL layer hands back one reused
            // buffer whose length is the session ceiling, so data.size is always large enough and
            // reading it here would never fire. That reuse is also why relaxing the guard is safe -
            // the ArrayIndexOutOfBounds this originally existed to prevent needed the exactly sized
            // per-message array the SSL layer no longer allocates.
            //
            // A zero-length continuation now passes too, and is a no-op: AapVideo's append copies
            // zero bytes. It was the one reason to keep rejecting on length alone, because a run of
            // `too short: 0` is what an SSL desync looks like from here - but a desync ends the
            // session in seconds through AapRead's own disconnect paths, which log unconditionally,
            // so the diagnosis survives without this guard having to guess at it.
            if (AapMessageFraming.carriesMessageType(header.flags) && ba.limit < 2) {
                // Carries the framing the same way the WRONG FLAG line above does. What is left here
                // is a message that claims to begin one and is too short to say what it begins, so
                // the channel and flag are what separate a phone that sent a malformed message from
                // a reader that mis-framed one.
                AppLog.e("Decrypted payload too short: %d  chan: %d %s  flags: 0x%02x  enc_len: %d",
                        ba.limit, header.chan, Channel.name(header.chan), header.flags, header.enc_len)
                return null
            }

            val msg = AapMessageIncoming(header, ba)

            if (AppLog.LOG_VERBOSE) {
                AppLog.d("RECV: %s", msg.toString())
            }
            return msg
        }

        fun calcOffset(header: EncryptedHeader): Int {
            return 2
        }
    }
}
