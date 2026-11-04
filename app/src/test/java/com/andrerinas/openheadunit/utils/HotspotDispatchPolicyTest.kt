package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.HotspotDispatchPolicy.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotDispatchPolicyTest {

    @Test
    fun `Android 11 and above with WRITE_SETTINGS tries TetheringManager then ConnectivityManager then LegacyWifiManager`() {
        val methods = HotspotDispatchPolicy.candidateMethods(sdkInt = 30, hasWriteSettings = true)
        assertEquals(
            listOf(Method.TETHERING_MANAGER, Method.CONNECTIVITY_MANAGER, Method.LEGACY_WIFI_MANAGER),
            methods
        )
    }

    @Test
    fun `Android 11 and above without WRITE_SETTINGS can only try official TetheringManager`() {
        val methods = HotspotDispatchPolicy.candidateMethods(sdkInt = 30, hasWriteSettings = false)
        assertEquals(
            listOf(Method.TETHERING_MANAGER),
            methods
        )
    }

    @Test
    fun `Android 10 and below with WRITE_SETTINGS falls back through ConnectivityManager and LegacyWifiManager`() {
        val methods = HotspotDispatchPolicy.candidateMethods(sdkInt = 29, hasWriteSettings = true)
        assertEquals(
            listOf(Method.CONNECTIVITY_MANAGER, Method.LEGACY_WIFI_MANAGER),
            methods
        )
    }

    @Test
    fun `Android 10 and below without WRITE_SETTINGS has no candidate start methods`() {
        val methods = HotspotDispatchPolicy.candidateMethods(sdkInt = 29, hasWriteSettings = false)
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `shouldStopAfterAttempt stops fallback chain when an attempt succeeds to prevent duplicate calls`() {
        assertTrue(
            "Successful dispatch should immediately stop the fallback chain to prevent duplicate startTethering calls",
            HotspotDispatchPolicy.shouldStopAfterAttempt(attemptSucceeded = true)
        )
        assertFalse(
            "Failed dispatch should allow the fallback chain to continue to the next method",
            HotspotDispatchPolicy.shouldStopAfterAttempt(attemptSucceeded = false)
        )
    }
}
