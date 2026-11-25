package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.Executor

/** Every end of a host takes its frequency callback back out of the platform. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalOnlyHotspotHostWatchTest {

    class Info {
        @Suppress("unused")
        fun getFrequency(): Int = 5785
    }

    private val live = mutableListOf<Any>()
    private var executor: Executor? = null
    private var callback: WifiManager.LocalOnlyHotspotCallback? = null
    private var throwOnStart = false

    private val wifi = mock(WifiManager::class.java) { call ->
        when (call.method.name) {
            "registerLocalOnlyHotspotSoftApCallback" -> {
                executor = call.arguments[0] as Executor
                live += call.arguments[1]
                null
            }
            "unregisterLocalOnlyHotspotSoftApCallback" -> {
                live.removeAll { it === call.arguments[0] }
                null
            }
            "startLocalOnlyHotspot" -> {
                if (throwOnStart) throw IllegalStateException("already open")
                callback = call.arguments[0] as WifiManager.LocalOnlyHotspotCallback
                null
            }
            else -> Answers.RETURNS_DEFAULTS.answer(call)
        }
    }
    private val context = mock(Context::class.java) { call ->
        when (call.method.name) {
            "getApplicationContext" -> call.mock
            "getSystemService" -> wifi
            else -> Answers.RETURNS_DEFAULTS.answer(call)
        }
    }
    private val heard = mutableListOf<List<Int>>()
    private val host = LocalOnlyHotspotHost(context, LocalHotspotLedger())

    private fun start() = host.start({ null }, { heard += it }) { }

    /** Runs the platform's callback as the platform does, on the executor it was given. */
    private fun report(to: Any) = executor!!.execute {
        Class.forName("android.net.wifi.WifiManager\$SoftApCallback").getMethod("onInfoChanged", List::class.java)
            .invoke(to, listOf(Info()))
    }

    @Test
    fun `a frequency reaches the caller on the main thread`() {
        start()
        report(live.single())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(listOf(5785)), heard)
    }

    @Test
    fun `close takes the callback out, and one already on its way is dropped`() {
        start()
        report(live.single())
        host.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Any>(), live)
        assertEquals(emptyList<List<Int>>(), heard)
    }

    @Test
    fun `a call that throws takes the callback out`() {
        throwOnStart = true
        start()
        assertEquals(emptyList<Any>(), live)
    }

    @Test
    fun `a platform stop takes the callback out`() {
        start()
        callback!!.onStopped()
        assertEquals(emptyList<Any>(), live)
    }
}
