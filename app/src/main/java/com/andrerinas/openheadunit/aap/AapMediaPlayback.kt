package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.protoUint32ToLong

class AapMediaPlayback(
    private val onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)?,
    private val onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)?
) {
    /** The transport has already reassembled and validated this complete protobuf message. */
    fun process(message: AapMessage) {
        when (message.type) {
            MSG_MEDIA_PLAYBACK_METADATA -> {
                try {
                    onAaMediaMetadata?.invoke(message.parse(MediaPlayback.MediaMetaData.newBuilder()).build())
                } catch (e: Exception) {
                    AppLog.w("AapMediaPlayback: Failed to parse metadata: ${e.message}")
                }
            }
            MSG_MEDIA_PLAYBACK_STATUS -> processStatusPacket(message)
            MSG_MEDIA_PLAYBACK_INPUT -> Unit
            else -> AppLog.e("Unsupported %s", message.toString())
        }
    }

    private fun processStatusPacket(message: AapMessage) {
        try {
            val status = message.parse(MediaPlayback.MediaPlaybackStatus.newBuilder()).build()
            onAaPlaybackStatus?.invoke(status)
            AppLog.d(
                "AapMediaPlayback: status mediaSource='${status.mediaSource}', " +
                    "playbackSeconds(u32)=${status.playbackSeconds.protoUint32ToLong()}, state=${status.state}"
            )
        } catch (e: Exception) {
            AppLog.w("AapMediaPlayback: Failed to parse playback status: ${e.message}")
        }
    }

    private companion object {
        // Based on AA protocol enum MediaPlaybackStatusMessageId from protos.proto.
        const val MSG_MEDIA_PLAYBACK_STATUS = 32769
        const val MSG_MEDIA_PLAYBACK_INPUT = 32770
        const val MSG_MEDIA_PLAYBACK_METADATA = 32771

    }
}
