package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Input
import com.google.protobuf.Message

class KeyCodeEvent(timeStamp: Long, val keycode: Int, isPress: Boolean)
    : AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, makeProto(timeStamp, keycode, isPress)) {

    companion object {
        private fun makeProto(timeStamp: Long, keycode: Int, isPress: Boolean): Message {
            return Input.InputReport.newBuilder().also {
                it.timestamp = timeStamp * 1000000L
                it.keyEvent = Input.KeyEvent.newBuilder().apply {
                    addKeys(Input.Key.newBuilder().also { key ->
                        key.keycode = keycode
                        key.down = isPress
                        key.longpress = false
                        key.metastate = 0
                    })
                }.build()
            }.build()
        }
    }
}
