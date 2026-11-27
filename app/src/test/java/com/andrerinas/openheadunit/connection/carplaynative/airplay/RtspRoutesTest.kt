package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.airplay.RtspRoutes.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** The table follows LIVI `stack/tests.rs` `plain_requests_before_pairing`. */
class RtspRoutesTest {

    @Test
    fun beforePairVerifyOnlyOptionsAndTheOpenPathsPass() {
        assertEquals(Route.Info, RtspRoutes.route("GET", "/info", false))
        assertEquals(Route.Options, RtspRoutes.route("OPTIONS", "*", false))
        assertEquals(Route.AuthSetup, RtspRoutes.route("POST", "/auth-setup", false))
        assertEquals(Route.PairSetup, RtspRoutes.route("POST", "/pair-setup", false))
        assertEquals(Route.PairVerify, RtspRoutes.route("POST", "/pair-verify", false))
    }

    @Test
    fun everyOtherRequestNeedsPairVerifyFirst() {
        for ((method, path) in listOf(
            "GET" to "/whatever", "SETUP" to "/s", "RECORD" to "/s", "POST" to "/command",
            "TEARDOWN" to "/s", "POST" to "/feedback",
        )) {
            assertEquals("$method $path", Route.NeedsPairVerify, RtspRoutes.route(method, path, false))
        }
        assertEquals(470, RtspRoutes.STATUS_NEEDS_PAIR_VERIFY)
    }

    @Test
    fun aLookalikePathAndAStreamMethodOnAnOpenPathAreRefused() {
        assertEquals(Route.NeedsPairVerify, RtspRoutes.route("GET", "/information", false))
        assertEquals(Route.NeedsPairVerify, RtspRoutes.route("GET", "/info2", false))
        for (method in listOf("SETUP", "RECORD", "TEARDOWN")) {
            assertEquals(method, Route.NeedsPairVerify, RtspRoutes.route(method, "/info", false))
        }
    }

    @Test
    fun afterPairVerifyEveryRouteOpens() {
        assertEquals(Route.Setup, RtspRoutes.route("SETUP", "/s", true))
        assertEquals(Route.Record, RtspRoutes.route("RECORD", "/s", true))
        assertEquals(Route.Teardown, RtspRoutes.route("TEARDOWN", "/s", true))
        assertEquals(Route.Command, RtspRoutes.route("POST", "/command", true))
        assertEquals(Route.Feedback, RtspRoutes.route("POST", "/feedback", true))
        assertEquals(Route.Other, RtspRoutes.route("GET", "/whatever", true))
        assertEquals(Route.Other, RtspRoutes.route("GET", "/command", true))
    }

    @Test
    fun pathsMatchByTheirEndAndWithoutCase() {
        assertEquals(Route.Info, RtspRoutes.route("GET", "/Info", false))
        assertEquals(Route.PairVerify, RtspRoutes.route("POST", "rtsp://host/x/PAIR-VERIFY", false))
    }
}
