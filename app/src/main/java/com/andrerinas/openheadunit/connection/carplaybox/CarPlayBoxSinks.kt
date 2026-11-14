package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.decoder.audio.AudioDecoder
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.Settings

/** The manager reaches the decoders only through these, so video and audio never see each other. */
interface BoxVideoSink {
    fun decode(buf: ByteArray, off: Int, len: Int)
    /** One callback for the decoder's three recovery hooks. */
    fun hookKeyframeRequests(request: (reason: String) -> Unit)
    fun unhookKeyframeRequests()
    fun stop(reason: String)
}

interface BoxAudioSink {
    fun open()
    fun configure(channel: Int, format: CarPlayBoxAudioFormat.Format)
    fun write(channel: Int, buf: ByteArray, off: Int, len: Int)
    fun close()
}

interface BoxMic {
    fun start(onData: (buf: ByteArray, len: Int) -> Unit)
    fun stop()
}

class VideoDecoderBoxSink(private val videoDecoder: VideoDecoder) : BoxVideoSink {

    private var onError: ((String) -> Unit)? = null
    private var onStarved: (() -> Unit)? = null
    private var onDropped: (() -> Unit)? = null

    override fun decode(buf: ByteArray, off: Int, len: Int) =
        videoDecoder.decode(buf, off, len, false, VideoDecoder.CodecType.H264)

    override fun hookKeyframeRequests(request: (String) -> Unit) {
        val error: (String) -> Unit = { request("decoder error: $it") }
        val starved: () -> Unit = { request("keyframe starved") }
        val dropped: () -> Unit = { request("frame dropped") }
        onError = error
        onStarved = starved
        onDropped = dropped
        videoDecoder.onDecoderError = error
        videoDecoder.onKeyframeStarved = starved
        videoDecoder.onFrameDropped = dropped
    }

    /** Clears each hook only when it is still ours. */
    override fun unhookKeyframeRequests() {
        if (videoDecoder.onDecoderError === onError) videoDecoder.onDecoderError = null
        if (videoDecoder.onKeyframeStarved === onStarved) videoDecoder.onKeyframeStarved = null
        if (videoDecoder.onFrameDropped === onDropped) videoDecoder.onFrameDropped = null
        onError = null
        onStarved = null
        onDropped = null
    }

    override fun stop(reason: String) = videoDecoder.stop(reason)
}

class AudioDecoderBoxSink(
    private val audioDecoder: AudioDecoder,
    private val settings: Settings,
) : BoxAudioSink {

    private var session: AudioDecoder.PlaybackSession? = null

    override fun open() {
        session = audioDecoder.openSession(AudioDecoder.PlaybackCallbacks())
    }

    override fun configure(channel: Int, format: CarPlayBoxAudioFormat.Format) {
        val s = session ?: return
        audioDecoder.stop(channel, s)
        audioDecoder.start(
            channel, AudioConfigs.stream(channel, settings), format.sampleRate, 16, format.channels,
            audioLatencyMultiplier = settings.audioLatencyMultiplier,
            audioQueueCapacity = settings.audioQueueCapacity,
            attachHwDspEqualizer = settings.attachHwDspEqualizer,
            preferAAudio = settings.useAAudioOutput,
            session = s,
        )
    }

    override fun write(channel: Int, buf: ByteArray, off: Int, len: Int) {
        val s = session ?: return
        audioDecoder.decode(channel, buf, off, len, s)
    }

    override fun close() {
        session?.let { audioDecoder.closeSession(it) }
        session = null
    }
}

class MicRecorderBoxMic(private val micRecorder: MicRecorder) : BoxMic {

    override fun start(onData: (ByteArray, Int) -> Unit) {
        micRecorder.listener = object : MicRecorder.Listener {
            override fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int, peak: Int) =
                onData(mic_buf, mic_audio_len)
        }
        MicRecorder.postLifecycle { micRecorder.start() }
    }

    override fun stop() {
        MicRecorder.postLifecycle { micRecorder.stop() }
    }
}
