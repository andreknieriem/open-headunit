package com.andrerinas.openheadunit.connection.wifi

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.os.Looper
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.HeldServerSocket
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Runs the real [NetworkDiscovery] behind the real [WifiLauncherSharedServices] listener. Main is
 * Robolectric's paused looper, so the test decides when each Main task runs; the IO side is the
 * real dispatcher, held at chosen points by the test's own doubles. Only the controlled loopback
 * address is ever probed, so a sweep never leaves the machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// No TLS here. Robolectric's Conscrypt provider is process-wide and breaks later plain-JVM TLS tests.
@ConscryptMode(ConscryptMode.Mode.OFF)
class NetworkDiscoveryGenerationOrderTest {
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var mainThread: Thread

    private val comm = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest()
    private val policy = CommManager::class.java.getDeclaredField("appExitPolicy")
        .apply { isAccessible = true }.get(comm) as AppExitPolicy
    private val app = mock<App>()
    private val service = mock<AapService>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shared by lazy { WifiLauncherSharedServices(service) }
    private val firstProbeGate = CountDownLatch(1)
    private val connected = Collections.synchronizedList(mutableListOf<Socket>())
    private val servers = mutableListOf<ServerSocket>()
    private val accepted = Collections.synchronizedList(mutableListOf<Socket>())
    private var rescanWithoutWaiting = false

    /** IO-side ticket checks, per ticket; the second one is the last check before the 5277 dial. */
    private val ioTicketChecks = ConcurrentHashMap<Long, AtomicInteger>()
    @Volatile private var holdBeforeDial: Long? = null
    private val atDial = CountDownLatch(1)
    private val releaseDial = CountDownLatch(1)

    private lateinit var ip: String

    @Before fun setUp() {
        mainThread = Thread.currentThread()
        AapService.scanningState.value = false
        val component = mock<AppComponent>()
        whenever(component.commManager).thenReturn(comm)
        // readProbeHelperPort() reads this first; holding it lets the test confine the sweep
        // before any address is probed. A null mode keeps the production 5289 fallback.
        whenever(component.settings).thenAnswer {
            assertTrue(firstProbeGate.await(5, TimeUnit.SECONDS))
            null
        }
        App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
            .set(app, lazyOf(component))
        // The shared services build the scanner on the service itself.
        whenever(service.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mock<ConnectivityManager>())
        whenever(service.applicationContext).thenReturn(app)
        whenever(service.serviceScope).thenReturn(serviceScope)
        doAnswer { rescanWithoutWaiting }.whenever(service).rescanWithoutWaiting
        doAnswer { rescanWithoutWaiting = it.arguments[0] as Boolean; null }
            .whenever(service).rescanWithoutWaiting = any()
        doReturn(false).whenever(comm).isBusy
        runBlocking {
            // CommManager's admission adopts the held socket as it admits the request.
            doAnswer { val socket = it.arguments[0] as Socket; if (HeldServerSocket.adopt(socket)) connected += socket; Unit }
                .whenever(comm).connectHeld(any<Socket>(), any(), any(), any())
        }
        doAnswer {
            val ticket = it.arguments[0] as Long
            val accepts = it.callRealMethod() as Boolean
            if (Thread.currentThread() !== mainThread &&
                ioTicketChecks.getOrPut(ticket) { AtomicInteger() }.incrementAndGet() == 2 &&
                ticket == holdBeforeDial
            ) {
                // Validated, now in the uninterruptible dial while Stop/Open overtakes it.
                atDial.countDown()
                assertTrue(releaseDial.await(10, TimeUnit.SECONDS))
            }
            accepts
        }.whenever(comm).acceptsAppConnectionTicket(any())
    }

    @After fun tearDown() {
        releaseDial.countDown()
        firstProbeGate.countDown()
        shared.localDiscovery?.stop()
        (shared.localDiscovery?.let { scanJob(it) })?.let { job ->
            runBlocking { kotlinx.coroutines.withTimeout(5_000) { job.join() } }
        }
        serviceScope.cancel()
        HeldServerSocket.discardAll()
        connected.forEach { HeldServerSocket.adopt(it); it.close() }
        servers.forEach { it.close() }
        accepted.forEach { it.close() }
        AapService.scanningState.value = false
    }

