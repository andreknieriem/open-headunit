package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.google.protobuf.Message

/**
 * The defined reply to a MicrophoneRequest, which this head unit never sent.
 *
 * Nothing acknowledged the phone opening or closing the microphone: the request only started or
 * stopped the recorder. Android Auto knows the message - its own protocol enum names it - and a
 * phone waiting on it before opening its speech pipeline would look exactly like a microphone that
 * is heard but never understood.
 *
 * The microphone lifecycle allocates the session id for each Open and preserves it in every
 * response, including asynchronous close and error replies.
 */
class MicrophoneResponse(status: Int, sessionId: Int)
    : AapMessage(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE,
        makeProto(status, sessionId)) {

    companion object {
        private fun makeProto(status: Int, sessionId: Int): Message {
            return Media.MicrophoneResponse.newBuilder().apply {
                this.status = status
                this.sessionId = sessionId
            }.build()
        }
    }
}
