package com.andrerinas.openheadunit.connection.self

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfLaunchRoutePolicyTest {

    @Test
    fun `17_4 and later take the head unit server route`() {
        for (v in listOf("17.4.0", "17.8.163804-release", "18.0", "17.10.1")) {
            assertEquals(v, SelfLaunchPath.HEADUNIT_SERVER, SelfLaunchRoutePolicy.pathFor(aaInstalled = true, aaVersionName = v))
        }
    }

    @Test
    fun `below 17_4 takes the legacy route`() {
        for (v in listOf("17.3.662864", "16.9.1")) {
            assertEquals(v, SelfLaunchPath.LEGACY, SelfLaunchRoutePolicy.pathFor(aaInstalled = true, aaVersionName = v))
        }
    }

    @Test
    fun `a version we cannot read keeps the route that has the VPN`() {
        for (v in listOf(null, "", "abc")) {
            assertEquals(v, SelfLaunchPath.LEGACY, SelfLaunchRoutePolicy.pathFor(aaInstalled = true, aaVersionName = v))
        }
    }

    @Test
    fun `no Android Auto gives no route whatever the version`() {
        for (v in listOf("18.0", "17.3.1", "", null)) {
            assertNull(v, SelfLaunchRoutePolicy.pathFor(aaInstalled = false, aaVersionName = v))
        }
    }

    @Test
    fun `no Android Auto never needs a VPN`() {
        assertFalse(SelfLaunchRoutePolicy.needsDummyVpn(null, offline = true, vpnAvailable = true))
    }

    @Test
    fun `only an offline legacy start with a VPN needs one`() {
        assertTrue(SelfLaunchRoutePolicy.needsDummyVpn(SelfLaunchPath.LEGACY, offline = true, vpnAvailable = true))
        assertFalse(SelfLaunchRoutePolicy.needsDummyVpn(SelfLaunchPath.HEADUNIT_SERVER, offline = true, vpnAvailable = true))
        assertFalse(SelfLaunchRoutePolicy.needsDummyVpn(SelfLaunchPath.LEGACY, offline = false, vpnAvailable = true))
        assertFalse(SelfLaunchRoutePolicy.needsDummyVpn(SelfLaunchPath.LEGACY, offline = true, vpnAvailable = false))
    }

    @Test
    fun `an online start never needs a VPN`() {
        for (path in SelfLaunchPath.entries) {
            assertFalse(SelfLaunchRoutePolicy.needsDummyVpn(path, offline = false, vpnAvailable = true))
        }
    }
}
