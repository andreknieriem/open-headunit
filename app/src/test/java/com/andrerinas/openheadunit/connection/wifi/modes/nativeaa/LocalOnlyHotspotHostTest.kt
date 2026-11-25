package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.RestartCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalOnlyHotspotHostTest {

    private val ledger = LocalHotspotLedger<LocalOnlyHotspotHost>()
    private val wifi = mock(WifiManager::class.java)
    private val context = mock(Context::class.java).also {
        `when`(it.applicationContext).thenReturn(it)
        `when`(it.getSystemService(Context.WIFI_SERVICE)).thenReturn(wifi)
    }
    private val events = mutableListOf<LocalOnlyHotspotHost.Event>()
    private var callback: WifiManager.LocalOnlyHotspotCallback? = null

    private fun host() = LocalOnlyHotspotHost(context, ledger)

    private fun captureCallback() {
        doAnswer {
            callback = it.arguments[0] as WifiManager.LocalOnlyHotspotCallback
            null
        }.`when`(wifi).startLocalOnlyHotspot(any(), any())
    }

    private fun reservation() = mock(WifiManager.LocalOnlyHotspotReservation::class.java)

    @Test
    fun `a call that throws spends nothing and its close changes nothing`() {
        doThrow(IllegalStateException("already open")).`when`(wifi).startLocalOnlyHotspot(any(), any())
        val host = host()
        host.start({ null }) { events += it }
        assertTrue(events.single() is LocalOnlyHotspotHost.Event.Threw)
        host.close()
        assertEquals(emptyList<Long>(), ledger.recentStarts(SystemClock.elapsedRealtime()))
        assertNull(ledger.lastEnd)
        assertNotNull(ledger.lastRefusedAtMs)
        assertFalse(ledger.hasUnansweredStart())
    }

    @Test
    fun `an answer after close closes the reservation before the mark clears and records the pair`() {
        captureCallback()
        val host = host()
        host.start({ "wlan1" to "192.168.97.139" }) { events += it }
        host.close()
        assertTrue(ledger.hasUnansweredStart())
        val res = reservation()
        var markedWhenClosed = false
        doAnswer { markedWhenClosed = ledger.hasUnansweredStart(); null }.`when`(res).close()
        callback!!.onStarted(res)
        verify(res, times(1)).close()
        assertTrue(markedWhenClosed)
        assertFalse(ledger.hasUnansweredStart())
        assertEquals("wlan1" to "192.168.97.139", ledger.lastPair)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a normal start then close closes the reservation once`() {
        captureCallback()
        val host = host()
        host.start({ null }) { events += it }
        val res = reservation()
        callback!!.onStarted(res)
        assertTrue(events.single() is LocalOnlyHotspotHost.Event.Started)
        assertTrue(host.isLive)
        assertFalse(ledger.hasUnansweredStart())
        host.close()
        host.close()
        verify(res, times(1)).close()
        assertEquals(RestartCause.CLOSE, ledger.lastEnd)
    }

    @Test
    fun `a platform stop after a start is the end and a later close does not move it`() {
        captureCallback()
        val host = host()
        host.start({ null }) { events += it }
        val res = reservation()
        callback!!.onStarted(res)
        callback!!.onStopped()
        assertTrue(events.last() is LocalOnlyHotspotHost.Event.Stopped)
        assertEquals(RestartCause.PLATFORM_STOP, ledger.lastEnd)
        host.close()
        assertEquals(RestartCause.PLATFORM_STOP, ledger.lastEnd)
        verify(res, times(0)).close()
    }
}
