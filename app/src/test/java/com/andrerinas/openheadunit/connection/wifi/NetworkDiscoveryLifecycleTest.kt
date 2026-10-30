package com.andrerinas.openheadunit.connection.wifi

import android.app.Application
import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

// App's static init reads SystemClock, which the plain JVM android.jar stub does not provide.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// No TLS here. Robolectric's Conscrypt provider is process-wide and breaks later plain-JVM TLS tests.
@ConscryptMode(ConscryptMode.Mode.OFF)
class NetworkDiscoveryLifecycleTest {
    private fun field(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private val probeLock get() = NetworkDiscovery::class.java.getDeclaredField("PROBE_LOCK")
        .apply { isAccessible = true }.get(null)
    private var inFlight: Job?
        get() = NetworkDiscovery::class.java.getDeclaredField("inFlightScan")
            .apply { isAccessible = true }.get(null) as Job?
        set(value) = NetworkDiscovery::class.java.getDeclaredField("inFlightScan")
            .apply { isAccessible = true }.set(null, value)

    private class QueuedIo : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }

    /** A real cancelling job whose socket cleanup cannot finish until the test releases it. */
    private class OutgoingScan(inFlight: Boolean) {
        val io = QueuedIo()
        private val scope = CoroutineScope(SupervisorJob() + io)
        val socket = Socket()
        val releaseCleanup = CompletableDeferred<Unit>()
        val joinEntered = CompletableDeferred<Unit>()
        private val owner = scope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    releaseCleanup.await()
                    socket.close()
                }
            }
        }
        val job = object : Job by owner {
            override suspend fun join() {
                joinEntered.complete(Unit)
                owner.join()
            }
        }
        init { if (inFlight) io.drain() }
        suspend fun close() {
            owner.cancel()
            releaseCleanup.complete(Unit)
            io.drain()
            withTimeout(5_000) { owner.join() }
            socket.close()
            scope.cancel()
        }
    }

    private inner class Fixture {
        val comm = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val policy = get(comm, "appExitPolicy") as AppExitPolicy
        val context = mock<Context>()
        init {
            val component = mock<AppComponent>()
            whenever(component.commManager).thenReturn(comm)
            val app = mock<App>()
            field(app, "component\$delegate", lazyOf(component))
            whenever(context.applicationContext).thenReturn(app)
            doAnswer {
                assertFalse("ticket validation must precede PROBE_LOCK", Thread.holdsLock(probeLock))
                it.callRealMethod()
            }.whenever(comm).acceptsAppConnectionTicket(any())
        }
        fun scanner() = NetworkDiscovery(context, mock())
        fun stopAndOpen() {
            policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
            policy.supersede()
        }
    }

    private fun install(scanner: NetworkDiscovery, job: Job, oneShot: Boolean, ticket: Long) {
        val generation = Class.forName("${NetworkDiscovery::class.java.name}\$Generation")
            .getDeclaredConstructor(Boolean::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!)
            .apply { isAccessible = true }.newInstance(oneShot, ticket)
        field(scanner, "scanJob", job)
        field(scanner, "currentGeneration", generation)
        @Suppress("UNCHECKED_CAST")
        (get(scanner, "reportedIps") as MutableSet<String>).add("outgoing scan")
    }

    @Test fun `fresh scan replaces queued or inflight stale generation and stale input cannot mutate it`() = runBlocking {
        for (alreadyInFlight in listOf(false, true)) {
            val fixture = Fixture()
            val scanner = fixture.scanner()
            val old = OutgoingScan(alreadyInFlight)
            val oldTicket = fixture.comm.appConnectionTicket()
            install(scanner, old.job, oneShot = true, ticket = oldTicket)
            synchronized(probeLock) { inFlight = old.job }
            var replacement: Job? = null
            try {
                fixture.stopAndOpen()
                val freshTicket = fixture.comm.appConnectionTicket()
                assertTrue(scanner.startScan(oneShot = true, ticket = freshTicket))
                val current = get(scanner, "scanJob") as Job
                replacement = current
                val generation = requireNotNull(get(scanner, "currentGeneration"))
                assertNotSame(old.job, current)
                assertTrue(old.job.isCancelled)
                assertEquals(freshTicket, get(generation, "ticket"))
                withTimeout(5_000) { old.joinEntered.await() }
                assertFalse(old.job.isCompleted)
                assertTrue(current.isActive)
                assertFalse(old.socket.isClosed)
                assertEquals(setOf("outgoing scan"), get(scanner, "reportedIps"))
                synchronized(probeLock) { assertSame(current, inFlight) }

                assertFalse(scanner.startScan(oneShot = false, ticket = oldTicket))
                assertSame(current, get(scanner, "scanJob"))
                assertSame(generation, get(scanner, "currentGeneration"))
                assertEquals(true, get(generation, "oneShot"))
                assertTrue(current.isActive)
            } finally {
                replacement?.cancelAndJoin()
                old.close()
                synchronized(probeLock) {
                    val retained = inFlight
                    if (retained === old.job) inFlight = null
                    if (replacement != null) assertNull(retained)
                }
            }
        }
    }

    @Test fun `compatible Open folds into same ticket and continuous request promotes without cancellation`() = runBlocking {
        val fixture = Fixture()
        val scanner = fixture.scanner()
        val old = OutgoingScan(inFlight = false)
        val ticket = fixture.comm.appConnectionTicket()
        install(scanner, old.job, oneShot = true, ticket = ticket)
        synchronized(probeLock) { inFlight = old.job }
        try {
            val generation = get(scanner, "currentGeneration")!!
            fixture.policy.supersede()
            assertEquals(ticket, fixture.comm.appConnectionTicket())
            assertFalse(scanner.startScan(oneShot = false, ticket = fixture.comm.appConnectionTicket()))
            assertSame(old.job, get(scanner, "scanJob"))
            assertSame(generation, get(scanner, "currentGeneration"))
            assertEquals(false, get(generation, "oneShot"))
            assertFalse(scanner.startScan(oneShot = true, ticket = ticket))
            assertEquals(false, get(generation, "oneShot"))
            assertTrue(old.job.isActive)
            assertFalse(old.job.isCancelled)
            assertFalse(old.joinEntered.isCompleted)
        } finally {
            old.close()
            synchronized(probeLock) { if (inFlight === old.job) inFlight = null }
        }
    }

    @Test fun `fresh scan joins replaced own job then different instance probe before clearing scan state`() = runBlocking {
        val fixture = Fixture()
        val scanner = fixture.scanner()
        val otherScanner = fixture.scanner()
        val previous = OutgoingScan(inFlight = true)
        val priorProbe = OutgoingScan(inFlight = true)
        val ticket = fixture.comm.appConnectionTicket()
        install(scanner, previous.job, oneShot = true, ticket = ticket)
        install(otherScanner, priorProbe.job, oneShot = true, ticket = ticket)
        synchronized(probeLock) { inFlight = priorProbe.job }
        var replacement: Job? = null
        try {
            fixture.stopAndOpen()
            assertTrue(scanner.startScan(oneShot = true, ticket = fixture.comm.appConnectionTicket()))
            val current = get(scanner, "scanJob") as Job
            replacement = current
            withTimeout(5_000) { previous.joinEntered.await() }
            assertTrue(previous.job.isCancelled)
            assertFalse(priorProbe.job.isCancelled)
            assertFalse(priorProbe.joinEntered.isCompleted)
            previous.io.drain()
            assertFalse(previous.socket.isClosed)
            assertTrue(current.isActive)
            previous.releaseCleanup.complete(Unit)
            previous.io.drain()
            withTimeout(5_000) { priorProbe.joinEntered.await() }
            assertTrue(previous.socket.isClosed)
            assertFalse(priorProbe.socket.isClosed)
            assertEquals(setOf("outgoing scan"), get(scanner, "reportedIps"))
            otherScanner.stop()
            priorProbe.io.drain()
            assertTrue(priorProbe.job.isCancelled)
            assertFalse(priorProbe.job.isCompleted)
            assertTrue(current.isActive)
            synchronized(probeLock) { assertSame(current, inFlight) }
        } finally {
            replacement?.cancelAndJoin()
            previous.close()
            priorProbe.close()
            synchronized(probeLock) {
                val retained = inFlight
                if (retained === priorProbe.job) inFlight = null
                if (replacement != null) assertNull(retained)
            }
        }
    }
}
