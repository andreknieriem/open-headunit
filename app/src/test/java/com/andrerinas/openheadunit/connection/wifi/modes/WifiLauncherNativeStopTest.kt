package com.andrerinas.openheadunit.connection.wifi.modes

import android.app.Application
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherStopSequence
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.CloseReason
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApCredentialsProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Drives the real stop entries: a stop that skips the provider leaks the hotspot reservation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WifiLauncherNativeStopTest {

    private val manager = WifiLauncherManager(mock<AapService>())
    private val provider = mock<SoftApCredentialsProvider>()
    private val launcher = WifiLauncherNative(manager, NativeStrategy.HOTSPOT).also {
        set(WifiLauncherNative::class.java, "softApCredentialsProvider", it, provider)
        set(WifiLauncherManager::class.java, "active", manager, it)
    }

    private fun set(type: Class<*>, field: String, target: Any, value: Any) =
        type.getDeclaredField(field).apply { isAccessible = true }.set(target, value)

    @Test
    fun `a before-hotspot-disable stop stops the provider`() = stopsTheProvider(WifiLauncherStopSequence.BEFORE_HOTSPOT_DISABLE)

    @Test
    fun `a last stop stops the provider`() = stopsTheProvider(WifiLauncherStopSequence.LAST)

    @Test
    fun `an any stop stops the provider`() = stopsTheProvider(WifiLauncherStopSequence.ANY)

    private fun stopsTheProvider(seq: WifiLauncherStopSequence) {
        launcher.stop(seq)
        verify(provider).stop(any())
    }

    @Test
    fun `a last stop with no note is a session end`() {
        launcher.stop(WifiLauncherStopSequence.LAST)
        verify(provider).stop(CloseReason.SESSION_END)
    }

    @Test
    fun `a last stop after a user exit note is a user exit`() {
        launcher.noteUserExit()
        launcher.stop(WifiLauncherStopSequence.LAST)
        verify(provider).stop(CloseReason.USER_EXIT)
        verify(provider, never()).stop(CloseReason.SESSION_END)
    }

    @Test
    fun `an any stop with no note is a launcher replaced`() {
        launcher.stop(WifiLauncherStopSequence.ANY)
        verify(provider).stop(CloseReason.LAUNCHER_REPLACED)
    }

    @Test
    fun `the status pill Cancel closes the hotspot as a user exit`() {
        manager.stopForUser()
        verify(provider).stop(CloseReason.USER_EXIT)
    }
}
