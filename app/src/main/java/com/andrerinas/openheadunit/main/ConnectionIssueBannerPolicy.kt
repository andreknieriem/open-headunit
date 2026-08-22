package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.aap.NativeTransport
import com.andrerinas.openheadunit.aap.SoftApBssidPolicy
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.StandingIssue

/**
 * Decides whether the main screen shows a connection-failure banner, and which one.
 *
 * Split out as a pure object because every one of these rules is a judgement that can be got
 * wrong quietly: a banner shown beside a working session, one that survives its own remedy, or one
 * a user can never be rid of. None of them needs Android to decide.
 */
object ConnectionIssueBannerPolicy {

    /** [com.andrerinas.openheadunit.utils.Settings.wifiConnectionMode] for Native AA wireless. */
    private const val NATIVE_AA_MODE = 3

    /**
     * The conditions the currently selected route can still be blocked by.
     *
     * Keyed on where each condition is *raised*, not on some looser notion of what a route
     * touches, so this can never claim one the route is incapable of producing:
     *
     * - `BLUETOOTH_SENT_NO_DATA` and `BSSID_UNAVAILABLE` are raised in `NativeAaHandshakeManager`,
     *   which only runs in Native AA. `BSSID_UNAVAILABLE` is raised on the abort branch alone, and
     *   only WiFi Direct aborts — the hotspot transport sends an empty BSSID and carries on
     *   (`NativeCredentialsPolicy.onUnusableBssid`).
     * - `HOTSPOT_CONFIG_UNREADABLE` is raised in `SoftApCredentialsProvider`, which only the
     *   Native AA hotspot transport ever constructs. Helper strategy 4 rides its own access point
     *   too but never resolves credentials from it, so it cannot raise this and does not appear
     *   here — which is also why this takes no `helperConnectionStrategy`: no rule uses it.
     *
     * A record is not deleted when it stops applying. It describes what the hardware did, and the
     * user may well be back on that route tomorrow; it is only hidden while it cannot be the
     * reason the last attempt failed.
     */
    fun relevantNow(mode: Int, transport: NativeTransport): Set<ConnectionIssue> {
        if (mode != NATIVE_AA_MODE) return emptySet()
        return when (transport) {
            NativeTransport.WIFI_DIRECT -> setOf(
                ConnectionIssue.BLUETOOTH_SENT_NO_DATA,
                ConnectionIssue.BSSID_UNAVAILABLE
            )
            NativeTransport.HOTSPOT -> setOf(
                ConnectionIssue.BLUETOOTH_SENT_NO_DATA,
                ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE
            )
        }
    }

    /**
     * The conditions whose own remedy is already in place, so the banner would be asking for work
     * that has already been done.
     *
     * These records are cleared only by something that *disproves* them, which the user typing a
     * value never does: the device that would not name its own access point still will not, and the
     * unit that could not read its own address still cannot. So a user who typed the hotspot name
     * the banner asked for, and whose access point then happens to be off at the next launch, would
     * be told again to enter it by hand. The record stays; the instruction stops.
     *
     * That makes this function load-bearing rather than cosmetic. It is now the only thing keeping
     * a worked-around condition off the screen, and the publish sites that decline to retire those
     * records rely on it - `SoftApCredentialsPolicy.disprovesConfigUnreadable` and
     * `SoftApBssidPolicy.disprovesBssidUnavailable` are its exact complements, and a test asserts
     * that every state one of them declines to retire is one this function hides.
     *
     * `BLUETOOTH_SENT_NO_DATA` has no entry because its remedy is leaving Native AA, which
     * [relevantNow] already answers.
     *
     * @param hotspotSsid [com.andrerinas.openheadunit.utils.Settings.hotspotSsid]
     * @param hotspotPassword [com.andrerinas.openheadunit.utils.Settings.hotspotPassword] — needed
     *   as well as the name, and not as an alternative to it: once a manual name is set the
     *   device's own configuration is never read, so the passphrase has nothing to fall back to
     *   and an unset one is sent as an open network the phone refuses. See
     *   [com.andrerinas.openheadunit.aap.SoftApCredentialsPolicy.resolve].
     * @param staticBssid [com.andrerinas.openheadunit.utils.Settings.staticBSSID], judged by the
     *   same predicate the handshake uses, so a value the chain would discard is not a remedy.
     */
    fun remedyApplied(
        hotspotSsid: String,
        hotspotPassword: String,
        staticBssid: String?
    ): Set<ConnectionIssue> {
        val applied = mutableSetOf<ConnectionIssue>()
        if (hotspotSsid.isNotEmpty() && hotspotPassword.isNotEmpty()) {
            applied.add(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE)
        }
        if (SoftApBssidPolicy.isUsable(staticBssid)) {
            applied.add(ConnectionIssue.BSSID_UNAVAILABLE)
        }
        return applied
    }

    /**
     * The issue to show, or null for none.
     *
     * @param standing        everything currently true, in any order
     * @param dismissedAtEpochMs when the user last dismissed the banner, 0 for never
     * @param sessionConnected whether a projection session is live right now
     * @param onboardingComplete whether the setup wizard has been finished
     * @param relevant        what the selected route can be blocked by, from [relevantNow]
     * @param remedyApplied   what the user has already fixed, from [remedyApplied]
     */
    fun bannerFor(
        standing: List<StandingIssue>,
        dismissedAtEpochMs: Long,
        sessionConnected: Boolean,
        onboardingComplete: Boolean,
        relevant: Set<ConnectionIssue>,
        remedyApplied: Set<ConnectionIssue>
    ): ConnectionIssue? {
        // Never beside a working session. Not cosmetic: in picture-in-picture the main screen is
        // deliberately left in front of a live projection, so an unguarded banner would sit next
        // to a connection that is working perfectly well.
        if (sessionConnected) return null
        // Never in front of the wizard, for the reason RenameNotice already waits: a notice spent
        // behind onboarding is a notice nobody read.
        if (!onboardingComplete) return null

        // The most recent standing issue that can still be the answer. A standing issue is by
        // definition still true, and the banner answers "why did my last attempt fail", so the
        // latest is the one that answers it - but only among the ones this route can produce and
        // has not already been fixed, or the newest record wins the screen while saying nothing
        // about the connection in front of the user.
        //
        // One at a time rather than a list: these panels are commonly 1024x600 and often 800x480,
        // and three stacked warnings would be the screen.
        val newest = standing
            .filter { it.raisedAtEpochMs != 0L }
            .filter { it.issue in relevant }
            .filter { it.issue !in remedyApplied }
            .maxByOrNull { it.raisedAtEpochMs }
            ?: return null

        // Dismissal is per occurrence, not permanent. Hiding what the user has already read costs
        // nothing; hiding the next failure too would make the banner useless on the second drive.
        if (newest.raisedAtEpochMs <= dismissedAtEpochMs) return null

        return newest.issue
    }
}
