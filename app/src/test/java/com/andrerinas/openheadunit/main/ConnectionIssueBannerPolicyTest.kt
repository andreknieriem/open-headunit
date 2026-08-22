package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.aap.NativeTransport
import com.andrerinas.openheadunit.aap.SoftApBssidPolicy
import com.andrerinas.openheadunit.aap.SoftApCredentials
import com.andrerinas.openheadunit.aap.SoftApCredentialsAttempt
import com.andrerinas.openheadunit.aap.SoftApCredentialsPolicy
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.StandingIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who sees the failure banner, and which one.
 *
 * Each rule here is a way of being wrong in front of a user: a warning beside a working session,
 * a warning that outlives its own remedy, or one that can never be got rid of. The suppression
 * cases are as load-bearing as the showing ones.
 */
class ConnectionIssueBannerPolicyTest {

    private fun bannerFor(
        standing: List<StandingIssue> = emptyList(),
        dismissedAtEpochMs: Long = 0L,
        sessionConnected: Boolean = false,
        onboardingComplete: Boolean = true,
        relevant: Set<ConnectionIssue> = ConnectionIssue.values().toSet(),
        remedyApplied: Set<ConnectionIssue> = emptySet()
    ) = ConnectionIssueBannerPolicy.bannerFor(
        standing, dismissedAtEpochMs, sessionConnected, onboardingComplete, relevant, remedyApplied
    )

    private fun standing(issue: ConnectionIssue, at: Long) = StandingIssue(issue, at)

    @Test
    fun `nothing standing shows nothing`() {
        assertNull(bannerFor())
    }

