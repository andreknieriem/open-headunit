package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.protoUint32ToLong

internal class AapMediaPlayback(
    private val onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)?,
    private val onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)?,
    private val presentation: PresentationQueue,
) {
    private var latestStatus = MediaPlayback.MediaPlaybackStatus.getDefaultInstance()

    /** The transport has already reassembled and validated this complete protobuf message. */
    fun process(message: AapMessage) {
        when (message.type) {
            MSG_MEDIA_PLAYBACK_METADATA -> {
                try {
                    val metadata = message.parse(MediaPlayback.MediaMetaData.newBuilder()).build()
                    presentation.submit(PresentationQueue.Slot.METADATA) { onAaMediaMetadata?.invoke(metadata) }
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
            // Status fields are optional: a position-only update must retain the previous
            // PLAYING/PAUSED state when several packets become one display update. Known fields
            // are singular; discard unknown fields rather than accumulate them for the session.
            latestStatus = latestStatus.toBuilder().mergeFrom(status)
                .setUnknownFields(com.google.protobuf.UnknownFieldSet.getDefaultInstance()).build()
            val snapshot = latestStatus
            presentation.submit(PresentationQueue.Slot.PLAYBACK) { onAaPlaybackStatus?.invoke(snapshot) }
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
