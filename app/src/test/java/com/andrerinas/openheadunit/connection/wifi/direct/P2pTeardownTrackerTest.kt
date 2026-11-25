package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P2pTeardownTrackerTest {

    @After
    fun reset() {
        P2pTeardownTracker.adoptKeptGroup()
        P2pTeardownTracker.latest = null
    }

    @Test
    fun `release runs the closure once and clears it`() {
        var runs = 0
        P2pTeardownTracker.registerKeptGroup(release = { runs++ }, closeChannel = {})
        P2pTeardownTracker.releaseKeptGroup()
        P2pTeardownTracker.releaseKeptGroup()
        assertEquals(1, runs)
    }

    @Test
    fun `adopt clears the release and hands back the channel close once`() {
        var releases = 0
        var closes = 0
        P2pTeardownTracker.registerKeptGroup(release = { releases++ }, closeChannel = { closes++ })
        P2pTeardownTracker.adoptKeptGroup()?.invoke()
        assertNull(P2pTeardownTracker.adoptKeptGroup())
        P2pTeardownTracker.releaseKeptGroup()
        assertEquals(0, releases)
        assertEquals(1, closes)
    }

    @Test
    fun `release clears the channel close`() {
        var closes = 0
        P2pTeardownTracker.registerKeptGroup(release = {}, closeChannel = { closes++ })
        P2pTeardownTracker.releaseKeptGroup()
        assertNull(P2pTeardownTracker.adoptKeptGroup())
        assertEquals(0, closes)
    }

    @Test
    fun `with no closure both calls do nothing`() {
        P2pTeardownTracker.releaseKeptGroup()
        assertNull(P2pTeardownTracker.adoptKeptGroup())
        assertNull(P2pTeardownTracker.latest)
    }

    @Test
    fun `a newer registration replaces the older closure`() {
        var first = 0
        var second = 0
        P2pTeardownTracker.registerKeptGroup(release = { first++ }, closeChannel = {})
        P2pTeardownTracker.registerKeptGroup(release = { second++ }, closeChannel = {})
        P2pTeardownTracker.releaseKeptGroup()
        assertEquals(0, first)
        assertEquals(1, second)
    }
}
