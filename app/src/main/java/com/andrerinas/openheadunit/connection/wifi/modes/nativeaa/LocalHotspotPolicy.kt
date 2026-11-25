package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.direct.P2pChannelClosePolicy
import com.andrerinas.openheadunit.connection.wifi.direct.P2pInterfaceNamePolicy
import com.andrerinas.openheadunit.connection.wifi.direct.P2pTeardown

/**
 * Every decision about the local-only hotspot source that needs no Android class: which source
 * runs, whether a reservation can be joined, which interface and address it has, what a failure
 * is called, and how often a restart is allowed. The Android shell is [LocalOnlyHotspotHost].
 */
object LocalHotspotPolicy {

    enum class Source { LOCAL_ONLY, SYSTEM }

    /** `startLocalOnlyHotspot` exists from Android 8. */
    const val MIN_SDK = 26
    const val START_TIMEOUT_MS = 20_000L
    const val INTERFACE_WAIT_MS = 5_000L
    const val INTERFACE_POLL_MS = 250L
    /** Past the channel-close fallback, so a `removeGroup` with no answer still closes the channel first. */
    const val TEARDOWN_TIMEOUT_MS = P2pChannelClosePolicy.CLOSE_FALLBACK_MS + 1_000L
    const val RESTART_WINDOW_MS = 60_000L
    const val MAX_STARTS_IN_WINDOW = 3
    /** A refused call is not retried by a refresh for this long. */
    const val REFUSED_RETRY_MS = 5_000L
    const val OLD_INTERFACE_WAIT_MS = 3_000L
    const val OLD_INTERFACE_POLL_MS = 100L

    private const val CHANNEL_CLOSE_SDK = 27
    private const val NEARBY_PERMISSION_SDK = 33
    private const val SECURITY_WPA2_PSK = 1
    private const val SECURITY_WPA2_WPA3_TRANSITION = 2
    private const val REASON_INCOMPATIBLE_MODE = 3

    fun source(sdkInt: Int, useSystemHotspot: Boolean): Source =
        if (sdkInt >= MIN_SDK && !useSystemHotspot) Source.LOCAL_ONLY else Source.SYSTEM

    fun showsUseSystemRow(sdkInt: Int, mode: WifiLauncherMode, transport: NativeStrategy): Boolean =
        sdkInt >= MIN_SDK && mode == WifiLauncherMode.NATIVE && transport == NativeStrategy.HOTSPOT

    /** Stable ids of the Native Hotspot rows that do nothing on the local source. */
    fun hiddenNativeRows(source: Source): Set<String> =
        if (source == Source.LOCAL_ONLY) {
            setOf(
                "autoEnableHotspot", "hotspotBand", "fiveGhzChannel", "hotspotSsidOverride",
                "hotspotPasswordOverride", "hotspotInterfaceOverride", "projectionSetupQr",
            )
        } else {
            emptySet()
        }

    /** Helper and Headunit Server keep the Auto-Enable toggle whatever the source says. */
    fun hidesAutoEnableToggle(mode: WifiLauncherMode, source: Source): Boolean =
        mode == WifiLauncherMode.NATIVE && source == Source.LOCAL_ONLY

    /**
     * Whether two Native launchers start the same way. The source only matters on the Hotspot
     * transport, so a changed setting restarts that mode and leaves a Wi-Fi Direct one alone.
     */
    fun sameStartConfiguration(
        strategy: NativeStrategy,
        source: Source,
        otherStrategy: NativeStrategy,
        otherSource: Source,
    ): Boolean = strategy == otherStrategy && (strategy != NativeStrategy.HOTSPOT || source == otherSource)

    /** The local hotspot is ours and fresh on each start, so no identity is graded or advertised. */
    fun assessesIdentity(source: Source): Boolean = source != Source.LOCAL_ONLY

    sealed class ReservationVerdict {
        object Joinable : ReservationVerdict()
        object NotJoinable : ReservationVerdict()
        data class UnsupportedSecurity(val type: Int) : ReservationVerdict()
    }

    /** [securityType] is the `SoftApConfiguration` value: 1 WPA2-PSK, 2 WPA2/WPA3 transition. */
    fun judgeReservation(ssid: String?, passphrase: String?, securityType: Int): ReservationVerdict = when {
        ssid.isNullOrBlank() || passphrase.isNullOrBlank() -> ReservationVerdict.NotJoinable
        securityType == SECURITY_WPA2_PSK || securityType == SECURITY_WPA2_WPA3_TRANSITION -> ReservationVerdict.Joinable
        else -> ReservationVerdict.UnsupportedSecurity(securityType)
    }

