package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.content.Context
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Endpoint-ID cleanup of a retired attempt must reach the SDK before a newer request for the same
 * endpoint. Nearby ends a pending request on disconnectFromEndpoint without onDisconnected, so a
 * late disconnect would leave the replacement connecting forever.
 */
class NearbyEndpointCleanupOrderTest {
    private fun field(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    /** Models the documented SDK contract: disconnecting a pending request ends it silently. */
    private class FakeNearby {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val client = mock<ConnectionsClient>()
        val pending = ConcurrentLinkedQueue<Pair<String, ConnectionLifecycleCallback>>()
        val requestFailures = ConcurrentLinkedQueue<OnFailureListener>()
        private val killed = Collections.newSetFromMap(ConcurrentHashMap<ConnectionLifecycleCallback, Boolean>())

        init {
            whenever(client.requestConnection(anyOrNull<String>(), any(), any<ConnectionLifecycleCallback>()))
                .thenAnswer {
                    check(!Thread.holdsLock(NearbyEndpointCalls)) { "request under endpoint queue monitor" }
                    val endpoint = it.getArgument<String>(1)
                    calls += "request:$endpoint"
                    pending += endpoint to it.getArgument<ConnectionLifecycleCallback>(2)
                    task { listener -> requestFailures += listener }
                }
            whenever(client.acceptConnection(any(), any())).thenAnswer {
                calls += "accept:${it.getArgument<String>(0)}"
                task {}
            }
            doAnswer {
                check(!Thread.holdsLock(NearbyEndpointCalls)) { "disconnect under endpoint queue monitor" }
                val endpoint = it.getArgument<String>(0)
                calls += "disconnect:$endpoint"
                pending.filter { (id, _) -> id == endpoint }.forEach { (_, callback) -> killed += callback }
                null
            }.whenever(client).disconnectFromEndpoint(any())
        }

        private fun task(onFailure: (OnFailureListener) -> Unit): Task<Void> {
            val task = mock<Task<Void>>()
            whenever(task.addOnFailureListener(any<OnFailureListener>())).thenAnswer {
                onFailure(it.getArgument(0)); task
            }
            return task
        }

        /** Delivers initiation and success only to requests the SDK still considers alive. */
        fun deliverLiveRequests() {
            while (true) {
                val (endpoint, callback) = pending.poll() ?: return
                if (callback in killed) continue
                val info = mock<ConnectionInfo>()
                whenever(info.endpointName).thenReturn("Phone")
                whenever(info.authenticationToken).thenReturn("token")
                callback.onConnectionInitiated(endpoint, info)
                val status = mock<Status>()
                whenever(status.statusCode).thenReturn(ConnectionsStatusCodes.STATUS_OK)
                val resolution = mock<ConnectionResolution>()
                whenever(resolution.status).thenReturn(status)
                callback.onConnectionResult(endpoint, resolution)
            }
        }
    }

    private class Harness(
        val comm: CommManager,
        val policy: AppExitPolicy,
        val nearby: NearbyManager,
        val guard: NearbyAttemptGuard,
        val sdk: FakeNearby,
        val scope: CoroutineScope,
    )

    /** Managers sharing [sdk] model a launcher replacement: Nearby is one service per process. */
    private fun harness(
        comm: CommManager = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest(),
        sdk: FakeNearby = FakeNearby(),
    ): Harness {
        val nearby = mock<NearbyManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS)
        val guard = NearbyAttemptGuard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        field(nearby, "commManager", comm); field(nearby, "attempts", guard)
        field(nearby, "connectionsClient", sdk.client); field(nearby, "context", mock<Context>())
        field(nearby, "scope", scope); field(nearby, "settings", mock<Settings>())
        field(nearby, "lastQuality", ConcurrentHashMap<String, Int>())
        return Harness(comm, get(comm, "appExitPolicy") as AppExitPolicy, nearby, guard, sdk, scope)
    }

    private fun Harness.stopThenOpen() {
        policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
        policy.supersede()
    }