    @Test
    fun `a standing issue shows`() {
        assertEquals(
            ConnectionIssue.BSSID_UNAVAILABLE,
            bannerFor(standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)))
        )
    }

    @Test
    fun `the most recently raised issue wins`() {
        val result = bannerFor(
            standing = listOf(
                standing(ConnectionIssue.BSSID_UNAVAILABLE, 1_000L),
                standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 9_000L),
                standing(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 5_000L)
            )
        )
        assertEquals(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, result)
    }

    @Test
    fun `a dismissal after the raise hides it`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)),
                dismissedAtEpochMs = 2_000L
            )
        )
    }

    @Test
    fun `a raise after the dismissal shows it again`() {
        assertEquals(
            ConnectionIssue.BSSID_UNAVAILABLE,
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 3_000L)),
                dismissedAtEpochMs = 2_000L
            )
        )
    }

    @Test
    fun `dismissing at the same instant still hides it`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 2_000L)),
                dismissedAtEpochMs = 2_000L
            )
        )
    }

    @Test
    fun `a live session suppresses it`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L)),
                sessionConnected = true
            )
        )
    }

    @Test
    fun `unfinished onboarding suppresses it`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L)),
                onboardingComplete = false
            )
        )
    }

    @Test
    fun `a zero stamp is not standing even if it is in the list`() {
        assertNull(bannerFor(standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 0L))))
    }

    @Test
    fun `a zero stamp never outranks a real one`() {
        assertEquals(
            ConnectionIssue.BLUETOOTH_SENT_NO_DATA,
            bannerFor(
                standing = listOf(
                    standing(ConnectionIssue.BSSID_UNAVAILABLE, 0L),
                    standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L)
                )
            )
        )
    }

    @Test
    fun `every issue can be shown`() {
        // A guard rather than a formality: a new enum value that no branch shows would be a
        // condition recorded on the connection path and then never told to anyone.
        for (issue in ConnectionIssue.values()) {
            assertNotNull(issue.name, bannerFor(standing = listOf(standing(issue, 1_000L))))
        }
    }

    // Which conditions the selected route can be blocked by.

    @Test
    fun `no condition is relevant outside Native AA`() {
        for (transport in NativeTransport.values()) {
            assertTrue(
                transport.name,
                ConnectionIssueBannerPolicy.relevantNow(mode = 2, transport = transport).isEmpty()
            )
        }
    }

    @Test
    fun `WiFi Direct can be blocked by a masked BSSID but not by the hotspot configuration`() {
        val relevant = ConnectionIssueBannerPolicy.relevantNow(3, NativeTransport.WIFI_DIRECT)
        assertTrue(ConnectionIssue.BSSID_UNAVAILABLE in relevant)
        assertTrue(ConnectionIssue.BLUETOOTH_SENT_NO_DATA in relevant)
        assertFalse(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE in relevant)
    }

    @Test
    fun `the hotspot transport is the other way round`() {
        // It survives an unusable BSSID by sending an empty one, so it never raises that
        // condition; it is the only route that resolves credentials from our own access point.
        val relevant = ConnectionIssueBannerPolicy.relevantNow(3, NativeTransport.HOTSPOT)
        assertTrue(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE in relevant)
        assertTrue(ConnectionIssue.BLUETOOTH_SENT_NO_DATA in relevant)
        assertFalse(ConnectionIssue.BSSID_UNAVAILABLE in relevant)
    }

    @Test
    fun `every issue is relevant on some route`() {
        // The same guard as `every issue can be shown`: a condition no route claims would be
        // recorded on the connection path and then never shown to anybody.
        val everRelevant = NativeTransport.values()
            .flatMap { ConnectionIssueBannerPolicy.relevantNow(3, it) }
            .toSet()
        for (issue in ConnectionIssue.values()) assertTrue(issue.name, issue in everRelevant)
    }

    // Which conditions the user has already fixed.

    @Test
    fun `nothing set is no remedy`() {
        assertTrue(ConnectionIssueBannerPolicy.remedyApplied("", "", null).isEmpty())
    }

    @Test
    fun `the hotspot name alone is not the remedy`() {
        // With a manual name set the device's own configuration is never read, so a blank
        // password is sent as an open network rather than falling back to the real one.
        assertFalse(
            ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE in
                ConnectionIssueBannerPolicy.remedyApplied("OHU-TEST", "", null)
        )
        assertFalse(
            ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE in
                ConnectionIssueBannerPolicy.remedyApplied("", "testtest1234", null)
        )
    }

    @Test
    fun `the hotspot name and password together are the remedy`() {
        assertTrue(
            ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE in
                ConnectionIssueBannerPolicy.remedyApplied("OHU-TEST", "testtest1234", null)
        )
    }

    @Test
    fun `a usable static BSSID is the remedy, a placeholder is not`() {
        assertTrue(
            ConnectionIssue.BSSID_UNAVAILABLE in
                ConnectionIssueBannerPolicy.remedyApplied("", "", "AA:BB:CC:DD:EE:FF")
        )
        // "0" is what the setting stores when unset, and the masked address is what Android hands
        // back to an unprivileged app - neither is something the user fixed.
        for (notAnAddress in listOf("0", "02:00:00:00:00:00", "")) {
            assertFalse(
                notAnAddress,
                ConnectionIssue.BSSID_UNAVAILABLE in
                    ConnectionIssueBannerPolicy.remedyApplied("", "", notAnAddress)
            )
        }
    }

    // What the two filters do to the banner.

    @Test
    fun `a condition the selected route cannot produce is not shown`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 1_000L)),
                relevant = ConnectionIssueBannerPolicy.relevantNow(3, NativeTransport.WIFI_DIRECT)
            )
        )
    }

    @Test
    fun `a condition whose remedy is already in place is not shown`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 1_000L)),
                remedyApplied = setOf(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE)
            )
        )
    }

    @Test
    fun `an older relevant condition beats a newer irrelevant one`() {
        // The interaction that matters. Picking the newest first and filtering afterwards would
        // show nothing here, and the user would be left with no reason for a failure that has one.
        assertEquals(
            ConnectionIssue.BSSID_UNAVAILABLE,
            bannerFor(
                standing = listOf(
                    standing(ConnectionIssue.BSSID_UNAVAILABLE, 1_000L),
                    standing(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 9_000L)
                ),
                relevant = ConnectionIssueBannerPolicy.relevantNow(3, NativeTransport.WIFI_DIRECT)
            )
        )
    }

    @Test
    fun `an older un-remedied condition beats a newer remedied one`() {
        assertEquals(
            ConnectionIssue.BLUETOOTH_SENT_NO_DATA,
            bannerFor(
                standing = listOf(
                    standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L),
                    standing(ConnectionIssue.BSSID_UNAVAILABLE, 9_000L)
                ),
                remedyApplied = setOf(ConnectionIssue.BSSID_UNAVAILABLE)
            )
        )
    }

    @Test
    fun `a dismissal is still judged against the condition actually shown`() {
        // The newest record is hidden, so the dismissal must be compared with the one that is
        // left. Comparing against the newest standing record instead would hide a condition the
        // user has never seen.
        assertEquals(
            ConnectionIssue.BSSID_UNAVAILABLE,
            bannerFor(
                standing = listOf(
                    standing(ConnectionIssue.BSSID_UNAVAILABLE, 3_000L),
                    standing(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 9_000L)
                ),
                dismissedAtEpochMs = 2_000L,
                relevant = ConnectionIssueBannerPolicy.relevantNow(3, NativeTransport.WIFI_DIRECT)
            )
        )
    }

    @Test
    fun `a half-finished hotspot remedy keeps its instruction after a dismissal`() {
        // The measured defect, as the screen sees it. Name set, password blank: the publish site
        // now keeps the record and re-stamps it, so a dismissal hides one occurrence and not the
        // next attempt. Before, that publish retired the record and the banner never came back.
        val remedy = ConnectionIssueBannerPolicy.remedyApplied("OHU-TEST", "", null)
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 1_000L)),
                dismissedAtEpochMs = 2_000L,
                remedyApplied = remedy
            )
        )
        assertEquals(
            ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE,
            bannerFor(
                standing = listOf(standing(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 3_000L)),
                dismissedAtEpochMs = 2_000L,
                remedyApplied = remedy
            )
        )
    }

    // What the publish sites leave standing, and what this screen then does with it.
    //
    // The pair of rules the record change rests on. A record is retired only by a disproof, so the
    // publish sites decline to retire records that are merely worked around - and the banner must
    // not then ask a user for work they have already done. The two halves were written apart and
    // could drift apart; UserExitHotspotPolicyTest has an assertion of the same shape.

    @Test
    fun `a hotspot record the publish site declines to retire is either still true or already hidden`() {
        var retired = 0
        var keptAndShown = 0
        var keptAndHidden = 0
        for (manualSsid in listOf("", "OHU-TEST")) {
            for (manualPassword in listOf("", "testtest1234")) {
                for (system in listOf(
                    null,
                    SoftApCredentials("", ""),
                    SoftApCredentials("AndroidAP", ""),
                    SoftApCredentials("AndroidAP", "swordfish")
                )) {
                    // SoftApCredentialsProvider reads the system configuration only when no manual
                    // name is set; a state the call site cannot produce proves nothing.
                    val config = if (manualSsid.isEmpty()) system else null
                    val where = "ssid='$manualSsid' pw='$manualPassword' system=$config"
                    if (SoftApCredentialsPolicy.decide(manualSsid, manualPassword, config, "192.168.43.1")
                        != SoftApCredentialsAttempt.PUBLISHED
                    ) continue
                    if (SoftApCredentialsPolicy.disprovesConfigUnreadable(manualSsid, manualPassword, config)) {
                        retired++
                        continue
                    }
                    val resolved = SoftApCredentialsPolicy.resolve(manualSsid, manualPassword, config)
                    val joinable = SoftApCredentialsPolicy.isJoinable(resolved.ssid, resolved.passphrase)
                    val hidden = ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE in
                        ConnectionIssueBannerPolicy.remedyApplied(manualSsid, manualPassword, null)
                    assertTrue(where, !joinable || hidden)
                    if (joinable) keptAndHidden++ else keptAndShown++
                }
            }
        }
        // Not vacuous: a filter bug that skipped everything would otherwise pass.
        assertTrue("no state retired the record", retired > 0)
        assertTrue("no state kept a record the banner shows", keptAndShown > 0)
        assertTrue("no state kept a record the remedy hides", keptAndHidden > 0)
    }

    @Test
    fun `a BSSID record the handshake declines to retire is already hidden`() {
        var retired = 0
        var kept = 0
        for (override in listOf(
            null, "", "0", "not-a-mac", "02:00:00:00:00:00", "aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF"
        )) {
            for (shell in listOf(null, "11:22:33:44:55:66", "00:00:00:00:00:00")) {
                for (hardware in listOf(null, "77:88:99:aa:bb:cc")) {
                    val resolved = SoftApBssidPolicy.choose(override, shell, hardware)
                    // No usable address is the abort branch, which raises rather than retires.
                    if (!SoftApBssidPolicy.isUsable(resolved)) continue
                    if (SoftApBssidPolicy.disprovesBssidUnavailable(resolved, override)) {
                        retired++
                        continue
                    }
                    assertTrue(
                        "override='$override' shell='$shell' hw='$hardware' -> '$resolved'",
                        ConnectionIssue.BSSID_UNAVAILABLE in
                            ConnectionIssueBannerPolicy.remedyApplied("", "", override)
                    )
                    kept++
                }
            }
        }
        assertTrue("no state retired the record", retired > 0)
        assertTrue("no state kept a record", kept > 0)
    }
}