    /** A random 127/8 address, so concurrent runs on one host do not share the fixed ports. */
    private fun serve(vararg ports: Int) {
        while (true) {
            val candidate = "127.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}.${Random.nextInt(2, 255)}"
            val bound = mutableListOf<ServerSocket>()
            try {
                for (port in ports) bound += ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(candidate), port), 50)
                }
            } catch (e: BindException) {
                bound.forEach { it.close() }
                continue
            }
            for (server in bound) {
                servers += server
                Thread {
                    try { while (true) accepted += server.accept() } catch (_: Exception) {}
                }.apply { isDaemon = true }.start()
            }
            ip = candidate
            val raw = candidate.split('.').map { it.toInt() }
                .foldIndexed(0) { i, acc, b -> acc or (b shl (8 * i)) }
            val wifi = mock<WifiManager>()
            whenever(wifi.dhcpInfo).thenReturn(DhcpInfo().apply { gateway = raw })
            whenever(app.getSystemService(Context.WIFI_SERVICE)).thenReturn(wifi)
            return
        }
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun scanJob(discovery: NetworkDiscovery) = field(discovery, "scanJob").get(discovery) as Job?

    /** Starts discovery through the shared services and confines it to [ip] before it probes. */
    private fun startDiscovery(oneShot: Boolean, wrap: (NetworkDiscovery.Listener) -> NetworkDiscovery.Listener): NetworkDiscovery {
        shared.startLocalDiscovery(oneShot = oneShot, ticket = comm.appConnectionTicket())
        val discovery = requireNotNull(shared.localDiscovery)
        val reported = field(discovery, "reportedIps")
        @Suppress("UNCHECKED_CAST")
        val inner = reported.get(discovery) as MutableSet<String>
        reported.set(discovery, object : MutableSet<String> by inner {
            override fun contains(element: String) = element != ip || inner.contains(element)
        })
        val listener = field(discovery, "listener")
        listener.set(discovery, wrap(listener.get(discovery) as NetworkDiscovery.Listener))
        firstProbeGate.countDown()
        return discovery
    }

    private fun stopAndOpen() {
        policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
        policy.supersede()
    }

    private fun pumpUntil(timeoutMs: Long = 5_000, oneTask: Boolean = false, done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            if (done()) return true
            if (System.nanoTime() > deadline) return false
            if (looper.isIdle) Thread.sleep(2) else if (oneTask) looper.runOneTask() else looper.idle()
        }
    }

    private fun waitFor(timeoutMs: Long = 5_000, done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!done()) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(2)
        }
        return true
    }

    /** Occupies every IO worker, so a continuation dispatched to IO waits until [release]. */
    private class SaturatedIo {
        private val parallelism = System.getProperty("kotlinx.coroutines.io.parallelism")?.toInt()
            ?: maxOf(64, Runtime.getRuntime().availableProcessors())
        private val release = CountDownLatch(1)
        private val started = CountDownLatch(parallelism)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        init {
            repeat(parallelism) { scope.launch { started.countDown(); release.await() } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
        }
        fun release() { release.countDown(); scope.cancel() }
    }

    private fun continuousScanRearms(rearmBeforeIoCompletion: Boolean) {
        serve(5289)
        val finished = Collections.synchronizedList(mutableListOf<Long>())
        var saturated: SaturatedIo? = null
        shared.javaClass.getDeclaredField("wirelessServer").apply { isAccessible = true }
            .set(shared, mock<WirelessServer>())
        val discovery = startDiscovery(oneShot = false) { real ->
            object : NetworkDiscovery.Listener by real {
                override fun onScanFinished(wasOneShot: Boolean, ticket: Long) {
                    // The scan's IO return is held behind every IO worker while Main runs on.
                    if (finished.isEmpty()) saturated = SaturatedIo()
                    real.onScanFinished(wasOneShot, ticket)
                    finished += ticket
                }
            }
        }
        val g0 = requireNotNull(scanJob(discovery))
        // A network kick while that scan runs folds into it and asks for an immediate rearm.
        assertFalse(discovery.startScan())
        assertSame(g0, scanJob(discovery))
        rescanWithoutWaiting = true

        assertTrue(pumpUntil(oneTask = true) { finished.size == 1 })
        assertFalse(AapService.scanningState.value)
        assertFalse("the immediate rearm is queued on Main", looper.isIdle)
        assertFalse(g0.isCompleted)
        val io = requireNotNull(saturated)
        if (rearmBeforeIoCompletion) {
            looper.runOneTask()
            io.release()
            assertTrue(waitFor { g0.isCompleted })
        } else {
            io.release()
            assertTrue(waitFor { g0.isCompleted })
            looper.runOneTask()
        }
        val state = "scanJobCompleted=${g0.isCompleted} scanningState=${AapService.scanningState.value} " +
            "pendingMain=${!looper.isIdle} newScan=${scanJob(discovery) !== g0}"
        assertNotSame("rearm was absorbed by the scan that had already reported completion: $state", g0, scanJob(discovery))
        assertTrue(state, AapService.scanningState.value)
        // The new generation really scans and reports, instead of leaving the state stuck.
        assertTrue(pumpUntil { finished.size == 2 })
        assertFalse(AapService.scanningState.value)
        assertTrue(waitFor { requireNotNull(scanJob(discovery)).isCompleted })
    }

    @Test fun `immediate rearm queued before the completed scan's IO return starts a new scan`() =
        continuousScanRearms(rearmBeforeIoCompletion = true)

    @Test fun `immediate rearm after the completed scan's IO return starts a new scan`() =
        continuousScanRearms(rearmBeforeIoCompletion = false)

    private fun replacementKeepsFreshSocket(replacements: Int) {
        serve(5277)
        val endpoint = "$ip:5277"
        val delivered = Collections.synchronizedList(mutableListOf<Pair<Long, Socket?>>())
        holdBeforeDial = comm.appConnectionTicket()
        val discovery = startDiscovery(oneShot = true) { real ->
            object : NetworkDiscovery.Listener by real {
                override fun onServiceFound(ip: String, port: Int, socket: Socket?, ticket: Long) {
                    delivered += ticket to socket
                    real.onServiceFound(ip, port, socket, ticket)
                }
            }
        }
        val g0 = requireNotNull(scanJob(discovery))
        assertTrue(atDial.await(5, TimeUnit.SECONDS))
        val superseded = mutableListOf<Job>()
        repeat(replacements) {
            stopAndOpen()
            shared.startLocalDiscovery(oneShot = true, ticket = comm.appConnectionTicket())
            superseded += requireNotNull(scanJob(discovery))
        }
        val fresh = comm.appConnectionTicket()
        val current = superseded.removeAt(superseded.lastIndex)
        assertTrue(g0.isCancelled)
        // Intermediate generations end once cancelled; give the newest one the chance to run
        // ahead of the old dial, which is exactly what must not happen.
        assertTrue(waitFor { superseded.all { it.isCompleted } })
        waitFor(1_000) { HeldServerSocket.isHeld(endpoint) }
        releaseDial.countDown()

        pumpUntil { connected.size == 1 }
        assertTrue(waitFor { g0.isCompleted })
        assertTrue(pumpUntil { current.isCompleted })
        looper.idle()
        val freshSocket = delivered.singleOrNull { it.first == fresh }?.second
        val state = "connects=${connected.size} freshDelivered=${freshSocket != null} " +
            "freshClosed=${freshSocket?.isClosed} delivered=${delivered.map { it.first }}"
        assertEquals(state, 1, connected.size)
        assertSame(state, freshSocket, connected.single())
        assertFalse(state, connected.single().isClosed)
        assertEquals(state, listOf(fresh), delivered.map { it.first })
    }

    @Test fun `single replacement keeps the fresh socket and connects once`() = replacementKeepsFreshSocket(1)

    @Test fun `consecutive replacement keeps the fresh socket and connects once`() = replacementKeepsFreshSocket(2)

    @Test fun `Stop cancels the dialling scan without delivering its socket`() {
        serve(5277)
        val endpoint = "$ip:5277"
        val delivered = Collections.synchronizedList(mutableListOf<Long>())
        holdBeforeDial = comm.appConnectionTicket()
        val discovery = startDiscovery(oneShot = true) { real ->
            object : NetworkDiscovery.Listener by real {
                override fun onServiceFound(ip: String, port: Int, socket: Socket?, ticket: Long) {
                    delivered += ticket
                    real.onServiceFound(ip, port, socket, ticket)
                }
                override fun onScanFinished(wasOneShot: Boolean, ticket: Long) {
                    delivered += ticket
                    real.onScanFinished(wasOneShot, ticket)
                }
            }
        }
        val g0 = requireNotNull(scanJob(discovery))
        assertTrue(atDial.await(5, TimeUnit.SECONDS))
        policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
        discovery.stop()
        releaseDial.countDown()
        assertTrue(pumpUntil { g0.isCompleted })
        looper.idle()
        assertTrue(g0.isCancelled)
        assertTrue(delivered.isEmpty())
        assertTrue(connected.isEmpty())
        assertFalse(HeldServerSocket.isHeld(endpoint))
        assertTrue(waitFor { accepted.size == 1 })
    }
}
