package com.andrerinas.openheadunit.connection.carplaybox

import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxLinkSession.Action
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxLinkSession.Event
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxLinkSession.Phase
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs one CarPlay box session: reads frames, feeds [CarPlayBoxLinkSession] and runs its actions.
 * Rules live in the pure objects; this class holds the I/O and the session's fields.
 */
class CarPlayBoxManager(
    private val scope: CoroutineScope,
    private val videoSink: BoxVideoSink,
    private val audioSink: BoxAudioSink,
    private val mic: BoxMic,
    private val aaSessionLive: () -> Boolean,
    private val displaySize: () -> Pair<Int, Int>,
    private val densityDpi: () -> Int,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val tickMs: Long = 250L,
) {

    private val lifecycleLock = Any()
    private val reducerLock = Any()
    private val writeLock = Any()

    @Volatile private var connection: ProjectionConnection? = null
    @Volatile private var live = false
    private var tickJob: Job? = null
    private var linkState = CarPlayBoxLinkSession.State()
    private var decoder = CarPlayBoxFrameCodec.Decoder()
    private val commandCounts = HashMap<Int, Int>()
    private val audioDecodeTypes = HashMap<Int, Int>()
    private var micOn = false
    private var encryptedLogged = false
    private var lastVideoSize: Pair<Int, Int>? = null
    @Volatile private var touchFlags = 2
    @Volatile private var keyframeReason = "requested"
    private var readerThread: Thread? = null

    private val phaseFlow = MutableStateFlow<Phase>(Phase.Idle)
    val state: StateFlow<Phase> get() = phaseFlow
    private val videoSizeFlow = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> get() = videoSizeFlow

    val isLive: Boolean get() = live

    /** Set by a user exit; only [onBoxDetached] clears it, so the box is not reopened until it leaves the bus. */
    @Volatile var boxUserExited = false
        private set

    /** False, with nothing written, while a session is live or the arbiter refuses. */
    fun start(label: String, connection: ProjectionConnection): Boolean {
        synchronized(lifecycleLock) {
            if (live) {
                AppLog.i("CarPlayBoxManager: start refused, a session is live")
                return false
            }
            if (!ConnectionArbiter.beginBoxSession("CarPlay box $label", aaSessionLive())) return false
            live = true
            this.connection = connection
            synchronized(reducerLock) { linkState = CarPlayBoxLinkSession.State() }
            decoder = CarPlayBoxFrameCodec.Decoder()
            commandCounts.clear()
            audioDecodeTypes.clear()
            micOn = false
            encryptedLogged = false
            lastVideoSize = null
            videoSizeFlow.value = null
            touchFlags = 2
            phaseFlow.value = Phase.Idle
        }
        AppLog.i("CarPlayBox: session start on $label")
        videoSink.hookKeyframeRequests { reason -> requestKeyframe(reason) }
        audioSink.open()
        scope.launch(Dispatchers.IO) {
            val opened = try { connection.connect() } catch (e: Exception) { false }
            if (!opened) {
                stop("open failed")
                return@launch
            }
            if (!live) return@launch
            startReader(connection)
            tickJob = scope.launch { while (isActive) { delay(tickMs); feed(Event.Tick) } }
            if (!live) tickJob?.cancel()
            feed(Event.Started)
        }
        return true
    }

    /** The one end path: unplug, user exit, a read error and a reducer End all come here. */
    fun stop(reason: String) {
        val closing: ProjectionConnection?
        synchronized(lifecycleLock) {
            if (!live) return
            live = false
            closing = connection
            connection = null
            if (reason == "user exit") boxUserExited = true
        }
        synchronized(reducerLock) {
            if (linkState.phase !is Phase.Ended) linkState = linkState.copy(phase = Phase.Ended(reason))
        }
        tickJob?.cancel()
        tickJob = null
        try {
            if (micOn) { micOn = false; mic.stop(); AppLog.i("CarPlayBox: microphone off") }
            videoSink.unhookKeyframeRequests()
            audioSink.close()
            videoSink.stop("CarPlay box ended")
            closing?.disconnect()
        } finally {
            ConnectionArbiter.endBoxSession()
        }
        AppLog.i("CarPlayBox: link lost ($reason)")
        phaseFlow.value = Phase.Ended(reason)
    }

    /** The box left the bus; it may be opened again when it returns. */
    fun onBoxDetached() {
        boxUserExited = false
    }

    fun onSurfaceReady(width: Int, height: Int) {
        AppLog.i("CarPlayBox: surface ready ${width}x$height")
        keyframeReason = "surface ready"
        feed(Event.SurfaceReady)
    }

    fun sendTouch(action: Int, touch: CarPlayBoxTouchScaler.BoxTouch) {
        if (!live) return
        send(CarPlayBoxMessages.touch(action, touch.x, touch.y, touchFlags))
    }

    private fun requestKeyframe(reason: String) {
        keyframeReason = reason
        feed(Event.DecoderAskedKeyframe)
    }

    private fun startReader(conn: ProjectionConnection) {
        val thread = Thread({ readLoop(conn) }, "carplay_box_reader")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    private fun readLoop(conn: ProjectionConnection) {
        val buf = ByteArray(CarPlayBoxFrameCodec.WRITE_CHUNK)
        try {
            while (live) {
                // Timeout 0: a silent box is not an error, and a timed read would count as one.
                val n = conn.recvBlocking(buf, buf.size, 0, false)
                if (n < 0) {
                    if (!conn.isConnected) break
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }
                if (n == 0) continue
                for (decoded in decoder.feed(buf, 0, n)) {
                    when (decoded) {
                        is CarPlayBoxFrameCodec.Decoded.Message -> handle(decoded.frame)
                        is CarPlayBoxFrameCodec.Decoded.Encrypted -> {
                            if (!encryptedLogged) {
                                encryptedLogged = true
                                AppLog.w("CarPlayBox: encrypted frame, unsupported")
                            }
                        }
                        is CarPlayBoxFrameCodec.Decoded.Overlong -> {
                            feed(Event.StreamEnded("bad frame length ${decoded.declared}"))
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("CarPlayBox: read failed: ${e.message}")
        }
        if (live) feed(Event.StreamEnded("read error"))
    }

    private fun handle(frame: CarPlayBoxFrameCodec.Frame) {
        when (frame.type) {
            CarPlayBoxMessages.TYPE_VIDEO -> handleVideo(frame.payload)
            CarPlayBoxMessages.TYPE_AUDIO -> handleAudio(frame.payload)
            CarPlayBoxMessages.TYPE_OPEN -> feed(Event.OpenEchoed)
            CarPlayBoxMessages.TYPE_PLUGGED -> feed(Event.PhoneConnected)
            CarPlayBoxMessages.TYPE_UNPLUGGED -> feed(Event.PhoneGone)
            CarPlayBoxMessages.TYPE_SOFTWARE_VERSION ->
                feed(Event.VersionSeen(CarPlayBoxMessages.parseSoftwareVersion(frame.payload)))
            CarPlayBoxMessages.TYPE_COMMAND -> {
                val n = CarPlayBoxMessages.parseCommand(frame.payload) ?: return
                if (n != CarPlayBoxLinkSession.BOX_CMD_MIC_ON && n != CarPlayBoxLinkSession.BOX_CMD_MIC_OFF) {
                    val k = (commandCounts[n] ?: 0) + 1
                    commandCounts[n] = k
                    AppLog.i("CarPlayBox: box command $n (#$k)")
                }
                feed(Event.BoxCommand(n))
            }
        }
    }

    private fun handleVideo(payload: ByteArray) {
        val header = CarPlayBoxMessages.parseVideo(payload) ?: return
        touchFlags = header.touchFlags
        val size = header.width to header.height
        if (size != lastVideoSize) {
            lastVideoSize = size
            videoSizeFlow.value = size
            AppLog.i("CarPlayBox: video ${header.width}x${header.height}")
        }
        val len = payload.size - CarPlayBoxMessages.VIDEO_HEADER_SIZE
        if (len > 0) videoSink.decode(payload, CarPlayBoxMessages.VIDEO_HEADER_SIZE, len)
    }

    private fun handleAudio(payload: ByteArray) {
        val header = CarPlayBoxMessages.parseAudio(payload) ?: return
        if (header.command != null) return
        val channel = CarPlayBoxAudioRouting.channelFor(header.decodeType, header.audioType) ?: return
        val format = CarPlayBoxAudioFormat.forDecodeType(header.decodeType) ?: return
        if (audioDecodeTypes[channel] != header.decodeType) {
            audioDecodeTypes[channel] = header.decodeType
            AppLog.i("CarPlayBox: audio ${Channel.name(channel)} " +
                "${format.sampleRate}Hz/${format.channels}")
            audioSink.configure(channel, format)
        }
        val len = payload.size - CarPlayBoxMessages.AUDIO_HEADER_SIZE
        if (len > 0) audioSink.write(channel, payload, CarPlayBoxMessages.AUDIO_HEADER_SIZE, len)
    }

    private fun feed(event: Event) {
        val actions: List<Action>
        synchronized(reducerLock) {
            val result = CarPlayBoxLinkSession.reduce(linkState, event, clock())
            linkState = result.state
            actions = result.actions
            if (result.state.phase !is Phase.Ended) phaseFlow.value = result.state.phase
        }
        for (action in actions) run(action)
    }

    private fun run(action: Action) {
        when (action) {
            Action.SendHeartBeat -> send(CarPlayBoxMessages.heartbeat())
            Action.SendInit -> {
                send(CarPlayBoxMessages.sendFileInt("/tmp/screen_dpi", densityDpi()))
                send(CarPlayBoxMessages.command(CarPlayBoxMessages.CMD_MIC_CAR))
                send(CarPlayBoxMessages.command(CarPlayBoxMessages.CMD_AUDIO_OVER_USB))
            }
            Action.SendOpen -> {
                val (w, h) = CarPlayBoxMessages.openSize(displaySize().first, displaySize().second)
                send(CarPlayBoxMessages.open(w, h, 30, 5, 49152, 2))
            }
            Action.SendKeyframeRequest -> {
                AppLog.i("CarPlayBox: keyframe requested ($keyframeReason)")
                send(CarPlayBoxMessages.command(CarPlayBoxMessages.CMD_KEYFRAME))
            }
            Action.MicOn -> if (!micOn) {
                micOn = true
                AppLog.i("CarPlayBox: microphone on")
                mic.start { buf, len -> send(CarPlayBoxMessages.micAudio(buf, len)) }
            }
            Action.MicOff -> if (micOn) {
                micOn = false
                AppLog.i("CarPlayBox: microphone off")
                mic.stop()
            }
            is Action.End -> stop(action.reason)
            is Action.Log -> AppLog.i("CarPlayBox: ${action.line}")
        }
    }

    private fun send(frame: ByteArray) {
        val conn = connection ?: return
        synchronized(writeLock) {
            for (chunk in CarPlayBoxFrameCodec.chunks(frame)) {
                if (conn.sendBlocking(chunk, chunk.size, WRITE_TIMEOUT_MS) < 0) {
                    AppLog.w("CarPlayBox: write failed")
                    return
                }
            }
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}
