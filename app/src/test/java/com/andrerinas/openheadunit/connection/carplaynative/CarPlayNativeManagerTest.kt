package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Phase
import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayTouchReport.Contact
import com.andrerinas.openheadunit.connection.carplaynative.airplay.FakeAirPlayPhone
import com.andrerinas.openheadunit.connection.carplaynative.airplay.H264Fixtures
import com.andrerinas.openheadunit.connection.carplaynative.airplay.LogCapture
import com.andrerinas.openheadunit.connection.carplaynative.airplay.awaitTrue
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneModeAnswer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The manager over a fake iPhone: usbmux, the iAP2 link and the 0x4301 are real, the tunnel and the NCM link are
 * fakes, and AirPlay runs on the loopback address. A skew clock moves the deadlines without a wait.
 */
class CarPlayNativeManagerTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var fx: NativeFixtures
    private lateinit var log: LogCapture
    private lateinit var manager: CarPlayNativeManager
    private val device = FakePhone()
    private val openers = ArrayList<AutoCloseable>()
    @Volatile private var enabled = true
    @Volatile private var aaLive = false

    private val product = CarPlayNativeProduct("Open Headunit", "OHU-1", "Open Headunit", "1.0", "1.0")

    // Threads alive before this test began, so a slow thread of another test is not counted here.
    private lateinit var baseline: Set<Thread>

    @Before fun setUp() {
        baseline = Thread.getAllStackTraces().keys.toSet()
        fx = NativeFixtures(folder.root)
        log = LogCapture()
        manager = CarPlayNativeManager(
            usb = fx.usb, tunnel = fx.tunnel, carkit = fx.carkit, storage = fx.storage, video = fx.video, audio = fx.audio, gate = fx.gate,
            inputs = { CarPlayNativeInputs(enabled, 34, true, fx.signer, aaLive) },
            product = product, displaySize = { 1280 to 720 }, densityDpi = { 160 },
            bindAddress = { fx.loopback }, clock = fx.clock::now, tickMs = 10, peerGate = { _, _ -> true },
        )
    }

    @After fun tearDown() {
        fx.tunnel.holdPrepared?.countDown()
        fx.tunnel.holdStop?.countDown()
        fx.carkit.holdFirst?.countDown()
        manager.stop("test over")
        openers.asReversed().forEach { runCatching { it.close() } }
        try {
            // Every path gives back exactly the flags and sinks it took.
            assertTrue("gate begins ${fx.gate.begins.size}, ends ${fx.gate.ends.get()}", awaitTrue { fx.gate.begins.size == fx.gate.ends.get() })
            assertEquals(0, fx.gate.unmatched.get())
            assertTrue("audio opened ${fx.audio.opened}, closed ${fx.audio.closed}", awaitTrue { fx.audio.opened == fx.audio.closed })
        } finally {
            log.close()
        }
    }

    private fun ourThreads(prefix: String) = Thread.getAllStackTraces().keys.filter { it.isAlive && it !in baseline && it.name.startsWith(prefix) }

    private fun awaitEnded(timeoutMs: Long = 8000) = assertTrue("phase ${manager.phase.value}", awaitTrue(timeoutMs) { manager.phase.value is Phase.Ended })

    private fun awaitPhase(expected: Phase, timeoutMs: Long = 8000) = assertTrue("phase ${manager.phase.value}, wanted $expected", awaitTrue(timeoutMs) { manager.phase.value == expected })

    /** Attaches, waits for the 0x4301 and returns it. */
    private fun startSession(): StartSeen {
        assertTrue(manager.onAttach(device))
        assertTrue("no 0x4301; log: ${log.lines.takeLast(15)}", awaitTrue(10_000) { fx.usb.phones.any { it.starts.isNotEmpty() } })
        val seen = fx.usb.phones.last().starts.poll(1, TimeUnit.SECONDS)!!
        fx.airPlayPort = seen.port
        return seen
    }

    private fun phoneConnects(port: Int): FakeAirPlayPhone = FakeAirPlayPhone(fx.loopback, port).also { openers += it }

    private val mac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)

    @Test fun aWholeSessionRunsFromAttachToUserExitAndClosesInOrder() {
        val start = startSession()
        assertEquals("fe80::2", start.address)
        assertEquals("02:11:22:33:44:55", start.deviceIdentifier)
        assertEquals(64, start.publicKey.length)
        assertTrue(start.port > 0)
        assertEquals(listOf("native /dev/bus/usb/001/005"), fx.gate.begins)
        assertEquals(1, fx.tunnel.starts.get())
        assertEquals(1, fx.video.hook?.let { 1 } ?: 0)
        assertEquals(1, fx.audio.opened)
        awaitPhase(Phase.StartSent)

        fx.usb.ethernets.single().deliver(ncmFrameFromPhone(mac, start.port))
        awaitPhase(Phase.NetworkUp)
        val phone = phoneConnects(start.port)
        assertTrue(awaitTrue { manager.phase.value == Phase.AirPlay })

        val identity = fx.storage.identity()
        phone.pair(identity)
        assertEquals(200, phone.authSetup().status)
        val ports = phone.setupSession(null)
        phone.connectEvents(ports.event)
        val streams = phone.setupStreams(phone.stream(110, 9))
        val screen = phone.connectScreen(streams.single()["dataPort"]!!.asLong!!.toInt())
        phone.sendScreenConfig(screen, H264Fixtures.avcc(H264Fixtures.sps(320, 240)))
        phone.sendScreenFrame(screen, 9, byteArrayOf(0, 0, 0, 2, 0x65, 1))
        awaitPhase(Phase.Streaming)
        assertEquals(320 to 240, manager.videoSize.value)
        assertTrue(manager.isLive)
        assertEquals(true, awaitTrue { fx.video.decoded.size == 2 })

        assertTrue(manager.sendTouch(listOf(Contact(0, 160.0, 120.0, true))))
        assertEquals("hidSendReport", phone.eventType(phone.nextEvent()))
        manager.onSurfaceReady(1280, 720)
        assertEquals("forceKeyFrame", phone.eventType(phone.nextEvent()))

        manager.stop(CarPlayNativeManager.USER_EXIT)
        assertFalse(manager.isLive)
        assertTrue(manager.userExited)
        awaitPhase(Phase.Ended(CarPlayNativeManager.USER_EXIT))
        assertEquals(
            listOf("gate begin", "tun closed (airplay closed)", "ncm closed", "usb pipe closed", "tunnel stopped", "gate end"),
            fx.order.toList(),
        )
        assertEquals(1, fx.gate.ends.get())
        assertEquals(1, fx.tunnel.stops.get())
        assertEquals(1, fx.audio.closed)
        assertEquals(null, fx.video.hook)
        assertEquals(1, log.count("link lost (user exit)"))
        assertEquals(1, log.count("session start on iPhone"))
        assertEquals(1, log.count("configuration 6 set; usbmux on 0x04/0x85"))
        assertEquals(1, log.count("carkit service open"))
        assertEquals(1, log.count("iAP2 identification accepted"))
        assertEquals(1, log.count("authentication succeeded"))
        assertEquals(1, log.count("start session sent (#1)"))
        assertEquals(1, log.count("USB network up"))
        assertEquals(1, log.count("paired"))
        assertTrue(fx.usb.phones.single().seen.containsAll(listOf(0x1D01, 0xAA01, 0xAA03, 0x6800, 0x4301)))
        assertTrue(ourThreads("carplay-iap2").isEmpty())
    }

    @Test fun aSecondSessionAfterAStopStartsClean() {
        val first = startSession()
        manager.stop("unplugged")
        assertFalse(manager.isLive)
        awaitEnded()
        manager.onDetach(device)
        fx.order.clear()
        fx.usb.phones.clear()
        val second = startSession()
        assertEquals(2, fx.gate.begins.size)
        assertEquals(2, fx.tunnel.starts.get())
        assertEquals(2, fx.usb.opens.get())
        awaitPhase(Phase.StartSent)
        assertEquals(null, manager.videoSize.value)
        assertTrue(first.port > 0 && second.port > 0)
        manager.stop("done")
        awaitEnded()
        assertEquals(2, fx.gate.ends.get())
        assertEquals("one end count per closed attempt", 2, manager.ends.value)
        assertEquals(1, log.count("link lost (done)"))
        assertEquals(1, log.count("link lost (unplugged)"))
    }

    @Test fun stopTwiceEndsOnceAndAStartWhileLiveWritesNothing() {
        startSession()
        assertTrue(manager.onAttach(device))
        assertEquals(1, fx.gate.begins.size)
        assertEquals(1, fx.usb.opens.get())
        manager.stop("first")
        manager.stop("second")
        awaitPhase(Phase.Ended("first"))
        assertEquals(1, fx.gate.ends.get())
        assertEquals(1, fx.tunnel.stops.get())
        assertEquals(1, log.count("link lost"))
    }

    @Test fun noAirPlayConnectionAfterTheNcmFrameSendsTheStartAgainOnceThenEnds() {
        val start = startSession()
        awaitPhase(Phase.StartSent)
        fx.usb.ethernets.single().deliver(ncmFrameFromPhone(mac, start.port))
        awaitPhase(Phase.NetworkUp)
        fx.clock.advance(CarPlayNativeSession.AIRPLAY_FIRST_MS + 100)
        assertTrue(awaitTrue { log.count("start session sent (#2)") == 1 })
        assertNotNull(fx.usb.phones.single().starts.poll(2, TimeUnit.SECONDS))
        assertEquals(null, fx.usb.phones.single().starts.poll(300, TimeUnit.MILLISECONDS))
        fx.clock.advance(CarPlayNativeSession.AIRPLAY_AFTER_RESEND_MS + 100)
        assertTrue(awaitTrue { manager.phase.value is Phase.Ended })
        assertEquals(1, log.count("link lost (no AirPlay connection)"))
        assertEquals(1, log.count("start session sent (#2)"))
        assertEquals(0, log.count("start session sent (#3)"))
        assertEquals(1, fx.gate.ends.get())
    }

    @Test fun noNcmFrameInThirtySecondsEndsWithTheNamedLine() {
        startSession()
        awaitPhase(Phase.StartSent)
        fx.clock.advance(CarPlayNativeSession.NCM_FIRST_FRAME_MS + 100)
        assertTrue(awaitTrue { manager.phase.value is Phase.Ended })
        assertEquals(1, log.count("link lost (no USB network from the iPhone)"))
    }

    @Test fun anUnplugEndsTheSessionWithUnplugged() {
        startSession()
        manager.onDetach(device)
        assertTrue(awaitTrue { manager.phase.value is Phase.Ended })
        assertEquals(1, log.count("link lost (unplugged)"))
        assertFalse(manager.userExited)
    }

    @Test fun theDetachOfAnotherDeviceChangesNothing() {
        startSession()
        manager.onDetach(FakePhone(id = "/dev/bus/usb/001/009"))
        Thread.sleep(200)
        assertTrue(manager.isLive)
        assertEquals(0, log.count("link lost"))
    }

    @Test fun aLateEventOfAnEndedSessionDoesNotEndTheNextOne() {
        // The first session's thread waits in the carkit step past its own close, then fails: a real event, delivered late.
        val release = CountDownLatch(1).also { fx.carkit.holdFirst = it }
        assertTrue(manager.onAttach(device))
        awaitPhase(Phase.Pairing)
        manager.stop("first")
        awaitPhase(Phase.Ended("first"))
        manager.onDetach(device)
        startSession()
        awaitPhase(Phase.StartSent)
        release.countDown()
        // Both session threads end: the second after its steps, the first after its late failure.
        assertTrue(awaitTrue { ourThreads("carplay-native-session").isEmpty() })
        // isLive drops at once on an end; the phase and the log line come only after the close.
        assertTrue(manager.isLive)
        Thread.sleep(2000)
        assertTrue(manager.isLive)
        assertEquals(Phase.StartSent, manager.phase.value)
        assertEquals(0, log.count("late failure of the first session"))
        assertEquals(1, log.count("link lost (first)"))
    }

    @Test fun theUserExitLatchHoldsUntilTheIphoneLeavesTheBus() {
        startSession()
        manager.stop(CarPlayNativeManager.USER_EXIT)
        assertTrue(manager.userExited)
        awaitEnded()
        assertTrue(manager.onAttach(device))
        assertEquals(1, fx.gate.begins.size)
        assertEquals(1, log.count("the user closed the session"))
        // A real unplug: later than the phone's own re-enumeration would come.
        fx.clock.advance(CarPlayNativeSession.LATCH_DETACH_AFTER_CLOSE_MS + 100)
        manager.onDetach(device)
        assertFalse(manager.userExited)
        assertTrue(manager.onAttach(device))
        assertTrue(awaitTrue(10_000) { fx.gate.begins.size == 2 })
    }

    @Test fun theIphonesReenumerationAfterTheUserExitDoesNotReopenTheSession() {
        startSession()
        manager.stop(CarPlayNativeManager.USER_EXIT)
        awaitEnded()
        // The phone answers our configuration reset: it leaves and comes back as a new instance at once.
        manager.onDetach(device)
        assertTrue(manager.userExited)
        val again = FakePhone(id = "/dev/bus/usb/001/006")
        assertTrue(manager.onAttach(again))
        assertEquals(1, log.count("the user closed the session"))
        assertEquals(1, fx.gate.begins.size)
        assertTrue(manager.userExited)
        // Unplugging the new instance clears the latch.
        fx.clock.advance(1_000)
        manager.onDetach(again)
        assertFalse(manager.userExited)
    }

    @Test fun aSettingTurnedOnMidSessionCountsAtTheNextAttach() {
        enabled = false
        assertFalse(manager.onAttach(device))
        assertEquals(0, fx.gate.begins.size)
        enabled = true
        assertTrue(manager.onAttach(device))
        assertTrue(awaitTrue(10_000) { fx.gate.begins.size == 1 })
    }

    @Test fun anAndroidAutoSessionRefusesTheAttachWithItsLineAndNoArbiterCall() {
        aaLive = true
        assertTrue(manager.onAttach(device))
        assertEquals(1, log.count("CarPlayNative: refused, an Android Auto session is live"))
        assertEquals(0, fx.gate.begins.size)
        assertEquals(0, fx.usb.opens.get())
    }

    @Test fun theDummyVpnRefusesBeforeTheArbiterAndTheTunnel() {
        fx.tunnel.dummyUp = true
        manager.onAttach(device)
        assertTrue(awaitTrue { manager.phase.value is Phase.Ended })
        assertEquals(1, log.count("CarPlayNative: refused, the dummy VPN holds the VPN slot"))
        assertEquals(0, fx.gate.begins.size)
        assertEquals(0, fx.gate.ends.get())
        assertEquals(0, fx.tunnel.starts.get())
        assertEquals(0, fx.usb.opens.get())
        assertEquals(0, log.count("link lost"))
    }

    @Test fun aMissingVpnConsentEndsWithItsLine() {
        fx.tunnel.prepared = false
        manager.onAttach(device)
        assertTrue(awaitTrue { manager.phase.value is Phase.Ended })
        assertEquals(1, log.count("CarPlayNative: VPN consent missing"))
        assertEquals(0, fx.gate.begins.size)
    }

    @Test fun theArbiterRefusingEndsTheAttemptAndNeverEndsAFlagItDidNotSet() {
        fx.gate.refuse = true
        manager.onAttach(device)
        assertTrue(awaitTrue { manager.phase.value is Phase.Ended })
        assertEquals(0, fx.gate.ends.get())
        assertEquals(0, fx.usb.opens.get())
        assertEquals(0, fx.audio.opened)
    }

    @Test fun anIphoneInTheDefaultModeIsAskedForCarPlayModeAndTheIgnoredSecondDialogEndsWithoutTheArbiter() {
        fx.usb.permission = false
        fx.usb.configurations = 4
        assertTrue(manager.onAttach(device))
        assertEquals(1, fx.usb.asked.get())
        fx.usb.permission = true
        manager.onPermissionResult(device, true)
        assertTrue(awaitTrue { fx.usb.modeRequests.get() == 1 })
        assertTrue(awaitTrue { manager.phase.value == Phase.AwaitReenumeration })
        // The iPhone comes back as a new instance in CarPlay mode, with no permission for it yet.
        val again = FakePhone(id = "/dev/bus/usb/001/006")
        fx.usb.permission = false
        fx.usb.configurations = 6
        manager.onDetach(device)
        assertEquals(Phase.AwaitReenumeration, manager.phase.value)
        manager.onAttach(again)
        awaitPhase(Phase.AwaitSecondPermission)
        assertEquals(2, fx.usb.asked.get())
        fx.clock.advance(CarPlayNativeSession.SECOND_PERMISSION_MS + 100)
        assertTrue(awaitTrue { manager.phase.value is Phase.Ended })
        assertEquals(1, log.count("CarPlayNative: permission for the CarPlay mode iPhone not given in 30 s; not started"))
        assertEquals(0, fx.gate.begins.size)
        assertEquals(0, fx.gate.ends.get())
        assertEquals(0, fx.usb.opens.get())
    }

    @Test fun aGrantForTheNewInstanceBeginsTheSession() {
        fx.usb.configurations = 4
        manager.onAttach(device)
        assertTrue(awaitTrue { manager.phase.value == Phase.AwaitReenumeration })
        val again = FakePhone(id = "/dev/bus/usb/001/006")
        fx.usb.configurations = 6
        fx.usb.permission = false
        manager.onAttach(again)
        awaitPhase(Phase.AwaitSecondPermission)
        fx.usb.permission = true
        manager.onPermissionResult(again, true)
        assertTrue(awaitTrue(10_000) { fx.gate.begins == listOf("native /dev/bus/usb/001/006") })
    }

    @Test fun aRefusedModeIsLoggedAndNothingBegins() {
        fx.usb.configurations = 4
        fx.usb.mode = IphoneModeAnswer.Refused("CarPlayNative: the iPhone refused wired CarPlay mode (mode 00000000)")
        manager.onAttach(device)
        assertTrue(awaitTrue { log.count("the iPhone refused wired CarPlay mode (mode 00000000)") == 1 })
        assertEquals(0, fx.gate.begins.size)
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test fun aDeniedPermissionIsLoggedAndTheSecondDenialEndsTheWait() {
        fx.usb.permission = false
        manager.onAttach(device)
        manager.onPermissionResult(device, false)
        assertEquals(1, log.count("USB permission denied"))
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test fun aDeviceThatIsNoIphoneIsLeftAlone() {
        val ipad = object : IphoneDevice by FakePhone() { override val productName = "iPad" }
        assertFalse(manager.onAttach(ipad))
        assertEquals(0, fx.usb.asked.get())
    }

    @Test fun theTunnelAddressMissingEndsTheSessionAndClosesWhatOpened() {
        val missing = CarPlayNativeManager(
            usb = fx.usb, tunnel = fx.tunnel, carkit = fx.carkit, storage = fx.storage, video = fx.video, audio = fx.audio, gate = fx.gate,
            inputs = { CarPlayNativeInputs(true, 34, true, fx.signer, false) },
            product = product, displaySize = { 1280 to 720 }, densityDpi = { 160 },
            bindAddress = { null }, clock = fx.clock::now, tickMs = 10, peerGate = { _, _ -> true },
        )
        missing.onAttach(device)
        assertTrue(awaitTrue(10_000) { missing.phase.value is Phase.Ended })
        assertEquals(1, log.count("link lost (the tunnel address is not on any interface)"))
        assertEquals(1, fx.gate.ends.get())
        assertEquals(1, fx.tunnel.stops.get())
        assertEquals(listOf("gate begin", "tun closed (airplay closed)", "ncm closed", "usb pipe closed", "tunnel stopped", "gate end"), fx.order.toList())
    }

    // An end at each step: the gate and the sinks are given back exactly as often as they were taken.

    private fun holdBeforeTheGate(): CountDownLatch {
        val release = CountDownLatch(1).also { fx.tunnel.holdPrepared = it }
        assertTrue(manager.onAttach(device))
        assertTrue("the session thread reached the consent check", awaitTrue { fx.tunnel.preparedCalls.get() == 1 })
        return release
    }

    private fun assertBalancedAfter(release: CountDownLatch) {
        awaitEnded()
        release.countDown()
        // The released thread runs to its end: a gate begun after the close would show here.
        assertTrue("the session thread ended", awaitTrue { ourThreads("carplay-native-session").isEmpty() })
        assertEquals(fx.gate.begins.size, fx.gate.ends.get())
        assertEquals(fx.audio.opened, fx.audio.closed)
        assertEquals(null, fx.video.hook)
        assertEquals(0, fx.gate.unmatched.get())
    }

    @Test fun aStopBetweenTheThreadStartAndTheGateBeginNeverBeginsTheGate() {
        val release = holdBeforeTheGate()
        manager.stop("stopped early")
        assertBalancedAfter(release)
        assertEquals(0, fx.gate.begins.size)
        assertEquals(0, fx.audio.opened)
    }

    @Test fun aDetachBetweenTheThreadStartAndTheGateBeginNeverBeginsTheGate() {
        val release = holdBeforeTheGate()
        manager.onDetach(device)
        assertBalancedAfter(release)
        assertEquals(0, fx.gate.begins.size)
    }

    @Test fun aUserExitAtEachPhaseGivesBackTheGateAndTheSinksOnce() {
        val phases = listOf("before the gate", "pairing", "start sent", "network up", "airplay")
        for ((index, phase) in phases.withIndex()) {
            fx.tunnel.holdPrepared = null
            fx.carkit.holdFirst = null
            var release = CountDownLatch(0)
            when (phase) {
                "before the gate" -> release = holdBeforeTheGate().also { fx.tunnel.preparedCalls.set(0) }
                "pairing" -> {
                    // The carkit hold applies to the first open of the fake only, so count this attempt as the first.
                    fx.carkit.opens.set(0)
                    release = CountDownLatch(1).also { fx.carkit.holdFirst = it }
                    assertTrue(manager.onAttach(device))
                    awaitPhase(Phase.Pairing)
                }
                else -> {
                    fx.carkit.opens.set(1)
                    val start = startSession()
                    awaitPhase(Phase.StartSent)
                    if (phase != "start sent") {
                        fx.usb.ethernets.last().deliver(ncmFrameFromPhone(mac, start.port))
                        awaitPhase(Phase.NetworkUp)
                    }
                    if (phase == "airplay") {
                        phoneConnects(start.port)
                        awaitPhase(Phase.AirPlay)
                    }
                }
            }
            manager.stop(CarPlayNativeManager.USER_EXIT)
            assertBalancedAfter(release)
            assertEquals("ends after $phase", fx.gate.begins.size, fx.gate.ends.get())
            fx.clock.advance(CarPlayNativeSession.LATCH_DETACH_AFTER_CLOSE_MS + 100)
            manager.onDetach(device)
            assertEquals(index + 1, log.count("attached (") )
        }
    }

    // One session at a time.

    @Test fun aReplugWhileTheOldCloseRunsWaitsForItAndTheNextSessionKeepsItsOwnTicker() {
        startSession()
        val hold = CountDownLatch(1).also { fx.tunnel.holdStop = it }
        val unplug = Thread { manager.onDetach(device) }.apply { start() }
        try {
            assertTrue("the close reached the tunnel step", awaitTrue { fx.tunnel.stops.get() == 1 })
            assertTrue(manager.onAttach(device))
            Thread.sleep(300)
            assertEquals("no second session while the first closes", 1, fx.gate.begins.size)
            assertEquals(1, fx.usb.opens.get())
            assertEquals(0, fx.gate.ends.get())
            assertEquals(1, log.count("attached while the last session closes"))
        } finally {
            hold.countDown()
        }
        unplug.join(5000)
        assertTrue(awaitTrue(10_000) { fx.gate.begins.size == 2 })
        awaitPhase(Phase.StartSent, 10_000)
        val order = fx.order.toList()
        assertTrue("the first flag ends before the second begins: $order", order.indexOf("gate end") < order.lastIndexOf("gate begin"))
        assertEquals(1, log.count("link lost (unplugged)"))
        // The second session's ticker runs: its own deadline ends it.
        fx.clock.advance(CarPlayNativeSession.NCM_FIRST_FRAME_MS + 100)
        awaitPhase(Phase.Ended("no USB network from the iPhone"))
        assertEquals(2, fx.gate.ends.get())
    }

    // The waits the reducer adds after auth and after the AirPlay connection.

    @Test fun aPhoneThatConnectsFailsPairVerifyAndClosesEndsTheSessionAfterFifteenSeconds() {
        val start = startSession()
        awaitPhase(Phase.StartSent)
        fx.usb.ethernets.single().deliver(ncmFrameFromPhone(mac, start.port))
        awaitPhase(Phase.NetworkUp)
        val phone = phoneConnects(start.port)
        awaitPhase(Phase.AirPlay)
        assertNotNull(phone.info()["name"])
        assertFalse(phone.pairVerifyUnknown(fx.storage.identity()))
        phone.close()
        assertTrue(awaitTrue { log.count("AirPlay connection ended before SETUP") == 1 })
        // isLive drops at once on an end, before the asynchronous close moves the phase.
        assertTrue(manager.isLive)
        assertEquals(Phase.AirPlay, manager.phase.value)
        fx.clock.advance(CarPlayNativeSession.AIRPLAY_TO_VIDEO_MS + 100)
        awaitEnded()
        assertEquals(1, log.count("link lost (no video from the iPhone in 15 s (1 AirPlay connection ended before SETUP))"))
        assertEquals(1, fx.gate.ends.get())
    }

    @Test fun anIphoneThatNeverSendsTheStartRequestAfterAuthEndsAfterThirtySeconds() {
        fx.usb.availabilities = 0
        assertTrue(manager.onAttach(device))
        assertTrue(awaitTrue(10_000) { log.count("authentication succeeded") == 1 })
        Thread.sleep(300)
        assertEquals(Phase.Authenticating, manager.phase.value)
        fx.clock.advance(CarPlayNativeSession.START_REQUEST_MS + 100)
        awaitEnded()
        assertEquals(1, log.count("link lost (no CarPlay start request from the iPhone in 30 s)"))
    }

    // The end counter, a user exit during a close, a tunnel held by another owner and a replay of a gone device.

    /** Starts a session, holds its close in the tunnel step, ends it and returns the hold. */
    private fun endWithTheCloseHeld(reason: String): CountDownLatch {
        startSession()
        val hold = CountDownLatch(1).also { fx.tunnel.holdStop = it }
        manager.stop(reason)
        assertTrue("the close reached the tunnel step", awaitTrue { fx.tunnel.stops.get() == 1 })
        return hold
    }

    @Test fun theEndCounterMovesOnceWhenTheCloseEndsNotBefore() {
        assertEquals(0, manager.ends.value)
        val hold = endWithTheCloseHeld("no video from the iPhone in 15 s")
        assertEquals("the close still runs", 0, manager.ends.value)
        hold.countDown()
        assertTrue(awaitTrue { manager.ends.value == 1 })
        awaitEnded()
        assertEquals(1, manager.ends.value)
    }

    @Test fun aUserExitDuringTheCloseSetsTheLatchAndDropsTheWaitingAttach() {
        val hold = endWithTheCloseHeld("no video from the iPhone in 15 s")
        assertTrue(manager.onAttach(device))
        assertEquals(1, log.count("attached while the last session closes"))
        manager.stop(CarPlayNativeManager.USER_EXIT)
        assertTrue(manager.userExited)
        hold.countDown()
        awaitEnded()
        assertTrue("the closer thread finished its work", awaitTrue(5000) { ourThreads("carplay-native-close").isEmpty() })
        assertEquals(1, fx.gate.begins.size)
        assertEquals(1, fx.usb.opens.get())
        assertEquals("no replay to refuse", 0, log.count("the user closed the session"))
    }

    @Test fun aWaitingAttachOfAnIphoneThatLeftDuringTheCloseIsDroppedWithOneLine() {
        val hold = endWithTheCloseHeld("unplugged early")
        assertTrue(manager.onAttach(device))
        fx.usb.present = false
        hold.countDown()
        assertTrue(awaitTrue { log.count("the waiting attach of iPhone is dropped; the iPhone left or lost its permission") == 1 })
        assertEquals(1, fx.gate.begins.size)
        assertEquals(1, fx.usb.opens.get())
    }

    @Test fun aWaitingAttachThatLostItsPermissionDuringTheCloseIsDropped() {
        val hold = endWithTheCloseHeld("unplugged early")
        assertTrue(manager.onAttach(device))
        fx.usb.permission = false
        hold.countDown()
        assertTrue(awaitTrue { log.count("the waiting attach of iPhone is dropped") == 1 })
        assertEquals(0, fx.usb.asked.get())
        assertEquals(1, fx.gate.begins.size)
    }

    @Test fun aTunnelThatAnotherOwnerHoldsRefusesTheSessionAndIsLeftAlone() {
        fx.tunnel.carPlayUp = true
        assertTrue(manager.onAttach(device))
        awaitEnded()
        assertEquals(1, log.count("CarPlayNative: refused, a CarPlay tunnel is already up (the tunnel check holds it)"))
        assertEquals(0, fx.gate.begins.size)
        assertEquals(0, fx.tunnel.starts.get())
        assertEquals(0, fx.tunnel.stops.get())
        assertEquals(0, fx.usb.opens.get())
    }

    @Test fun aConnectionThatReplacesTheStreamingOneAndClosesBeforeSetupEndsTheSessionAfterFifteenSeconds() {
        val start = startSession()
        awaitPhase(Phase.StartSent)
        fx.usb.ethernets.single().deliver(ncmFrameFromPhone(mac, start.port))
        awaitPhase(Phase.NetworkUp)
        val phone = phoneConnects(start.port)
        awaitPhase(Phase.AirPlay)
        phone.pair(fx.storage.identity())
        assertEquals(200, phone.authSetup().status)
        val ports = phone.setupSession(null)
        phone.connectEvents(ports.event)
        val streams = phone.setupStreams(phone.stream(110, 9))
        val screen = phone.connectScreen(streams.single()["dataPort"]!!.asLong!!.toInt())
        phone.sendScreenConfig(screen, H264Fixtures.avcc(H264Fixtures.sps(320, 240)))
        phone.sendScreenFrame(screen, 9, byteArrayOf(0, 0, 0, 2, 0x65, 1))
        awaitPhase(Phase.Streaming)

        val second = phoneConnects(start.port)
        awaitPhase(Phase.AirPlay)
        assertNotNull(second.info()["name"])
        second.close()
        assertTrue(awaitTrue { log.count("AirPlay connection ended before SETUP") == 1 })
        assertTrue(manager.isLive)
        fx.clock.advance(CarPlayNativeSession.AIRPLAY_TO_VIDEO_MS + 100)
        awaitEnded()
        assertEquals(1, log.count("link lost (no video from the iPhone in 15 s (1 AirPlay connection ended before SETUP))"))
    }
}
