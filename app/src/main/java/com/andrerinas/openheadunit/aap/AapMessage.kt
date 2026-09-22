package com.andrerinas.openheadunit.aap


import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.MsgType
import com.andrerinas.openheadunit.utils.Utils
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Message

open class AapMessage(
        internal val channel: Int,
        internal val flags: Byte,
        internal val type: Int,
        internal val dataOffset: Int,
        internal val size: Int,
        val data: ByteArray) {

    @JvmOverloads constructor(channel: Int, type: Int, proto: Message, buf: ByteArray = ByteArray(size(proto)))
            : this(channel, flags(channel, type), type, HEADER_SIZE + MsgType.SIZE, size(proto), buf) {

        val msgType = this.type
        this.data[0] = channel.toByte()
        this.data[1] = flags
        Utils.intToBytes(proto.serializedSize + MsgType.SIZE, 2, this.data)
        this.data[4] = (msgType shr 8).toByte()
        this.data[5] = (msgType and 0xFF).toByte()

        toByteArray(proto, this.data, HEADER_SIZE + MsgType.SIZE, proto.serializedSize)
    }

    val isAudio: Boolean
        get() = Channel.isAudio(this.channel)

    val isVideo: Boolean
        get() = Channel.isVideo(this.channel)

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(Channel.name(channel))
        sb.append(' ')
        sb.append(MsgType.name(type, channel))
        sb.append(" type: ")
        sb.append(type)
        sb.append(" flags: ")
        sb.append(flags)
        sb.append(" size: ")
        sb.append(size)
        sb.append(" dataOffset: ")
        sb.append(dataOffset)

//        sb.append('\n')
//        AapDump.logHex("", 0, data, this.size, sb)

        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        val msg = other as AapMessage

        if (msg.channel != this.channel) {
            return false
        }
        if (msg.flags != this.flags) {
            return false
        }
        if (msg.type != this.type) {
            return false
        }
        if (msg.size != this.size || msg.data.size < this.size) {
            return false
        }
        if (msg.dataOffset != this.dataOffset) {
            return false
        }
        for (i in 0 until this.size) {
            if (msg.data[i] != this.data[i]) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int{
        var result = channel
        result = 31 * result + flags
        result = 31 * result + type
        result = 31 * result + dataOffset
        result = 31 * result + size
 //       result = 31 * result + Arrays.hashCode(data)
        return result
    }


    internal fun <T : Message.Builder> parse(builder: T): T {
        builder.mergeFrom(this.data, this.dataOffset, this.size - this.dataOffset)
        return builder
    }

    private fun toByteArray(msg: Message, data: ByteArray, offset: Int, length: Int) {
        val output = CodedOutputStream.newInstance(data, offset, length)
        msg.writeTo(output)
        output.checkNoSpaceLeft()
    }

    companion object {
        const val HEADER_SIZE = 4

        private fun size(proto: Message): Int {
            return proto.serializedSize + MsgType.SIZE + HEADER_SIZE
        }

        private fun flags(channel: Int, type: Int): Byte {
            var flags: Byte = 0x0b
            if (channel != Channel.ID_CTR && MsgType.isControl(type)) {
                // Set Control Flag (On non-control channels, indicates generic/"control type" messages
                flags = 0x0f
            }
            return flags
        }
    }
}
