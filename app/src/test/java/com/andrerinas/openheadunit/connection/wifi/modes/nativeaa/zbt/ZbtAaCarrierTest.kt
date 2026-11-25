package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeHandoffPolicy
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException

/** Exercises the actual demultiplexer and carrier, including writes and channel replacement. */
class ZbtAaCarrierTest {
    private lateinit var clock: MockedStatic<SystemClock>
    private var logger: AppLog.Logger? = null
    private var wall = System.currentTimeMillis()
    private var elapsed = 100_000L
    private var running = true
    private var connected = false
    private var busy = false
    private var evidence = 0
    private var canServe = true
    private var admitted = true
    private val admission = com.andrerinas.openheadunit.connection.ConnectionAdmission { action ->
        if (!admitted) false else { action(); true }
    }
    private val logs = mutableListOf<String>()
    private val sinks = mutableListOf<ByteArrayOutputStream>()

    @Before fun setup() {
        clock = Mockito.mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { elapsed }
        ZbtDaemonReachability.forget()
        logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) { logs.add(msg) }
        }
    }

    @After fun cleanup() {
        clock.close()
        ZbtDaemonReachability.forget()
        logger?.let { AppLog.LOGGER = it }
    }

    private fun advance(ms: Long) { elapsed += ms; wall += ms }
    private data class Step(val bytes: ByteArray? = null, val before: () -> Unit = {})
    private fun frame(id: Int, body: ByteArray = byteArrayOf()) = ZbtFraming.encodeFrame(body, id)
    private fun peer(connected: Boolean = true, name: String = "AABBCCDDEE01") =
        byteArrayOf(0x10, 1, 0x18, if (connected) 1 else 0, 0x3a, 12) + name.toByteArray()
    private fun presence(connected: Boolean = true) = frame(ZbtMessages.LINK_INFO2, peer(connected))

    private fun open(
        steps: List<Step>, control: (Int, ByteArray) -> Unit, rfcomm: (ByteArray) -> Unit,
        eof: () -> Unit = { running = false }
    ): ZbtByteChannel {
        val queue = ArrayDeque(steps)
        val source = object : InputStream() {
            var data = byteArrayOf()
            var offset = 0
            override fun read(): Int = error("bulk reads only")
            override fun read(target: ByteArray, off: Int, len: Int): Int {
                if (offset == data.size) {
                    val step = queue.removeFirstOrNull() ?: run { eof(); return -1 }
                    step.before()
                    data = step.bytes ?: throw SocketTimeoutException()
                    offset = 0
                }
                val size = minOf(len, data.size - offset)
                data.copyInto(target, off, offset, offset + size)
                offset += size
                return size
            }
        }
        val sink = ByteArrayOutputStream().also { sinks.add(it) }
        return ZbtByteChannel(source, sink, control, rfcomm).also { it.sendRequestInit() }
    }

    private fun carrier(
        factory: ((Int, ByteArray) -> Unit, (ByteArray) -> Unit) -> ZbtByteChannel,
        serve: suspend (HandshakeLink) -> Unit = { running = false; it.close() }
    ) = ZbtAaCarrier(
        admission = admission,
        serve = serve, isRunning = { running }, isFinishedForSession = { false },
        isSessionConnected = { connected }, isSettling = { false },
        isHandshakeInFlight = { busy }, mayServeHandshake = { canServe },
        onPhoneEvidence = { evidence++ }, openChannel = { _, control, rfcomm -> factory(control, rfcomm) },
        now = { wall }, elapsedNow = { elapsed }
    )

    private fun ids(sink: ByteArrayOutputStream): List<Int> {
        val data = sink.toByteArray()
        var offset = 0
        return buildList {
            while (offset < data.size) {
                val header = data.copyOfRange(offset, offset + ZbtFraming.HEADER_SIZE)
                add(ZbtFraming.decodeMsgId(header))
                offset += ZbtFraming.HEADER_SIZE + ZbtFraming.decodeBodySize(header)
            }
        }
    }
    private fun wakeCount() = sinks.sumOf { ids(it).count { id -> id == ZbtMessages.REQUEST_RECONN } }

    @Test fun `withdrawn arm cannot claim or open the external module`() = runBlocking {
        admitted = false
        val carrier = carrier({ _, _ -> error("withdrawn arm opened a channel") })
        carrier.requestWake(true, admission = admission)
        carrier.run()
        assertTrue(sinks.isEmpty())
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
    }

    @Test fun `channel end cannot reopen under a withdrawn original arm`() = runBlocking {
        var opens = 0
        val carrier = carrier({ control, rfcomm ->
            opens++
            open(emptyList(), control, rfcomm, eof = { admitted = false })
        })
        carrier.run()
        assertEquals(1, opens)
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
    }

    @Test fun `queued wake is checked again after the daemon read returns`() = runBlocking {
        val carrier = carrier({ control, rfcomm ->
            open(listOf(Step(frame(ZbtMessages.INIT_INFO)) { admitted = false }), control, rfcomm)
        })
        carrier.requestWake(true, admission = admission)
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, sinks.size)
        assertEquals(listOf(ZbtMessages.REQUEST_INIT), ids(sinks.single()))
    }

    @Test fun `Save cancellation withdraws its queued wake while the carrier remains armed`() = runBlocking {
        var commandCurrent = true
        val command = com.andrerinas.openheadunit.connection.ConnectionAdmission { action ->
            if (!commandCurrent) false else { action(); true }
        }
        val carrier = carrier({ control, rfcomm ->
            open(listOf(Step(frame(ZbtMessages.INIT_INFO)) {
                advance(8_001)
                commandCurrent = false
            }), control, rfcomm)
        })
        carrier.requestWake(true, admission = command)
        carrier.run()
        assertEquals(0, wakeCount())
        assertTrue(admitted)
    }

    @Test fun `connected phone consumes startup wake and starts handshake without waiting`() = runBlocking {
        val startedAt = elapsed
        var served = false
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step(presence())
        ), control, rfcomm) }, { link ->
            served = true
            assertEquals(startedAt, elapsed)
            assertEquals("AA:BB:CC:DD:EE:01", link.peerAddress)
            running = false
            link.close()
        })
        carrier.requestWake(admission = admission)
        carrier.run()
        assertTrue(served)
        assertEquals(0, wakeCount())
    }

    @Test fun `disconnected report followed by connected report does not spend an early wake`() = runBlocking {
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence(false)), Step(presence()) { advance(1_000) }
        ), control, rfcomm) })
        carrier.requestWake(admission = admission)
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, evidence)
    }

    @Test fun `silent module gets one coalesced wake after the observation budget`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)),
            Step { advance(7_999); assertEquals(0, wakeCount()); carrier.requestWake(true, admission = admission) },
            Step { advance(1); assertEquals(0, wakeCount()) },
            Step { advance(20_000); assertEquals(1, wakeCount()) }
        ), control, rfcomm) })
        carrier.requestWake(admission = admission)
        carrier.requestWake(true, admission = admission)
        carrier.run()
        assertEquals(1, wakeCount())
    }

    @Test fun `HFP zero and a pairing scalar do not bypass the opening wait`() = runBlocking {
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.HFP_LINK_INFO, byteArrayOf(0x10, 0))),
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x10, 1, 0x18, 0, 0x20, 1)))
        ), control, rfcomm) }, { fail("no connected phone or blind deadline") })
        carrier.requestWake(admission = admission)
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `anonymous module retains the blind handshake fallback`() = runBlocking {
        var served = false
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step { advance(8_000) },
            Step { advance(2_001) }
        ), control, rfcomm) }, { link ->
            served = true
            assertNull(link.peerAddress)
            running = false
            link.close()
        })
        carrier.requestWake(admission = admission)
        carrier.run()
        assertTrue(served)
        assertEquals(1, wakeCount())
    }

    @Test fun `real phone bytes cancel a queued wake without losing handshake data`() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA, payload))
        ), control, rfcomm) }, { link ->
            assertEquals("phone bytes should be served immediately", 100_000L, elapsed)
            assertEquals(1, link.input.read())
            assertEquals(2, link.input.read())
            assertEquals(3, link.input.read())
            running = false
            link.close()
        })
        carrier.requestWake(admission = admission)
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `phone bytes also suppress later automatic and user wake requests`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1))),
            Step { advance(8_001); carrier.requestWake(admission = admission) },
            Step { advance(16_000); repeat(20) { carrier.requestWake(true, admission = admission) } }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, logs.count { "reconnect request ignored: phone data" in it })
    }

    @Test fun `explicit disconnect releases phone data wake suppression`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1))),
            Step(presence(false)) { advance(8_001) },
            Step { carrier.requestWake(true, admission = admission) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(1, wakeCount())
    }

    @Test fun `incomplete link report and HFP zero do not erase phone data evidence`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1))),
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x10, 1))),
            Step(frame(ZbtMessages.HFP_LINK_INFO, byteArrayOf(0x10, 0))),
            Step { advance(8_001); carrier.requestWake(true, admission = admission) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `new channel does not inherit phone data suppression`() = runBlocking {
        canServe = false
        var opens = 0
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm ->
            if (++opens == 1) {
                open(listOf(Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1)))),
                    control, rfcomm, eof = {})
            } else {
                open(listOf(Step { advance(8_001); carrier.requestWake(true, admission = admission) }), control, rfcomm)
            }
        })
        carrier.run()
        assertEquals(2, opens)
        assertEquals(1, wakeCount())
    }

    @Test fun `empty phone frame does not suppress a new wake`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA)),
            Step { advance(8_001); carrier.requestWake(true, admission = admission) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(1, wakeCount())
    }

    @Test fun `reopened channel ignores old peer callbacks and old queued wakes`() = runBlocking {
        var opens = 0
        var oldControl: ((Int, ByteArray) -> Unit)? = null
        var oldRfcomm: ((ByteArray) -> Unit)? = null
        lateinit var carrier: ZbtAaCarrier
        val peers = mutableListOf<String?>()
        carrier = carrier({ control, rfcomm ->
            opens++
            if (opens == 1) {
                oldControl = control
                oldRfcomm = rfcomm
                open(listOf(Step(presence())), control, rfcomm)
            } else {
                advance(5_001)
                oldControl!!(ZbtMessages.LINK_INFO2, peer(false))
                oldControl!!(ZbtMessages.LINK_INFO2, peer())
                oldRfcomm!!(byteArrayOf(9))
                open(listOf(Step(frame(ZbtMessages.INIT_INFO)), Step { advance(10_001) }), control, rfcomm)
            }
        }, { link ->
            peers.add(link.peerAddress)
            if (opens == 1) {
                // A real disconnect during setup can allow a user wake to queue. The old
                // socket's pending work must not transfer to the next phone/channel.
                oldControl!!(ZbtMessages.LINK_INFO2, peer(false))
                busy = true
                carrier.requestWake(true, admission = admission)
                busy = false
            } else running = false
            link.close()
        })
        carrier.run()
        assertEquals(listOf("AA:BB:CC:DD:EE:01", null), peers)
        assertEquals(1, evidence)
        assertEquals(0, wakeCount())
    }

    @Test fun `a new request after a real disconnect can still wake the phone`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(presence(false)) { advance(9_000); carrier.requestWake(true, admission = admission) },
            Step { carrier.requestWake(true, admission = admission) }
        ), control, rfcomm) })
        carrier.requestWake(admission = admission)
        carrier.run()
        assertEquals(1, wakeCount())
    }

    @Test fun `projection that becomes live during observation consumes the pending wake`() = runBlocking {
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step { advance(9_000); connected = true }
        ), control, rfcomm) }, { fail("projection is already live") })
        carrier.requestWake(admission = admission)
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `closing during observation drops queued work`() = runBlocking {
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step { advance(11_000); carrier.close() }
        ), control, rfcomm) }, { fail("closed carrier") })
        carrier.requestWake(admission = admission)
        carrier.run()
        carrier.requestWake(true, admission = admission)
        assertEquals(0, wakeCount())
    }

    @Test fun `repeated clicks cannot refresh an expired pending wake`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)),
            Step { busy = true; advance(59_999); carrier.requestWake(true, admission = admission) },
            Step { advance(1); busy = false }, Step { advance(1) }
        ), control, rfcomm) })
        carrier.requestWake(admission = admission)
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `incomplete legacy link report preserves a connected peer before its first bytes`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x10, 1))),
            Step { advance(8_001); carrier.requestWake(true, admission = admission) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, evidence)
    }

    @Test fun `missing or invalid is_connect in LinkInfo2 cannot create a reconnect opportunity`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(frame(ZbtMessages.LINK_INFO2, byteArrayOf(0x10, 1))),
            Step(frame(ZbtMessages.LINK_INFO2, byteArrayOf(0x10, 1, 0x18, 2))),
            Step { advance(8_001); carrier.requestWake(true, admission = admission) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, evidence)
    }

    @Test fun `legacy connected report without type preserves peer but explicit down still releases it`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x18, 1))),
            Step { advance(8_001); carrier.requestWake(true, admission = admission) },
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x18, 0))) { assertEquals(0, wakeCount()) },
            Step { carrier.requestWake(true, admission = admission) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(1, wakeCount())
        assertEquals(1, evidence)
    }

    @Test fun `LinkInfo2 missing type preserves peer while a valid non AA type keeps its behavior`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(frame(ZbtMessages.LINK_INFO2, byteArrayOf(0x18, 1))),
            Step { advance(8_001); carrier.requestWake(true, admission = admission) },
            Step(frame(ZbtMessages.LINK_INFO2, byteArrayOf(0x10, 3, 0x18, 1))) { assertEquals(0, wakeCount()) },
            Step { carrier.requestWake(true, admission = admission) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(1, wakeCount())
        assertEquals(1, evidence)
    }

    private fun openingCarrier(
        factory: (ZbtChannelOpening, (Int, ByteArray) -> Unit, (ByteArray) -> Unit) -> ZbtByteChannel
    ) = ZbtAaCarrier(
        admission = admission,
        serve = { fail("a retired opening must not reach the handshake") },
        isRunning = { running }, isFinishedForSession = { false },
        isSessionConnected = { connected }, isSettling = { false },
        isHandshakeInFlight = { busy }, mayServeHandshake = { canServe },
        onPhoneEvidence = { evidence++ }, openChannel = factory,
        now = { wall }, elapsedNow = { elapsed }
    )

    @Test fun `withdrawal while daemon connect returns prevents RequestInit`() = runBlocking {
        val sink = ByteArrayOutputStream()
        var closes = 0
        val carrier = openingCarrier { opening, control, rfcomm ->
            ZbtByteChannel.open(
                onControlFrame = control, onRfcommData = rfcomm, opening = opening,
                socketFactory = { object : java.net.Socket() {
                    override fun connect(endpoint: java.net.SocketAddress, timeout: Int) { admitted = false }
                    override fun setSoTimeout(timeout: Int) = Unit
                    override fun getInputStream(): InputStream = java.io.ByteArrayInputStream(byteArrayOf())
                    override fun getOutputStream(): java.io.OutputStream = sink
                    override fun close() { closes++ }
                } }
            )
        }
        carrier.run()
        assertEquals(0, sink.size())
        assertTrue(closes > 0)
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
    }

    @Test fun `close reaches a connecting socket before channel publication`() = runBlocking {
        val sink = ByteArrayOutputStream()
        var connects = 0
        var closes = 0
        lateinit var carrier: ZbtAaCarrier
        carrier = openingCarrier { opening, control, rfcomm ->
            ZbtByteChannel.open(
                onControlFrame = control, onRfcommData = rfcomm, opening = opening,
                socketFactory = { object : java.net.Socket() {
                    override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                        connects++
                        carrier.close()
                    }
                    override fun setSoTimeout(timeout: Int) = Unit
                    override fun getInputStream(): InputStream = java.io.ByteArrayInputStream(byteArrayOf())
                    override fun getOutputStream(): java.io.OutputStream = sink
                    override fun close() { closes++ }
                } }
            )
        }
        carrier.requestWake(admission = admission)
        carrier.run()
        assertEquals(1, connects)
        assertTrue(closes > 0)
        assertEquals(0, sink.size())
        assertFalse(ZbtDaemonReachability.carrierLive())
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
    }

    @Test fun `late returned channel is closed without serving a retired carrier`() = runBlocking {
        var closes = 0
        lateinit var carrier: ZbtAaCarrier
        carrier = openingCarrier { _, control, rfcomm ->
            val result = ZbtByteChannel(
                java.io.ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), control, rfcomm,
                onClose = java.io.Closeable { closes++ }
            )
            carrier.close()
            result
        }
        carrier.run()
        assertEquals(1, closes)
        assertFalse(ZbtDaemonReachability.carrierLive())
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
    }

    @Test fun `closing before run does not preempt an observer or open a socket`() = runBlocking {
        val observer = ZbtDaemonReachability.observerOpening { true }!!
        var closed = false
        observer.attach(java.io.Closeable { closed = true })
        try {
            val carrier = openingCarrier { _, _, _ -> error("retired carrier opened") }
            carrier.close()
            carrier.run()
            assertFalse(closed)
            assertFalse(ZbtDaemonReachability.carrierWantsClient())
        } finally {
            observer.close()
            ZbtDaemonReachability.releaseObserver(observer)
        }
    }

    @Test fun `close releases a refused opening claim without waiting for retry backoff`() = runBlocking {
        var opens = 0
        val carrier = openingCarrier { _, _, _ ->
            opens++
            throw java.io.IOException("refused")
        }
        val job = launch { carrier.run() }
        yield()
        assertEquals(1, opens)
        assertTrue(ZbtDaemonReachability.carrierWantsClient())
        carrier.close()
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
        withTimeout(1_000) { job.join() }
        assertEquals(1, opens)
    }

    // ---------------------------------------------------------------------------------------------
    // Recovery evidence across reopen. The manager clears its consecutive-failure count on
    // onPhoneEvidence and counts a WPP-silent attempt as one failure; that contract is wired here
    // to NativeHandoffPolicy's real limit, around the real carrier and byte channel.
    // ---------------------------------------------------------------------------------------------

    private class Limited(var failures: Int = 0, var served: Int = 0, var opens: Int = 0)

    /** [steps] gives each open's frames, by 1-based open number; the run ends after [opens] opens. */
    private fun limitedRun(
        opens: Int,
        state: Limited = Limited(),
        beforeOpen: (Int) -> Unit = {},
        steps: (Int) -> List<Step>
    ): Limited = runBlocking {
        val carrier = ZbtAaCarrier(
            admission = admission,
            // A phone that never answers WPP: every served attempt ends as one counted failure.
            serve = { link ->
                state.served++; state.failures++; link.close()
                if (state.opens >= opens) running = false
            },
            isRunning = { running }, isFinishedForSession = { false },
            isSessionConnected = { connected }, isSettling = { false },
            isHandshakeInFlight = { busy },
            mayServeHandshake = { NativeHandoffPolicy.shouldServeHandshake(state.failures) },
            onPhoneEvidence = { evidence++; state.failures = 0 },
            openChannel = { _, control, rfcomm ->
                state.opens++
                check(state.opens <= opens) { "test exceeded its reopen budget" }
                beforeOpen(state.opens)
                advance(ZbtAttemptPolicy.MIN_ATTEMPT_INTERVAL_MS + 1)
                open(steps(state.opens), control, rfcomm, eof = { if (state.opens >= opens) running = false })
            },
            now = { wall }, elapsedNow = { elapsed }
        )
        carrier.run()
        state
    }

    private val sameReport = listOf(Step(frame(ZbtMessages.INIT_INFO)), Step(presence()))

    @Test fun `same connected report on every reopen cannot lift the failure limit`() {
        val run = limitedRun(opens = 8) { sameReport }
        assertEquals(8, run.opens)
        assertEquals(NativeHandoffPolicy.MAX_CONSECUTIVE_HANDSHAKE_FAILURES, run.served)
        assertEquals("only the first report is an arrival", 1, evidence)
    }

    @Test fun `explicit down then up after the limit is a fresh chance`() {
        val run = limitedRun(opens = 7) { n ->
            if (n < 7) sameReport
            else listOf(Step(frame(ZbtMessages.INIT_INFO)), Step(presence(false)), Step(presence()))
        }
        assertEquals(NativeHandoffPolicy.MAX_CONSECUTIVE_HANDSHAKE_FAILURES + 1, run.served)
        assertEquals(2, evidence)
    }

    @Test fun `phone bytes on a reopened channel are a fresh chance`() {
        val run = limitedRun(opens = 7) { n ->
            if (n < 7) sameReport
            else sameReport + Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1)))
        }
        assertEquals(NativeHandoffPolicy.MAX_CONSECUTIVE_HANDSHAKE_FAILURES + 1, run.served)
        assertEquals(2, evidence)
    }

    @Test fun `a different phone address on a reopened channel is a fresh chance`() {
        val run = limitedRun(opens = 7) { n ->
            if (n < 7) sameReport
            else listOf(Step(frame(ZbtMessages.INIT_INFO)), Step(frame(ZbtMessages.LINK_INFO2, peer(name = "AABBCCDDEE02"))))
        }
        assertEquals(NativeHandoffPolicy.MAX_CONSECUTIVE_HANDSHAKE_FAILURES + 1, run.served)
        assertEquals(2, evidence)
    }

    @Test fun `manual retry still lifts the limit without new link evidence`() {
        val state = Limited()
        // The manager's manual poke clears the count directly; the carrier must not need an edge.
        val run = limitedRun(opens = 7, state = state, beforeOpen = { n -> if (n == 7) state.failures = 0 }) { sameReport }
        assertEquals(NativeHandoffPolicy.MAX_CONSECUTIVE_HANDSHAKE_FAILURES + 1, run.served)
        assertEquals(1, evidence)
    }

    @Test fun `a retired channel's down report cannot turn the next reopen into an arrival`() {
        var firstControl: ((Int, ByteArray) -> Unit)? = null
        var opens = 0
        val carrier = carrier({ control, rfcomm ->
            opens++
            advance(ZbtAttemptPolicy.MIN_ATTEMPT_INTERVAL_MS + 1)
            if (opens == 1) firstControl = control
            else firstControl!!(ZbtMessages.LINK_INFO2, peer(false))
            open(sameReport, control, rfcomm, eof = { if (opens >= 2) running = false })
        }, { link -> if (opens >= 2) running = false; link.close() })
        runBlocking { carrier.run() }
        assertEquals(2, opens)
        assertEquals(1, evidence)
    }
}
