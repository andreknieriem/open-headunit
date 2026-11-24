package com.andrerinas.openheadunit.connection.wifi.direct

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.utils.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
// No TLS here. Robolectric's Conscrypt provider is process-wide and breaks later plain-JVM TLS tests.
@ConscryptMode(ConscryptMode.Mode.OFF)
class WifiDirectManagerChannelCloseTest {

    @After
    fun clearTracker() {
        P2pTeardownTracker.adoptKeptGroup()
        P2pTeardownTracker.latest = null
    }

    @Test
    fun `stop closes the channel once and a repeated stop does not close it again`() {
        val f = Fixture()
        f.owner.stop()
        f.owner.stop()
        verify(f.firstChannel, times(1)).close()
        val record = P2pTeardownTracker.latest
        assertNotNull(record)
        assertTrue(record!!.groupRemoved)
        assertTrue(record.channelClosed)
    }

    @Test
    fun `stop with a kept group leaves the channel open`() {
        val f = Fixture()
        f.owner.onConnectionInfoAvailable(groupFormedInfo())
        f.owner.stop(keepGroup = true)
        verify(f.firstChannel, never()).close()
        assertTrue(P2pTeardownTracker.latest!!.keptGroup)
        assertEquals(0, f.removals)
    }

    @Test
    fun `releasing a kept group removes it and then closes the channel`() {
        val f = Fixture()
        f.owner.onConnectionInfoAvailable(groupFormedInfo())
        f.owner.stop(keepGroup = true)
        P2pTeardownTracker.releaseKeptGroup()
        assertEquals(1, f.removals)
        verify(f.firstChannel, times(1)).close()
        assertTrue(!P2pTeardownTracker.latest!!.keptGroup)
    }

    @Test
    fun `adopting a kept group closes its channel without a removal`() {
        val f = Fixture()
        f.owner.onConnectionInfoAvailable(groupFormedInfo())
        f.owner.stop(keepGroup = true)
        P2pTeardownTracker.adoptKeptGroup()!!.invoke()
        verify(f.firstChannel, times(1)).close()
        assertEquals(0, f.removals)
        P2pTeardownTracker.releaseKeptGroup()
        assertEquals(0, f.removals)
    }

    @Test
    fun `an owned group is removed first and the channel closes after the answer`() {
        val f = Fixture()
        f.owner.onConnectionInfoAvailable(groupFormedInfo())
        f.owner.stop()
        assertEquals(1, f.removals)
        verify(f.firstChannel, times(1)).close()
    }

    @Test
    fun `refreshNativeCredentials opens a new channel after stop`() {
        val f = Fixture()
        f.owner.stop()
        assertEquals(1, f.initializes)
        f.owner.refreshNativeCredentials()
        assertEquals(2, f.initializes)
    }

    @Test
    @Config(sdk = [28, 30])
    fun `a group info answer after stop neither throws nor asks the framework`() {
        val f = Fixture()
        f.owner.onConnectionInfoAvailable(groupFormedInfo())
        val requestsBefore = f.groupInfoRequests
        f.owner.stop()
        val initializesBefore = f.initializes
        f.answerDeviceInfo()
        f.owner.onConnectionInfoAvailable(groupFormedInfo())
        f.answerDeviceInfo()
        assertEquals(requestsBefore, f.groupInfoRequests)
        assertEquals(initializesBefore, f.initializes)
    }

    private fun groupFormedInfo(): WifiP2pInfo = WifiP2pInfo().apply {
        groupFormed = true
        isGroupOwner = true
    }

    private class Fixture {
        var initializes = 0
        var removals = 0
        var groupInfoRequests = 0
        private val deviceInfoCallbacks = mutableListOf<Any>()
        val firstChannel: WifiP2pManager.Channel = mock(WifiP2pManager.Channel::class.java)
        private val platform = mock(WifiP2pManager::class.java, Answer { call ->
            when (call.method.name) {
                "initialize" -> {
                    initializes++
                    if (initializes == 1) firstChannel else mock(WifiP2pManager.Channel::class.java)
                }
                "removeGroup" -> {
                    removals++
                    (call.arguments[1] as WifiP2pManager.ActionListener).onSuccess()
                    null
                }
                "requestGroupInfo" -> {
                    groupInfoRequests++
                    null
                }
                "requestDeviceInfo" -> {
                    deviceInfoCallbacks.add(call.arguments[1])
                    null
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })
        val owner: WifiDirectManager

        init {
            val app = mock(App::class.java)
            val component = mock(AppComponent::class.java)
            val settings = mock(Settings::class.java)
            val context = mock(Context::class.java)
            `when`(context.applicationContext).thenReturn(app)
            val pm = mock(PackageManager::class.java)
            `when`(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)).thenReturn(true)
            `when`(context.packageManager).thenReturn(pm)
            `when`(context.getSystemService(Context.WIFI_P2P_SERVICE)).thenReturn(platform)
            val wifi = mock(WifiManager::class.java)
            `when`(wifi.isWifiEnabled).thenReturn(true)
            `when`(app.getSystemService(Context.WIFI_SERVICE)).thenReturn(wifi)
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                .set(app, lazyOf(component))
            `when`(component.settings).thenReturn(settings)
            owner = WifiDirectManager(context)
            WifiDirectManager::class.java.getDeclaredField("isReceiverRegistered").apply { isAccessible = true }
                .set(owner, true)
        }

        fun answerDeviceInfo() {
            val device = WifiP2pDevice().apply { deviceAddress = "aa:bb:cc:dd:ee:ff" }
            val pending = deviceInfoCallbacks.toList()
            deviceInfoCallbacks.clear()
            pending.forEach { (it as WifiP2pManager.DeviceInfoListener).onDeviceInfoAvailable(device) }
        }
    }
}
