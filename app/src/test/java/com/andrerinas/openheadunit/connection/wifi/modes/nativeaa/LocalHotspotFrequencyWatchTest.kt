package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.app.Application
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.lang.reflect.Method
import java.util.concurrent.Executor

/** On the platform's own API 34 classes, so a wrong method name or a wrong callback type fails here. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalHotspotFrequencyWatchTest {

    class Info(private val mhz: Int) {
        @Suppress("unused")
        fun getFrequency(): Int = mhz
    }

    private var registered: Any? = null
    private var unregistered: Any? = null
    private var refuse = false

    private val wifi = mock(WifiManager::class.java) { call ->
        when (call.method.name) {
            "registerLocalOnlyHotspotSoftApCallback" -> {
                registered = call.arguments[1]
                if (refuse) throw SecurityException("no")
                null
            }
            "unregisterLocalOnlyHotspotSoftApCallback" -> {
                unregistered = call.arguments[0]
                null
            }
            else -> Answers.RETURNS_DEFAULTS.answer(call)
        }
    }
    private val heard = mutableListOf<List<Int>>()
    private val watch = LocalHotspotFrequencyWatch(wifi, Executor { it.run() })

    private fun onInfoChanged(parameter: Class<*>): Method =
        Class.forName("android.net.wifi.WifiManager\$SoftApCallback").getMethod("onInfoChanged", parameter)

    @Test
    fun `each entry's frequency is read and one without it is left out`() {
        assertEquals(listOf(5180, 2437), LocalHotspotFrequencyWatch.frequencies(listOf(Info(5180), "x", null, Info(2437))))
    }

    @Test
    fun `the list form of the platform callback gives the frequencies and the single form is not read twice`() {
        watch.start { heard += it }
        onInfoChanged(List::class.java).invoke(registered, listOf(Info(5180)))
        onInfoChanged(Class.forName("android.net.wifi.SoftApInfo")).invoke(registered, null)
        onInfoChanged(List::class.java).invoke(registered, emptyList<Any>())
        assertEquals(listOf(listOf(5180), emptyList()), heard)
    }

    @Test
    fun `the callback can be a map key`() {
        watch.start { }
        assertEquals(registered, registered)
        assertEquals(System.identityHashCode(registered), registered.hashCode())
    }

    @Test
    fun `stop unregisters the same callback once`() {
        watch.start { }
        watch.stop()
        assertEquals(registered, unregistered)
        unregistered = null
        watch.stop()
        assertNull(unregistered)
    }

    @Test
    fun `a refusal reads nothing and takes the callback back out once`() {
        refuse = true
        watch.start { heard += it }
        assertEquals(registered, unregistered)
        unregistered = null
        watch.stop()
        assertNull(unregistered)
        assertEquals(emptyList<List<Int>>(), heard)
    }
}