    @Test fun `retired tunnel cleanup preempted after detach cannot cancel the next request for its endpoint`() {
        val h = harness()
        val oldTicket = h.comm.appConnectionTicket()
        val old = h.guard.begin("E", oldTicket)
        val oldSocket = NearbySocket()
        val detached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        // Cancelling a childless Job runs its handlers on the cancelling thread: this parks the old
        // attempt's IO exactly between detaching its state and its endpoint-ID cleanup.
        val oldTunnel = Job().apply {
            invokeOnCompletion { detached.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        }
        field(h.nearby, "activeEndpointId", "E"); field(h.nearby, "activeNearbySocket", oldSocket)
        field(h.nearby, "tunnelJob", oldTunnel)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val io = Thread({
            try {
                runBlocking { h.nearby.handOverTunnel(old, oldSocket, h.nearby.connectionAdmission(old), oldTicket) }
            } catch (failure: Throwable) { errors += failure }
        }, "Nearby-old-tunnel").apply { isDaemon = true }
        try {
            h.stopThenOpen()
            io.start()
            assertTrue("old tunnel never reached its cleanup", detached.await(3, TimeUnit.SECONDS))
            assertNull("old attempt was not detached before cleanup", h.guard.current())

            val fresh = h.comm.appConnectionTicket()
            h.nearby.connectToEndpoint("E", fresh)
            h.nearby.connectToEndpoint("E", fresh) // Same-epoch duplicate stays coalesced.
            val replacement = h.guard.current()
            assertNotNull("fresh request was not admitted", replacement)
            assertNotSame(old, replacement)

            resume.countDown()
            io.join(3000)
            assertFalse("old tunnel cleanup did not finish", io.isAlive)
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals("SDK call order", listOf("disconnect:E", "request:E"), h.sdk.calls.toList())

            h.sdk.deliverLiveRequests()
            assertEquals(listOf("disconnect:E", "request:E", "accept:E"), h.sdk.calls.toList())
            assertEquals("replacement stuck connecting", false, get(h.nearby, "isConnecting"))
            assertEquals("E", get(h.nearby, "activeEndpointId"))
            assertSame(replacement, h.guard.current())
            assertTrue(oldSocket.isClosed)
        } finally {
            resume.countDown(); oldSocket.close(); h.scope.cancel()
        }
    }

    @Test fun `replacement manager request is not cancelled by the previous manager's detached cleanup`() {
        val previous = harness()
        val replacementManager = harness(previous.comm, previous.sdk)
        val oldTicket = previous.comm.appConnectionTicket()
        val old = previous.guard.begin("E", oldTicket)
        val oldSocket = NearbySocket()
        val detached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val oldTunnel = Job().apply {
            invokeOnCompletion { detached.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        }
        field(previous.nearby, "activeEndpointId", "E"); field(previous.nearby, "activeNearbySocket", oldSocket)
        field(previous.nearby, "tunnelJob", oldTunnel)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val io = Thread({
            try {
                runBlocking {
                    previous.nearby.handOverTunnel(old, oldSocket, previous.nearby.connectionAdmission(old), oldTicket)
                }
            } catch (failure: Throwable) { errors += failure }
        }, "Nearby-previous-tunnel").apply { isDaemon = true }
        try {
            previous.stopThenOpen()
            io.start()
            assertTrue("old tunnel never reached its cleanup", detached.await(3, TimeUnit.SECONDS))
            assertNull(previous.guard.current())

            // The wireless scan command replaces the launcher: stop the old manager, then the new
            // manager's discovery finds the same endpoint.
            previous.nearby.stop()
            replacementManager.nearby.connectToEndpoint("E", replacementManager.comm.appConnectionTicket())
            val fresh = replacementManager.guard.current()
            assertNotNull("fresh request was not admitted", fresh)

            resume.countDown()
            io.join(3000)
            assertFalse("old tunnel cleanup did not finish", io.isAlive)
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals("SDK call order", listOf("disconnect:E", "request:E"), previous.sdk.calls.toList())

            previous.sdk.deliverLiveRequests()
            assertEquals(listOf("disconnect:E", "request:E", "accept:E"), previous.sdk.calls.toList())
            assertEquals("replacement stuck connecting", false, get(replacementManager.nearby, "isConnecting"))
            assertEquals("E", get(replacementManager.nearby, "activeEndpointId"))
            assertSame(fresh, replacementManager.guard.current())
            assertNull(get(previous.nearby, "activeEndpointId"))
        } finally {
            resume.countDown(); oldSocket.close(); previous.scope.cancel(); replacementManager.scope.cancel()
        }
    }

    @Test fun `ordinary request failure still disconnects its endpoint`() {
        val h = harness()
        try {
            h.nearby.connectToEndpoint("E", h.comm.appConnectionTicket())
            assertEquals(true, get(h.nearby, "isConnecting"))
            h.sdk.requestFailures.single().onFailure(Exception("rejected"))
            assertEquals(listOf("request:E", "disconnect:E"), h.sdk.calls.toList())
            assertNull(h.guard.current())
            assertEquals(false, get(h.nearby, "isConnecting"))
        } finally { h.scope.cancel() }
    }

    @Test fun `revoked tunnel without a replacement still disconnects its endpoint`() = runBlocking {
        val h = harness()
        val ticket = h.comm.appConnectionTicket()
        val attempt = h.guard.begin("E", ticket)
        val socket = NearbySocket()
        field(h.nearby, "activeEndpointId", "E"); field(h.nearby, "activeNearbySocket", socket)
        try {
            h.stopThenOpen()
            h.nearby.handOverTunnel(attempt, socket, h.nearby.connectionAdmission(attempt), ticket)
            assertEquals(listOf("disconnect:E"), h.sdk.calls.toList())
            assertTrue(socket.isClosed)
            assertNull(h.guard.current())
        } finally { socket.close(); h.scope.cancel() }
    }

    @Test fun `same-epoch duplicate request is suppressed`() {
        val h = harness()
        try {
            val ticket = h.comm.appConnectionTicket()
            h.nearby.connectToEndpoint("E", ticket)
            val attempt = h.guard.current()
            h.nearby.connectToEndpoint("E", ticket)
            assertEquals(listOf("request:E"), h.sdk.calls.toList())
            assertSame(attempt, h.guard.current())
        } finally { h.scope.cancel() }
    }
}
