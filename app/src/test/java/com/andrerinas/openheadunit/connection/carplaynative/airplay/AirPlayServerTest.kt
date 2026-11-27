package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxAudioFormat
import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayTouchReport.Contact
import com.andrerinas.openheadunit.connection.carplaynative.codec.BinaryPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The server on the loopback address with a phone that speaks the wire layout. Cases follow LIVI `stack/tests.rs`. */
class AirPlayServerTest {

    private class Events : AirPlayServer.Listener {
        val peers = CopyOnWriteArrayList<String>()
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        val streaming = java.util.concurrent.atomic.AtomicInteger()
        val ended = CopyOnWriteArrayList<String>()
        val beforeSetup = CopyOnWriteArrayList<String>()
        override fun onControlConnection(peer: String) { peers.add(peer) }
        override fun onVideoSize(width: Int, height: Int) { sizes.add(width to height) }
        override fun onStreaming() { streaming.incrementAndGet() }
        override fun onEnded(reason: String) { ended.add(reason) }
        override fun onEndedBeforeSetup(reason: String) { beforeSetup.add(reason) }
    }

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val clock = AtomicLong(0)
    private val identity = AirPlayIdentity.generate()
    private val video = RecordingVideoSink()
    private val audio = RecordingAudioSink()
    private val events = Events()
    private val log = LogCapture()
    private val toClose = ArrayList<AutoCloseable>()
    private lateinit var server: AirPlayServer
    private var port = 0

    private fun info(width: Long = 1280, height: Long = 720) = InfoPlist.Config(
        deviceName = "OHU", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02", sourceVersion = "1.0",
        model = "OHU", manufacturer = "OHU", hevc = false,
        main = InfoPlist.Display(width, height, viewArea = InfoPlist.Insets()),
        entertainmentSampleRate = 48000, disableAudioOutput = false, oemLabel = "OHU", rightHandDrive = false,
        appearanceSetting = 1, offerMainHighMedia = false, offerOpus = false,
    )

    private fun newServer(acceptsPeer: (InetAddress) -> Boolean = { true }): AirPlayServer {
        val made = AirPlayServer(
            loopback, info(), identity, PairingStore(), StubSigner(), video, audio, events, { clock.get() }, acceptsPeer,
        )
        toClose.add(made)
        port = made.start()
        return made
    }

    private fun phone(): FakeAirPlayPhone = FakeAirPlayPhone(loopback, port).also { toClose.add(it) }

    // Threads alive before this test began, so a slow thread of another test is not counted here.
    private lateinit var baseline: Set<Thread>

    @Before fun setUp() {
        baseline = Thread.getAllStackTraces().keys.toSet()
        server = newServer()
    }

    @After fun tearDown() {
        toClose.asReversed().forEach { runCatching { it.close() } }
        log.close()
    }

    private fun threadsAlive() = Thread.getAllStackTraces().keys.filter {
        it.isAlive && it !in baseline && listOf("carplay-airplay", "carplay-screen", "carplay-audio", "carplay-timing", "carplay-keepalive", "carplay-event").any { p -> it.name.startsWith(p) }
    }

    /** A paired phone with a session, an open event channel and a timing socket. */
    private class Session(val phone: FakeAirPlayPhone, val ports: FakeAirPlayPhone.Ports, val timing: DatagramSocket)

    private fun session(): Session {
        val p = phone()
        p.pair(identity)
        assertEquals(200, p.authSetup().status)
        val timing = DatagramSocket(0, loopback).also { toClose.add(it) }
        val ports = p.setupSession(timing)
        p.connectEvents(ports.event)
        assertTrue(awaitTrue { log.count("event channel open") == 1 })
        return Session(p, ports, timing)
    }

    @Test fun infoIsOpenAndTheRestNeedsPairVerify() {
        val p = phone()
        val info = p.info()
        assertNotNull(info["displays"])
        assertEquals(470, p.request("SETUP", "/s", ByteArray(0)).status)
        assertEquals(470, p.request("RECORD", "/s").status)
        assertEquals(470, p.request("POST", "/command", ByteArray(0)).status)
        assertEquals(200, p.request("OPTIONS", "*").status)
    }

    @Test fun pairingAuthSetupAndSetupRunOverTheWire() {
        val s = session()
        assertEquals(1, log.count("pair-verify done"))
        assertEquals(1, log.count("auth-setup done"))
        assertEquals(1, events.peers.size)
        assertTrue(s.ports.keepAlive != null && s.ports.keepAlive!! > 0)
        assertTrue(s.ports.timing > 0 && s.ports.event > 0)
        assertTrue(awaitTrue { threadsAlive().isNotEmpty() })
    }

