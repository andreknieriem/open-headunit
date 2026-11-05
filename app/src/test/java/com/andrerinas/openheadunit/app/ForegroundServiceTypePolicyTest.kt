package com.andrerinas.openheadunit.app

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two rules: what the service starts with, and what it adds while capturing.
 *
 * The one that matters is the first. The microphone type is while-in-use, so a background start
 * that claims it is refused even with the permission granted, and this service starts from boot,
 * USB and Bluetooth receivers where a refusal means no session at all.
 */
class ForegroundServiceTypePolicyTest {

    private val u = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    // --- what the service starts with ---

    @Test
    fun `the start never claims the microphone type, whatever the permission and the setting say`() {
        assertEquals(base, ForegroundServiceTypePolicy.baseTypeMask(u))
    }

    @Test
    fun `before Q the start takes no types at all`() {
        assertEquals(0, ForegroundServiceTypePolicy.baseTypeMask(Build.VERSION_CODES.P))
    }

    // --- what it adds while capturing ---

    @Test
    fun `capture claims the microphone type with the permission and the setting`() {
        assertEquals(base or mic, ForegroundServiceTypePolicy.withMicrophone(u, true, true))
    }

    @Test
    fun `a denied permission never claims it`() {
        assertEquals(base, ForegroundServiceTypePolicy.withMicrophone(u, false, true))
    }

    @Test
    fun `a head unit that hands the microphone to the phone never claims it`() {
        assertEquals(base, ForegroundServiceTypePolicy.withMicrophone(u, true, false))
    }

    @Test
    fun `neither claims it`() {
        assertEquals(base, ForegroundServiceTypePolicy.withMicrophone(u, false, false))
    }

    @Test
    fun `before Q capture takes no types either`() {
        assertEquals(0, ForegroundServiceTypePolicy.withMicrophone(
            Build.VERSION_CODES.P, true, true))
    }

    // --- the relationship between the two ---

    @Test
    fun `capture only ever adds to the start mask, never removes from it`() {
        for (granted in listOf(true, false)) {
            for (enabled in listOf(true, false)) {
                val withMic = ForegroundServiceTypePolicy.withMicrophone(u, granted, enabled)
                assertEquals(
                    "withMicrophone($granted, $enabled) dropped a type the start had",
                    base, withMic and base)
            }
        }
    }

    // --- the mileage ---

    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test
    fun `counting the mileage with precise location adds the location type`() {
        assertEquals(base or location, ForegroundServiceTypePolicy.withLocation(base, u, true, true))
        assertEquals(base or mic or location, ForegroundServiceTypePolicy.withLocation(base or mic, u, true, true))
    }

    @Test
    fun `no permission or the feature off never claims it`() {
        assertEquals(base, ForegroundServiceTypePolicy.withLocation(base, u, false, true))
        assertEquals(base, ForegroundServiceTypePolicy.withLocation(base, u, true, false))
    }

    @Test
    fun `before Q there is no location type to claim`() {
        assertEquals(0, ForegroundServiceTypePolicy.withLocation(0, Build.VERSION_CODES.P, true, true))
    }
}
