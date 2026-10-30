package com.andrerinas.openheadunit.connection.wifi.modes.helper

import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NearbyLifecycleLockTest {
    private fun field(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    @Test fun actualBandwidthCallbackAndAdmissionUseOneLockOrderAcrossStop() {
        for (stop in listOf(false, true)) {
            val comm = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            val lifecycle = get(comm, "transportLifecycleLock")!!
            val policy = get(comm, "appExitPolicy") as AppExitPolicy
            val nearby = mock<NearbyManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS)
            val guard = NearbyAttemptGuard()
            val attempt = guard.begin("phone", comm.appConnectionTicket())
            val socket = NearbySocket()
            val client = mock<ConnectionsClient>()
            val quality = ConcurrentHashMap<String, Int>()
            field(nearby, "commManager", comm); field(nearby, "attempts", guard)
            field(nearby, "connectionsClient", client); field(nearby, "lastQuality", quality)
            field(nearby, "activeEndpointId", "phone"); field(nearby, "activeNearbySocket", socket)
            doAnswer {
                assertFalse("SDK cleanup under lifecycle lock", Thread.holdsLock(lifecycle))
                assertFalse("SDK cleanup under attempt monitor", Thread.holdsLock(guard))
                null
            }.whenever(client).disconnectFromEndpoint(any())
            val callback = NearbyManager::class.java.getDeclaredMethod("connectionLifecycleCallback",
                NearbyAttemptGuard.Attempt::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(nearby, attempt, attempt.ticket) as ConnectionLifecycleCallback
            val bandwidth = mock<BandwidthInfo>()
            whenever(bandwidth.quality).thenReturn(BandwidthInfo.Quality.LOW)
            val admission = nearby.connectionAdmission(attempt)
            val lifecycleHeld = CountDownLatch(1)
            val enterAdmission = CountDownLatch(1)
            val errors = ConcurrentLinkedQueue<Throwable>()
            var admitted = false
            var publications = 0
            val handoff = Thread({
                try {
                    synchronized(lifecycle) {
                        lifecycleHeld.countDown()
                        check(enterAdmission.await(3, TimeUnit.SECONDS))
                        if (stop) policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
                        policy.supersede() // Compatible Open must not revoke a ticket by itself.
                        admitted = admission.run { publications++ }
                    }
                } catch (failure: Throwable) { errors.add(failure) }
            }, "Nearby-admission").apply { isDaemon = true }
            val callbackThread = Thread({
                try { callback.onBandwidthChanged("phone", bandwidth) }
                catch (failure: Throwable) { errors.add(failure) }
            }, "Nearby-bandwidth").apply { isDaemon = true }
            try {
                handoff.start()
                assertTrue(lifecycleHeld.await(3, TimeUnit.SECONDS))
                callbackThread.start()
                // Android's compile bootclasspath omits java.management; the test JVM supplies it.
                val bean = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null)
                val beanType = Class.forName("java.lang.management.ThreadMXBean")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var blockedOnLifecycle = false
                while (System.nanoTime() < deadline) {
                    if (callbackThread.state == Thread.State.BLOCKED &&
                        callbackThread.stackTrace.any { it.className == CommManager::class.java.name }) {
                        blockedOnLifecycle = true
                        break
                    }
                    if (!callbackThread.isAlive) break
                    Thread.yield()
                }
                assertTrue("actual callback did not reach the held lifecycle gate: $errors", blockedOnLifecycle)
                enterAdmission.countDown()
                handoff.join(3000); callbackThread.join(3000)
                val deadlocked = (beanType.getMethod("findDeadlockedThreads").invoke(bean) as LongArray?)
                    ?.toSet().orEmpty()
                assertFalse("callback/admission lock inversion: $deadlocked", handoff.id in deadlocked || callbackThread.id in deadlocked)
                assertFalse("admission did not complete", handoff.isAlive)
                assertFalse("callback did not complete", callbackThread.isAlive)
                assertTrue(errors.toString(), errors.isEmpty())
                assertEquals(!stop, admitted)
                assertEquals(if (stop) 0 else 1, publications)
                if (stop) {
                    assertTrue(socket.isClosed)
                    assertTrue(quality.isEmpty())
                    assertNull(guard.current())
                } else {
                    assertEquals(BandwidthInfo.Quality.LOW, quality["phone"])
                    assertSame(attempt, guard.current())
                }
            } finally {
                enterAdmission.countDown()
                socket.close()
            }
        }
    }
}