    @Test fun aSessionSetupWithoutKeepAliveGetsNoKeepAlivePort() {
        val p = phone()
        p.pair(identity)
        assertEquals(null, p.setupSession(null, keepAlive = false).keepAlive)
    }

    @Test fun aSecondSessionSetupIsRefused() {
        val s = session()
        assertEquals(400, s.phone.request("SETUP", "/s", BinaryPlist.encode(PlistValue.dict("name" to PlistValue.Text("x"))), RtspRoutes.CONTENT_PLIST).status)
    }

    @Test fun screenConfigAndFramesReachTheVideoSinkAsAnnexBWithParameterSetsFirst() {
        val s = session()
        val answer = s.phone.setupStreams(s.phone.stream(110, 42))
        assertEquals(110L, answer.single()["type"]!!.asLong)
        val screen = s.phone.connectScreen(answer.single()["dataPort"]!!.asLong!!.toInt())
        val sps = H264Fixtures.sps(320, 240)
        s.phone.sendScreenConfig(screen, H264Fixtures.avcc(sps))
        s.phone.sendScreenFrame(screen, 42, byteArrayOf(0, 0, 0, 3, 0x65, 1, 2))
        s.phone.sendScreenFrame(screen, 42, byteArrayOf(0, 0, 0, 2, 0x41, 9, 0, 0, 0, 1, 0x06))
        assertTrue(awaitTrue { video.decoded.size == 3 })
        val start = byteArrayOf(0, 0, 0, 1)
        assertArrayEquals(start + sps + start + H264Fixtures.pps, video.decoded[0])
        assertArrayEquals(start + byteArrayOf(0x65, 1, 2), video.decoded[1])
        assertArrayEquals(start + byteArrayOf(0x41, 9) + start + byteArrayOf(0x06), video.decoded[2])
        assertEquals(listOf(320 to 240), events.sizes)
        assertEquals(1, events.streaming.get())
        assertEquals(1, log.count("video 320x240"))
    }

    @Test fun aFrameWithABadTagIsDroppedAndLoggedOncePerSession() {
        val s = session()
        val answer = s.phone.setupStreams(s.phone.stream(110, 7))
        val screen = s.phone.connectScreen(answer.single()["dataPort"]!!.asLong!!.toInt())
        s.phone.sendScreenConfig(screen, H264Fixtures.avcc(H264Fixtures.sps(320, 240)))
        s.phone.sendScreenFrame(screen, 7, byteArrayOf(0, 0, 0, 2, 0x65, 1), tamper = true)
        s.phone.sendScreenFrame(screen, 7, byteArrayOf(0, 0, 0, 2, 0x65, 1), tamper = true)
        s.phone.sendScreenFrame(screen, 7, byteArrayOf(0, 0, 0, 2, 0x41, 2))
        assertTrue(awaitTrue { video.decoded.size == 2 })
        assertEquals(1, log.count("failed its tag"))
        assertEquals(1, events.streaming.get())
    }

    @Test fun aScreenStreamWithoutAConnectionIdIsRefused() {
        val s = session()
        assertTrue(s.phone.setupStreams(s.phone.stream(110, null)).isEmpty())
        assertEquals(1, log.count("no streamConnectionID; refused"))
    }

    @Test fun anUnservedStreamTypeStaysOutOfTheAnswer() {
        val s = session()
        val answer = s.phone.setupStreams(s.phone.stream(130, 1), s.phone.stream(111, 2), s.phone.stream(102, 3))
        assertTrue(answer.isEmpty())
    }

    @Test fun pcmAudioIsSwappedToLittleEndianOnTheMediaChannelWhateverItsAudioType() {
        val s = session()
        val answer = s.phone.setupStreams(
            s.phone.stream(100, 11, "alert", 0x8000), s.phone.stream(101, 12, "media", 0x8000),
        )
        assertEquals(2, answer.size)
        s.phone.sendAudio(answer[0]["dataPort"]!!.asLong!!.toInt(), 11, byteArrayOf(1, 2, 3, 4))
        s.phone.sendAudio(answer[1]["dataPort"]!!.asLong!!.toInt(), 12, byteArrayOf(5, 6), sequence = 1)
        assertTrue(awaitTrue { audio.written.size == 2 })
        val byChannel = audio.written.associate { it.first to it.second.toList() }
        assertEquals(listOf<Byte>(2, 1, 4, 3), byChannel[Channel.ID_AUD])
        assertEquals(listOf<Byte>(6, 5), byChannel[Channel.ID_AU1])
        assertEquals(setOf(Channel.ID_AUD, Channel.ID_AU1), audio.configured.map { it.first }.toSet())
        assertEquals(CarPlayBoxAudioFormat.Format(48_000, 2), audio.configured.first().second)
        assertEquals(1, log.count("audio 100 alert 48000Hz/2"))
    }