    /** The platform quotes a UTF-8 name in the config. The phone needs the bare text. */
    fun cleanSsid(raw: String?): String? {
        if (raw == null) return null
        return if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
    }

    /** Below API 30 the config carries key-management bits. WPA2-PSK is type 1, open is 0, the rest 3. */
    fun legacySecurityType(wpa2Psk: Boolean, open: Boolean): Int = when {
        wpa2Psk -> SECURITY_WPA2_PSK
        open -> 0
        else -> 3
    }

    /** The reservation address when usable, else the first usable [interfaceRungs] entry, else null. */
    fun chooseBssid(reservationBssid: String?, interfaceRungs: List<String?>): String? =
        (listOf(reservationBssid) + interfaceRungs).firstOrNull { SoftApBssidPolicy.isUsable(it) }

    /**
     * The interface the new reservation brought up: a site-local address, not a P2P one, and a
     * (name, address) pair [before] did not hold. One that is up wins over one that waits for its
     * first station. A reused name with a new address counts.
     */
    fun newInterface(
        before: Set<Pair<String, String>>,
        now: List<ApInterfaceCandidate>,
        stationIpv4: String? = null,
    ): ApInterfaceCandidate? {
        val fresh = now.filter { candidate ->
            val ip = candidate.siteLocalIpv4 ?: return@filter false
            !P2pInterfaceNamePolicy.canCarryGroupAddress(candidate.name) && (candidate.name to ip) !in before
        }
        return SoftApNetworkPolicy.pickApInterface(fresh, stationIpv4)
    }

    enum class InterfaceStep { PUBLISH, POLL, NO_INTERFACE, NO_ADDRESS }

    /**
     * An interface can hold its IPv4 about a second before its link is ready. On a unit that hides the
     * hardware address the BSSID comes from the IPv6 link-local, which exists only after that: poll for both.
     */
    fun interfaceStep(interfaceFound: Boolean, bssidFound: Boolean, nowMs: Long, deadlineMs: Long): InterfaceStep = when {
        interfaceFound && bssidFound -> InterfaceStep.PUBLISH
        nowMs < deadlineMs -> InterfaceStep.POLL
        interfaceFound -> InterfaceStep.NO_ADDRESS
        else -> InterfaceStep.NO_INTERFACE
    }

    /** The (name, address) pairs now up, without [ownPair]: the hotspot that owned it is closed. */
    fun beforeSet(candidates: List<ApInterfaceCandidate>, ownPair: Pair<String, String>?): Set<Pair<String, String>> =
        candidates.mapNotNull { c -> c.siteLocalIpv4?.let { c.name to it } }.filter { it != ownPair }.toSet()

    /** True once no candidate holds both the name and the address of [ownPair]. */
    fun oldPairGone(candidates: List<ApInterfaceCandidate>, ownPair: Pair<String, String>?): Boolean =
        ownPair == null || candidates.none { it.name == ownPair.first && it.siteLocalIpv4 == ownPair.second }

    /** The band the reservation asked for. Only the 5 GHz and higher answer lifts the video cap. */
    enum class LocalBand(val label: String) {
        BAND_24("2.4 GHz"),
        ABOVE_24("5 GHz or higher"),
        UNKNOWN("unknown"),
    }

    /** `SoftApConfiguration.BAND_2GHZ`; the other band bits are 5, 6 and 60 GHz. */
    private const val BAND_2GHZ = 1

    /** A mask that holds 2.4 GHz may run there, so it counts as 2.4 GHz. 0 means nothing was read. */
    fun bandFromMask(mask: Int): LocalBand = when {
        mask <= 0 -> LocalBand.UNKNOWN
        (mask and BAND_2GHZ) != 0 -> LocalBand.BAND_24
        else -> LocalBand.ABOVE_24
    }

    /** The keys of `SoftApConfiguration.getChannels()` are band bits; their union is the mask. */
    fun bandMaskFromChannelKeys(keys: List<Int>): Int = keys.fold(0) { mask, key -> mask or key.coerceAtLeast(0) }

