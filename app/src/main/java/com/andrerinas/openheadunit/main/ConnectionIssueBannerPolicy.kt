package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.aap.NativeTransport
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBssidPolicy
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

    /**
     * What either Native transport can raise. Kept apart from the per-transport extras because
     * both lists had to be edited by hand for every record belonging to both, and one was missed.
     */
    private val EITHER_NATIVE_TRANSPORT = setOf(
        ConnectionIssue.BLUETOOTH_SENT_NO_DATA,
        ConnectionIssue.VIDEO_LINK_TOO_SLOW,
        ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE,
        ConnectionIssue.HANDS_FREE_RECORD_REFUSED,
        ConnectionIssue.PHONE_HOLDS_STALE_ENDPOINT
    )

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
     * - `HOTSPOT_NOT_RUNNING` and `HOTSPOT_CONFIG_UNREADABLE` are raised in `SoftApCredentialsProvider`, which only the
     *   Native AA hotspot transport ever constructs. Helper strategy 4 rides its own access point
     *   too but never resolves credentials from it, so it cannot raise this and does not appear
     *   here — which is also why this takes no `helperConnectionStrategy`: no rule uses it.
     * - `WIFI_DIRECT_GROUP_REFUSED` is raised in `WifiDirectManager` on the branch that gives up on
     *   group creation, which only the Native AA WiFi Direct transport reaches. The helper's own
     *   WiFi Direct strategy shares that manager but hands the phone no credentials, so a refused
     *   group there is not the reason a connection failed.
     * - `WIFI_DIRECT_STACK_CYCLED` is raised in the same manager's state receiver and is the cause
     *   the refusal above is usually the symptom of, so it is keyed the same way.
     * - `WIFI_RADIO_OFF` is raised on the branch above both of those, where the radio is off and the
     *   platform refuses to switch it on, so no group is ever asked for. Same transport, same key.
     * - `HANDS_FREE_HELD_ELSEWHERE` is raised by the wake poke in `NativeAaHandshakeManager`, which
     *   only runs in Native AA but runs on both of its transports, so it is keyed to both.
     * - `HANDS_FREE_RECORD_REFUSED` is raised where that manager opens its listeners, which is the
     *   same mode on the same two transports, so it is keyed the same way.
     *
     * A record is not deleted when it stops applying. It describes what the hardware did, and the
     * user may well be back on that route tomorrow; it is only hidden while it cannot be the
     * reason the last attempt failed.
     */
    fun relevantNow(
        mode: Int,
        transport: NativeTransport,
        wirelessSelected: Boolean,
    ): Set<ConnectionIssue> {
        // Keyed on its endpoint rather than on a mode or on wireless being chosen: it is raised
        // only when the peer we dialled was Android Auto's own head unit server, so its presence
        // already proves the route that produces it ran. Self Mode reaches it either way.
        val anyMode = setOf(ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING)
        // A cable-only unit never brings the wireless stack up, so none of the rest can be the
        // reason its last attempt failed, however recently the record was written.
        if (!wirelessSelected) return anyMode
        if (mode != NATIVE_AA_MODE) return anyMode
        return anyMode + when (transport) {
            NativeTransport.WIFI_DIRECT -> EITHER_NATIVE_TRANSPORT + setOf(
                ConnectionIssue.BSSID_UNAVAILABLE,
                ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED,
                ConnectionIssue.WIFI_DIRECT_STACK_CYCLED,
                ConnectionIssue.WIFI_RADIO_OFF,
                ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED
            )
            NativeTransport.HOTSPOT -> EITHER_NATIVE_TRANSPORT + setOf(
                ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE,
                ConnectionIssue.HOTSPOT_NOT_RUNNING
            )
        }
    }

    /**
     * The conditions whose own remedy is already in place, so the banner would be asking for work
     * that has already been done.
     *
     * These records are cleared only by something that *disproves* them, which the user typing a
     * value never does: the device that would not name its own access point still will not, and a
     * unit whose every source came back empty is not made to read one by a value typed into a box. So a user who typed the hotspot name
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
     * [relevantNow] already answers. `HOTSPOT_NOT_RUNNING`, `WIFI_DIRECT_GROUP_REFUSED`,
     * `WIFI_DIRECT_STACK_CYCLED`, `FIVE_GHZ_CHANNEL_REFUSED` and `VIDEO_LINK_TOO_SLOW` have none
     * either: no setting fixes them, and an access point coming up, a group forming or a session
     * rendering a frame disproves them outright, so those records retire themselves. The channel
     * one is retired by a narrower event than the rest - a group formed *on the channel that was
     * asked for*, since one on the driver's own pick is the failure it describes. Lowering the
     * frame rate is deliberately not a remedy here: it is a guess at the ceiling, and only a
     * session that renders proves it was enough. `HEADUNIT_SERVER_NOT_ANSWERING` has no entry
     * either: its remedy is on the phone, and a handshake that completes disproves it.
     * `HANDS_FREE_HELD_ELSEWHERE` has none for the same shape of reason: the lever is the other
     * device, and the next wake pass that reads the link free retires it.
     * `PHONE_HOLDS_STALE_ENDPOINT` has none either: the record lives on the phone, no setting here
     * reaches it, and a dial this unit serves disproves it. `HANDS_FREE_RECORD_REFUSED` has none
     * because it is this unit's own Bluetooth stack refusing, and a registration that succeeds on a
     * later arming retires it.
     *
     * @param hotspotSsid [com.andrerinas.openheadunit.utils.Settings.hotspotSsid]
     * @param hotspotPassword [com.andrerinas.openheadunit.utils.Settings.hotspotPassword] — needed
     *   as well as the name, and not as an alternative to it: once a manual name is set the
     *   device's own configuration is never read, so the passphrase has nothing to fall back to
     *   and an unset one is sent as an open network the phone refuses. See
     *   [com.andrerinas.openheadunit.aap.SoftApCredentialsPolicy.resolve].
     * @param staticBssid [com.andrerinas.openheadunit.utils.Settings.staticBSSID], judged by the
     *   same predicate the handshake uses, so a value the chain would discard is not a remedy.
     * @param staticP2pBssid [com.andrerinas.openheadunit.utils.Settings.staticP2pBSSID]. Either
     *   address answers the record, which is about this unit not reading its own: the user only
     *   types the one for the transport they run.
     */
    fun remedyApplied(
        hotspotSsid: String,
        hotspotPassword: String,
        staticBssid: String?,
        staticP2pBssid: String? = null
    ): Set<ConnectionIssue> {
        val applied = mutableSetOf<ConnectionIssue>()
        if (hotspotSsid.isNotEmpty() && hotspotPassword.isNotEmpty()) {
            applied.add(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE)
        }
        if (SoftApBssidPolicy.isUsable(staticBssid) || SoftApBssidPolicy.isUsable(staticP2pBssid)) {
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