    @Test fun aStreamWhoseFormatThePlayerCannotPlayGetsPortsAndItsPacketsAreDroppedWithOneLogLine() {
        val s = session()
        val answer = s.phone.setupStreams(s.phone.stream(100, 21, "telephony", 0x1000_0000L))
        assertEquals(1, answer.size)
        assertTrue(answer.single()["dataPort"]!!.asLong!! > 0)
        s.phone.sendAudio(answer.single()["dataPort"]!!.asLong!!.toInt(), 21, byteArrayOf(1, 2))
        s.phone.sendAudio(answer.single()["dataPort"]!!.asLong!!.toInt(), 21, byteArrayOf(1, 2), sequence = 2)
        Thread.sleep(200)
        assertTrue(audio.written.isEmpty())
        assertTrue(audio.configured.isEmpty())
        assertEquals(1, log.count("its packets are dropped"))
    }

    @Test fun aTouchWritesOneHidReportAtTheDeclaredDisplayAndTheAnswerIsLogged() {
        val s = session()
        assertTrue(server.sendTouch(listOf(Contact(0, 160.0, 120.0, true)), 320, 240))
        val message = s.phone.nextEvent()
        assertEquals("hidSendReport", s.phone.eventType(message))
        val dict = BinaryPlist.decode(message.body) as PlistValue.Dict
        assertEquals("2a2a2a2a", dict["uuid"]!!.asText)
        assertArrayEquals(byteArrayOf(0, 1, 0x80.toByte(), 0x02, 0x68, 0x01, 1, 0, 0, 0, 0, 0), dict["hidReport"]!!.asBytes)
        s.phone.answerEvent(message)
        assertTrue(awaitTrue { log.count("command hidSendReport answered (#1)") == 1 })
    }

    @Test fun aTouchOutsideThePictureIsClampedToTheDeclaredDisplay() {
        val s = session()
        server.sendTouch(listOf(Contact(0, 400.0, 300.0, true)), 320, 240)
        val dict = BinaryPlist.decode(s.phone.nextEvent().body) as PlistValue.Dict
        assertArrayEquals(byteArrayOf(0, 1, 0x00, 0x05, 0xd0.toByte(), 0x02), dict["hidReport"]!!.asBytes!!.copyOf(6))
    }

    @Test fun aTouchBeforeTheEventChannelIsOpenIsDroppedAndLoggedOnce() {
        val p = phone()
        p.pair(identity)
        assertFalse(server.sendTouch(listOf(Contact(0, 1.0, 1.0, true)), 320, 240))
        assertFalse(server.sendTouch(listOf(Contact(0, 1.0, 1.0, true)), 320, 240))
        assertEquals(1, log.count("touch dropped"))
    }

    @Test fun twoKeyframeRequestsInOneSecondWriteOneAndADroppedOneDoesNotMoveTheWindow() {
        val s = session()
        clock.set(100)
        server.requestKeyframe("new surface")
        clock.set(600)
        server.requestKeyframe("decoder error: x")
        clock.set(1_100)
        server.requestKeyframe("keyframe starved")
        server.sendTouch(listOf(Contact(0, 1.0, 1.0, false)), 320, 240)
        val types = (1..3).map { s.phone.eventType(s.phone.nextEvent()) }
        assertEquals(listOf("forceKeyFrame", "forceKeyFrame", "hidSendReport"), types)
        assertEquals(1, log.count("keyframe requested (new surface)"))
        assertEquals(0, log.count("keyframe requested (decoder error: x)"))
        assertEquals(1, log.count("keyframe requested (keyframe starved)"))
    }

    @Test fun theKeyframeCommandHasNoUuidSoTheMainScreenRestarts() {
        val s = session()
        server.requestKeyframe("frame dropped")
        val dict = BinaryPlist.decode(s.phone.nextEvent().body) as PlistValue.Dict
        assertEquals("forceKeyFrame", dict["type"]!!.asText)
        assertEquals(PlistValue.Dict(emptyList()), dict["params"])
    }

    @Test fun aKeyframeRequestBeforeTheEventChannelIsOpenWritesNothingAndKeepsTheWindowFree() {
        val p = phone()
        p.pair(identity)
        server.requestKeyframe("new surface")
        assertEquals(0, log.count("keyframe requested"))
        val timing = DatagramSocket(0, loopback).also { toClose.add(it) }
        val ports = p.setupSession(timing)
        p.connectEvents(ports.event)
        assertTrue(awaitTrue { log.count("event channel open") == 1 })
        server.requestKeyframe("new surface")
        assertEquals("forceKeyFrame", p.eventType(p.nextEvent()))
    }