    private val CHANNELS_TEXT = Regex("""Channels\s*=\s*\{([^}]*)\}""")
    private val BAND_TEXT = Regex("""\bBand\s*=\s*(\d+)""")

    /**
     * Reads the band mask from `SoftApConfiguration.toString()`: `Channels = {1=0}` from API 31,
     * `Band =1` on API 30. The text is no stable contract, so anything else gives 0 (unknown).
     */
    fun bandMaskFromConfigText(text: String?): Int {
        if (text == null) return 0
        CHANNELS_TEXT.find(text)?.let { match ->
            val keys = match.groupValues[1].split(',').mapNotNull { it.substringBefore('=').trim().toIntOrNull() }
            return bandMaskFromChannelKeys(keys)
        }
        return BAND_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** The cap applies on 2.4 GHz and when the band is unknown. No channel list is sent: the channel is not read. */
    fun capsVideo(band: LocalBand): Boolean = band != LocalBand.ABOVE_24

    /** Names the grants a start needs and the unit lacks. Empty means the call may go ahead. */
    fun missingGrants(
        sdkInt: Int,
        targetSdk: Int,
        nearbyGranted: Boolean,
        fineGranted: Boolean,
        locationOn: Boolean,
    ): List<String> = buildList {
        if (sdkInt >= NEARBY_PERMISSION_SDK && targetSdk >= NEARBY_PERMISSION_SDK) {
            if (!nearbyGranted) add("NEARBY_WIFI_DEVICES")
        } else {
            if (!fineGranted) add("ACCESS_FINE_LOCATION")
            if (!locationOn) add("location switch")
        }
    }

    sealed class HostEvent {
        data class Failed(val reason: Int) : HostEvent()
        object Stopped : HostEvent()
    }

    data class AfterStart(val failure: LocalHotspotFailure, val isStartFailure: Boolean, val invalidates: Boolean)

    /** A failure before `onStarted` is a start failure. One after it ends a run that was live. */
    fun afterStart(event: HostEvent, started: Boolean, wifiOn: Boolean = true): AfterStart = when (event) {
        HostEvent.Stopped -> AfterStart(LocalHotspotFailure.PLATFORM_STOPPED, isStartFailure = !started, invalidates = true)
        is HostEvent.Failed ->
            if (started && event.reason == REASON_INCOMPATIBLE_MODE) {
                AfterStart(LocalHotspotFailure.SYSTEM_HOTSPOT_STARTED, isStartFailure = false, invalidates = true)
            } else {
                AfterStart(LocalHotspotFailure.fromReason(event.reason, wifiOn), isStartFailure = !started, invalidates = true)
            }
    }

    data class StartFailureResponse(val raisesBanner: Boolean, val nextSource: Source)

    /** A failed start raises the banner record and stays on the local source: no silent fall-back. */
    fun onStartFailure(): StartFailureResponse = StartFailureResponse(raisesBanner = true, nextSource = Source.LOCAL_ONLY)

    enum class RestartCause(val label: String) {
        CLOSE("close"),
        PLATFORM_STOP("platform stop"),
        WAKE("wake"),
    }

    enum class RefreshAction { REREAD, WAIT, RESTART, GIVE_UP }

    /** Stamps on `elapsedRealtime` that still fall in the restart window. */
    fun prune(stampsMs: List<Long>, nowMs: Long): List<Long> = stampsMs.filter { nowMs - it < RESTART_WINDOW_MS }

    fun restartAllowed(stampsMs: List<Long>, nowMs: Long): Boolean =
        prune(stampsMs, nowMs).size < MAX_STARTS_IN_WINDOW

    /**
     * A live reservation is re-read and never restarted. A refused call holds a refresh for
     * [REFUSED_RETRY_MS]: it has no stamp, so nothing else bounds the loop.
     */
    fun onRefresh(
        reservationLive: Boolean,
        startInFlight: Boolean,
        restartStampsMs: List<Long>,
        nowMs: Long,
        lastRefusedAtMs: Long? = null,
    ): RefreshAction = when {
        reservationLive -> RefreshAction.REREAD
        startInFlight -> RefreshAction.WAIT
        lastRefusedAtMs != null && nowMs - lastRefusedAtMs < REFUSED_RETRY_MS -> RefreshAction.WAIT
        restartAllowed(restartStampsMs, nowMs) -> RefreshAction.RESTART
        else -> RefreshAction.GIVE_UP
    }

    sealed class StartDecision {
        /** [cause] is null when the process has no earlier end. */
        data class Go(val cause: RestartCause?, val attempt: Int) : StartDecision()
        object GiveUp : StartDecision()
    }

    /** Asks the same ledger as [onRefresh], for a `start()` that builds a new reservation. */
    fun startDecision(startStampsMs: List<Long>, lastEnd: RestartCause?, nowMs: Long): StartDecision =
        if (restartAllowed(startStampsMs, nowMs)) {
            StartDecision.Go(lastEnd, prune(startStampsMs, nowMs).size + 1)
        } else {
            StartDecision.GiveUp
        }

    /**
     * The unanswered mark after a call to `startLocalOnlyHotspot` threw. The call opened no request,
     * so the mark goes back to [earlier], whose request the platform may still hold.
     */
    fun <T : Any> pendingAfterThrow(current: T?, thrower: T, earlier: T?): T? =
        if (current === thrower) earlier else current

    enum class Gate { GO, WAIT, TIMED_OUT }

    /**
     * Holds the start until the Wi-Fi Direct stop is done: group removed, channel closed (API 27
     * and later) and no kept group. A null record means nothing stopped. The bound is
     * [TEARDOWN_TIMEOUT_MS] after the stop.
     */
    fun teardownGate(teardown: P2pTeardown?, sdkInt: Int, nowMs: Long): Gate {
        if (teardown == null || !teardown.stopped) return Gate.GO
        val done = !teardown.keptGroup && teardown.groupRemoved &&
            (teardown.channelClosed || sdkInt < CHANNEL_CLOSE_SDK)
        return when {
            done -> Gate.GO
            nowMs - teardown.stoppedAtMs > TEARDOWN_TIMEOUT_MS -> Gate.TIMED_OUT
            else -> Gate.WAIT
        }
    }

    enum class CloseReason(val label: String) {
        USER_EXIT("user exit"),
        LAUNCHER_REPLACED("launcher replaced"),
        SESSION_END("session end"),
    }

    /** A noted user exit wins: the exit path stops the launcher at `ANY`, which reads as a replacement. */
    fun closeReason(launcherReplaced: Boolean, userExitNoted: Boolean): CloseReason = when {
        userExitNoted -> CloseReason.USER_EXIT
        launcherReplaced -> CloseReason.LAUNCHER_REPLACED
        else -> CloseReason.SESSION_END
    }

    // The rig greps these prefixes. LocalHotspotPolicyTest pins them.

    fun startedLine(ssid: String, security: Int, bssid: String, iface: String, ip: String) =
        "LocalHotspot: started ssid=$ssid security=$security bssid=$bssid iface=$iface ip=$ip"

    fun bandLine(band: LocalBand, mask: Int) =
        "LocalHotspot: band ${band.label} (mask=$mask), channel list 0, 2.4 GHz cap " +
            if (capsVideo(band)) "applied" else "not forced"

    fun stationLine(before: Boolean, after: Boolean) =
        "LocalHotspot: station state before=${joined(before)} after=${joined(after)}"

    fun failedLine(reason: Int, failure: LocalHotspotFailure, sdkInt: Int) =
        "LocalHotspot: start failed reason=$reason (${failure.name}): ${failure.describe(sdkInt)}"

    fun closedLine(reason: CloseReason) = "LocalHotspot: closed (${reason.label})"

    fun restartLine(cause: RestartCause, attempt: Int) = "LocalHotspot: restart after ${cause.label} attempt=$attempt"

    fun strayClosedLine() = "LocalHotspot: closed a hotspot that an earlier start left up"

    fun oldInterfaceLine() =
        "LocalHotspot: the old interface was still up after ${OLD_INTERFACE_WAIT_MS / 1000} s; " +
            "its address is not taken as an old one"

    fun teardownLine(done: Boolean) =
        if (done) "LocalHotspot: p2p teardown done before start" else "LocalHotspot: p2p teardown timed out"

    fun platformStoppedLine() = "LocalHotspot: the platform stopped the hotspot"

    fun notAppliedLine() =
        "SoftApCredentials: the local hotspot is in use; the manual name, password, interface, " +
            "static BSSID and Auto-Enable Hotspot are not applied."

    private fun joined(value: Boolean) = if (value) "joined" else "not"
}
