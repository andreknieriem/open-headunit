package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

internal class AapMessageHandlerType(
        private val transport: AapTransport,
        recorder: MicRecorder,
        private val aapAudio: AapAudio,
        private val aapVideo: AapVideo,
        settings: Settings,
        context: Context,
        onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null,
        onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null) : AapMessageHandler {

    private val aapControl: AapControl = AapControlGateway(transport, recorder, aapAudio, settings, context)
    private val mediaPlayback = AapMediaPlayback(onAaMediaMetadata, onAaPlaybackStatus)
    private val aapNavigation = AapNavigation(context, settings)

    private val dispatchMonitor = TransportDispatchMonitor()

    @Throws(AapMessageHandler.HandleException::class)
    override fun handle(message: AapMessage) {
        // Anything slow here is time the socket is not read, audio included. Video has its own
        // thread now, so this should stay near zero; videoQueue is where its backlog shows up.
        val dispatchStartMs = android.os.SystemClock.elapsedRealtime()
        try {
            dispatch(message)
        } finally {
            val finishedMs = android.os.SystemClock.elapsedRealtime()
            dispatchMonitor.onDispatch(
                message.channel, finishedMs - dispatchStartMs, finishedMs,
                transport.videoQueueDepth(), transport.videoShedCount()
            )
                ?.let { AppLog.i("AapTransport: %s", it) }
        }
    }

    private fun dispatch(message: AapMessage) {

        // Every decrypted inbound message passes through here, on every channel, which makes this
        // the one place that can say the link is alive rather than just that the picture is moving.
        // See AapTransport.lastMessageReceivedMs for why that distinction matters. The channel goes
        // with it so the media channels can be measured apart from the link: this fault stops video
        // and audio while leaving control running, and the three are one series here.
        transport.noteMessageReceived(message.channel, message.size)

        val msgType = message.type
        val flags = message.flags

        // 1. Video goes to its own thread (ID_VID), which sends the ack itself once the decode is
        // done. That ack is the phone's flow control and the only bound on the video backlog, so it
        // stays behind the work; what the demux buys is that it is no longer the read thread that
        // waits for it, and audio is read and acked on its own path throughout.
        if (Channel.isVideo(message.channel)) {
            // False means control traffic on the video channel, which falls through to step 5 as
            // it always has. The video thread still sees it either way.
            if (transport.dispatchVideo(message)) {
                return
            }
        }

        // 2. Try processing as Audio stream (Speech, System, Media)
        if (message.isAudio) {
            if (aapAudio.process(message)) {
                // Send ACK AFTER processing
                if (msgType == 0 || msgType == 1) {
                    transport.sendMediaAck(message.channel)
                }
                return
            }
        }

        // 3. Media Playback Status (separate channel)
        if (message.channel == Channel.ID_MPB && msgType > 31) {
            mediaPlayback.process(message)
            return
        }

        // 4. Navigation (turn-by-turn from any AA nav app)
        // Process only payload messages on NAV channel (>31).
        // Control/handshake messages on NAV channel must pass through to AapControl.
        if (message.channel == Channel.ID_NAV && msgType > 31) {
            if (aapNavigation.process(message)) {
                return
            }
        }

        // 5. Control Message Fallback
        if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AppLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AppLog.e("Unknown msg_type: %d, flags: %d, channel: %d", msgType, flags, message.channel)
        }
    }
}