    @Test fun aPhoneThatFallsSilentEndsTheSessionOnce() {
        val s = session()
        s.phone.setupStreams(s.phone.stream(110, 5))
        clock.set(5_000)
        Thread.sleep(400)
        assertTrue(events.ended.isEmpty())
        clock.set(11_000)
        assertTrue(awaitTrue { events.ended.size == 1 })
        assertTrue(events.ended.single().contains("silent"))
        Thread.sleep(500)
        assertEquals(1, events.ended.size)
    }

    @Test fun aPhoneThatKeepsTalkingIsNotSilent() {
        val s = session()
        s.phone.setupStreams(s.phone.stream(110, 5))
        clock.set(9_000)
        s.phone.request("POST", "/feedback")
        clock.set(18_000)
        Thread.sleep(500)
        assertTrue(events.ended.isEmpty())
        clock.set(19_500)
        assertTrue(awaitTrue { events.ended.size == 1 })
    }

    @Test fun aTeardownClosesOneStreamOrAllAndKeepsTheControlConnection() {
        val s = session()
        val answer = s.phone.setupStreams(s.phone.stream(110, 5), s.phone.stream(100, 6, "media", 0x8000))
        val screenPort = answer[0]["dataPort"]!!.asLong!!.toInt()
        val one = BinaryPlist.encode(PlistValue.dict("streams" to PlistValue.Items(listOf(s.phone.stream(100, null)))))
        assertEquals(200, s.phone.request("TEARDOWN", "/s", one, RtspRoutes.CONTENT_PLIST).status)
        assertEquals(200, s.phone.request("POST", "/feedback").status)
        assertEquals(200, s.phone.request("TEARDOWN", "/s", ByteArray(0)).status)
        assertTrue(awaitTrue { !canConnect(screenPort) })
        assertEquals(200, s.phone.request("POST", "/feedback").status)
        assertTrue(events.ended.isEmpty())
    }

    private fun canConnect(port: Int) = try { java.net.Socket(loopback, port).use { true } } catch (_: java.io.IOException) { false }

    @Test fun theScreenStreamClosingAfterSetupEndsTheSession() {
        val s = session()
        val answer = s.phone.setupStreams(s.phone.stream(110, 5))
        s.phone.connectScreen(answer.single()["dataPort"]!!.asLong!!.toInt()).close()
        assertTrue(awaitTrue { events.ended.size == 1 })
    }

    @Test fun aControlConnectionThatEndsBeforeSetupIsReportedOnceAndEndsNothing() {
        val p = phone()
        p.info()
        p.close()
        assertTrue(awaitTrue { events.beforeSetup.size == 1 })
        Thread.sleep(200)
        assertEquals(1, events.beforeSetup.size)
        assertTrue(events.ended.isEmpty())
        val again = phone()
        assertNotNull(again.info()["name"])
    }

    @Test fun aNewControlConnectionReplacesTheOldOneWithoutEndingTheSession() {
        val first = phone()
        first.pair(identity)
        val second = phone()
        assertNotNull(second.info()["name"])
        assertTrue(first.isClosedByServer())
        assertTrue(events.ended.isEmpty())
        assertTrue("a replaced connection is not reported", events.beforeSetup.isEmpty())
        assertEquals(2, events.peers.size)
    }

    @Test fun closeEndsTheSessionQuietlyAndLeavesNoThreadOrSocket() {
        val s = session()
        val answer = s.phone.setupStreams(s.phone.stream(110, 5), s.phone.stream(100, 6, "media", 0x8000))
        s.phone.connectScreen(answer[0]["dataPort"]!!.asLong!!.toInt())
        assertTrue(threadsAlive().isNotEmpty())
        server.close()
        assertTrue(awaitTrue { threadsAlive().isEmpty() })
        assertTrue(events.ended.isEmpty())
        assertTrue(s.phone.isClosedByServer())
        server.close()
        assertTrue(events.ended.isEmpty())
    }

    @Test fun aHostThatIsNotOnTheNcmLinkIsClosedUnread() {
        val strict = newServer(acceptsPeer = { PeerGate.isNcmPeer(it, loopback) })
        val before = events.peers.size
        val p = FakeAirPlayPhone(loopback, port).also { toClose.add(it) }
        assertTrue(runCatching { p.info() }.isFailure)
        assertEquals(before, events.peers.size)
        strict.close()
    }

    @Test fun aServerCannotStartTwice() {
        assertTrue(runCatching { server.start() }.isFailure)
    }
}
