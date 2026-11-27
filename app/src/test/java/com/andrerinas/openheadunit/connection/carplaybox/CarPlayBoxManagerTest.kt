package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CarPlayBoxManagerTest {

    /** Plays the box side: queued chunks are read in order; a marker closes or fails the link. */
    private class FakeBox : ProjectionConnection {
        private val closeMarker = ByteArray(0)
        private val errorMarker = ByteArray(1)
        private val incoming = LinkedBlockingQueue<ByteArray>()
        val written = CopyOnWriteArrayList<ByteArray>()
        @Volatile var connected = false
        @Volatile var disconnects = 0

        override val type = ProjectionConnection.Type.USB
        override val isConnected get() = connected
        override val isSingleMessage = false
        override suspend fun connect(): Boolean { connected = true; return true }
        override fun disconnect() { disconnects++; connected = false; incoming.offer(closeMarker) }
        override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
            written.add(buf.copyOf(length))
            return length
        }
        override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
            while (true) {
                val next = incoming.poll(20, TimeUnit.MILLISECONDS)
                if (next == null) { if (!connected) return -1 else continue }
                if (next === closeMarker) { connected = false; return -1 }
                if (next === errorMarker) return -1
                System.arraycopy(next, 0, buf, 0, next.size)
                return next.size
            }
        }
        fun push(bytes: ByteArray) { incoming.offer(bytes) }
        fun pushError() { incoming.offer(errorMarker) }
        fun types() = written.map { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt(8) }
        fun commands() = written.filter { types0(it) == CarPlayBoxMessages.TYPE_COMMAND }
            .map { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt(16) }
        private fun types0(f: ByteArray) = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
    }

    private class FakeVideo : BoxVideoSink {
        val chunks = CopyOnWriteArrayList<ByteArray>()
        var request: ((String) -> Unit)? = null
        var hooked = false
        var stops = 0
        override fun decode(buf: ByteArray, off: Int, len: Int) { chunks.add(buf.copyOfRange(off, off + len)) }
        override fun hookKeyframeRequests(request: (String) -> Unit) { this.request = request; hooked = true }
        override fun unhookKeyframeRequests() { hooked = false }
        override fun stop(reason: String) { stops++ }
    }

    private class FakeAudio : BoxAudioSink {
        val configured = CopyOnWriteArrayList<Pair<Int, CarPlayBoxAudioFormat.Format>>()
        val writes = CopyOnWriteArrayList<Pair<Int, Int>>()
        var closed = 0
        override fun open() = Unit
        override fun configure(channel: Int, format: CarPlayBoxAudioFormat.Format) { configured.add(channel to format) }
        override fun write(channel: Int, buf: ByteArray, off: Int, len: Int) { writes.add(channel to len) }
        override fun close() { closed++ }
    }

    private class FakeMic : BoxMic {
        var onData: ((ByteArray, Int) -> Unit)? = null
        var running = false
        override fun start(onData: (ByteArray, Int) -> Unit) { this.onData = onData; running = true }
        override fun stop() { running = false }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val givenBack = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
    private val logs = CopyOnWriteArrayList<String>()
    private var savedLogger: AppLog.Logger? = null
    @Volatile private var now = 1_000L
    private var aaLive = false
    private lateinit var video: FakeVideo
    private lateinit var audio: FakeAudio
    private lateinit var mic: FakeMic
    private lateinit var manager: CarPlayBoxManager

    @Before
    fun setUp() {
        savedLogger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) { logs.add(msg) }
        }
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = { now }
        ConnectionArbiter.actions = object : ConnectionArbiter.Actions {
            override fun preempt(loser: ConnectionArbiter.Claim) = Unit
            override fun standDownWireless(by: ConnectionArbiter.Claim) = true
            override fun giveBack(wireless: Boolean, usb: Boolean) { givenBack.add(wireless to usb) }
            override fun schedule(delayMs: Long, block: () -> Unit) = Unit
        }
        video = FakeVideo()
        audio = FakeAudio()
        mic = FakeMic()
        manager = CarPlayBoxManager(
            scope, video, audio, mic,
            aaSessionLive = { aaLive }, displaySize = { 1025 to 601 }, densityDpi = { 240 },
            clock = { now }, tickMs = 3_600_000L,
        )
    }

    @After
    fun tearDown() {
        manager.stop("test over")
        scope.cancel()
        ConnectionArbiter.actions = null
        ConnectionArbiter.reset()
        savedLogger?.let { AppLog.LOGGER = it }
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun begin(box: FakeBox = FakeBox()): FakeBox {
        assertTrue(manager.start("1314:1520", box))
        waitFor("open") { box.types().size >= 5 }
        return box
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun videoFrame(stream: ByteArray) =
        CarPlayBoxFrameCodec.encode(0x06, le(20).putInt(1280).putInt(720).array() + stream)

    private fun audioFrame(decodeType: Int, audioType: Int, pcm: ByteArray) = CarPlayBoxFrameCodec.encode(
        0x07, le(12).putInt(decodeType).putFloat(0f).putInt(audioType).array() + pcm,
    )

    private fun boxCommand(n: Int) = CarPlayBoxFrameCodec.encode(0x08, le(4).putInt(n).array())

    @Test
    fun `heartbeat first then the init frames then open`() {
        val box = begin()
        assertEquals(listOf(0xAA, 0x99, 0x08, 0x08, 0x01), box.types().take(5))
        assertEquals(listOf(7, 23), box.commands().take(2))
        val open = ByteBuffer.wrap(box.written[4]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1024, open.getInt(16))
        assertEquals(600, open.getInt(20))
    }

    @Test
    fun `a video frame split over three reads reaches the sink once`() {
        val box = begin()
        val frame = videoFrame(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 3, 4, 5, 6, 7, 8))
        box.push(frame.copyOfRange(0, 10))
        box.push(frame.copyOfRange(10, 25))
        box.push(frame.copyOfRange(25, frame.size))
        waitFor("video") { video.chunks.size == 1 }
        assertEquals(listOf<Byte>(0, 0, 0, 1, 0x67), video.chunks[0].toList().take(5))
        assertTrue(logs.any { it.contains("CarPlayBox: video 1280x720") })
    }

    @Test
    fun `call audio by decode type 5 reaches the call channel`() {
        val box = begin()
        box.push(audioFrame(5, 0, ByteArray(960)))
        waitFor("audio") { audio.writes.size == 1 }
        assertEquals(Channel.ID_AU2 to 960, audio.writes[0])
        assertEquals(16000, audio.configured[0].second.sampleRate)
        box.push(audioFrame(5, 0, ByteArray(960)))
        waitFor("second write") { audio.writes.size == 2 }
        assertEquals(1, audio.configured.size)
    }

    @Test
    fun `box commands 7 and 8 start and stop the microphone`() {
        val box = begin()
        box.push(boxCommand(7))
        waitFor("mic on") { mic.running }
        mic.onData!!.invoke(ByteArray(640) { 1 }, 640)
        val last = box.written.last()
        val b = ByteBuffer.wrap(last).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(7, b.getInt(8))
        assertEquals(3, b.getInt(24))
        assertEquals(16 + 12 + 640, last.size)
        box.push(boxCommand(8))
        waitFor("mic off") { !mic.running }
    }

    @Test
    fun `a decoder keyframe callback writes one command 12 and two within a second write one`() {
        val box = begin()
        val before = box.commands().count { it == 12 }
        video.request!!.invoke("decoder error")
        video.request!!.invoke("frame dropped")
        assertEquals(before + 1, box.commands().count { it == 12 })
        now += 1500
        video.request!!.invoke("frame dropped")
        assertEquals(before + 2, box.commands().count { it == 12 })
    }

    @Test
    fun `a closed link ends the session once and gives wireless back`() {
        val box = begin()
        assertTrue(video.hooked)
        box.connected = false
        box.push(ByteArray(1))
        waitFor("end") { logs.any { it.contains("CarPlayBox: link lost") } }
        assertFalse(manager.isLive)
        assertEquals(1, logs.count { it.contains("CarPlayBox: link lost") })
        assertFalse(video.hooked)
        assertEquals(1, video.stops)
        assertEquals(listOf(true to false), givenBack.toList())
        manager.stop("again")
        assertEquals(1, logs.count { it.contains("CarPlayBox: link lost") })
    }

    @Test
    fun `a read error on a connected link is retried and silence ends nothing`() {
        val box = begin()
        box.pushError()
        box.pushError()
        now += 60_000
        Thread.sleep(150)
        assertTrue(manager.isLive)
        assertEquals(0, logs.count { it.contains("CarPlayBox: link lost") })
    }

    @Test
    fun `start during a live session returns false and writes nothing`() {
        begin()
        val second = FakeBox()
        assertFalse(manager.start("again", second))
        assertTrue(second.written.isEmpty())
        assertEquals(0, second.disconnects)
    }

    @Test
    fun `a user exit sets the latch and only a detach clears it`() {
        begin()
        manager.stop("user exit")
        assertTrue(manager.boxUserExited)
        assertEquals(
            CarPlayBoxPolicy.Attach.UserExited,
            CarPlayBoxPolicy.attach(true, true, manager.isLive, manager.boxUserExited, false, true),
        )
        manager.onBoxDetached()
        assertFalse(manager.boxUserExited)
    }

    @Test
    fun `box commands the app does not know are logged with a count`() {
        val box = begin()
        box.push(boxCommand(12))
        waitFor("first") { logs.any { it.contains("box command 12 (#1)") } }
        box.push(boxCommand(12))
        waitFor("second") { logs.any { it.contains("box command 12 (#2)") } }
    }

    @Test
    fun `start returns false and writes nothing when the arbiter refuses`() {
        aaLive = true
        val box = FakeBox()
        assertFalse(manager.start("1314:1520", box))
        assertTrue(box.written.isEmpty())
        assertFalse(manager.isLive)
    }

    @Test
    fun `a second session after a stop starts from a clean state`() {
        val first = begin()
        manager.stop("unplugged")
        assertFalse(manager.isLive)
        val second = begin()
        assertEquals(0xAA, second.types().first())
        assertTrue(manager.isLive)
        assertTrue(first.disconnects >= 1)
    }

    @Test
    fun `a surface ready asks for a keyframe`() {
        val box = begin()
        manager.onSurfaceReady(1024, 600)
        assertTrue(logs.any { it.contains("CarPlayBox: surface ready 1024x600") })
        assertTrue(logs.any { it.contains("CarPlayBox: keyframe requested (surface ready)") })
        assertTrue(box.commands().contains(12))
    }
}
