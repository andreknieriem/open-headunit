package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether to drop this unit's own WiFi association before creating the Native AA P2P group.
 *
 * A station joined on 2.4 GHz leaves a single-radio group owner no 5 GHz channel, and a station
 * left disconnected scans on the platform's own schedule, each scan taking the radio off the group.
 * So AUTO stands down where the group can gain a band, or where the two bands already differ.
 * On Android 7.1 and earlier, a known 2.4 GHz-only radio also leaves its station network: those
 * legacy P2P stacks can refuse to create a group while the station association is active.
 * Pure, so every case is a unit test.
 */
object StationStandDownPolicy {

    /** First Android whose framework refuses these calls to an ordinary app. */
    private const val FIRST_API_WITH_TARGET_SDK_GUARD = 29

    /** Last Android where holding the overlay permission still gets past that guard. */
    private const val LAST_API_WITH_OVERLAY_BYPASS = 34

    /** Legacy P2P APIs on these releases can refuse a group while a 2.4 GHz-only station is joined. */
    private const val LAST_API_WITH_LEGACY_P2P_STATION_CONFLICT = 25

    /**
     * Whether the platform will honour `disableNetwork()` at all.
     *
     * The guard is `WifiServiceImpl.isTargetSdkLessThanQOrPrivileged`, which lives in the *device's*
     * framework and arrived in Android 10 — so this app's target SDK does not decide it, the head
     * unit's version does. That guard also passes anything holding SYSTEM_ALERT_WINDOW, which this
     * app already asks for, and that bypass was removed again in Android 15.
     */
    fun isAvailable(sdkInt: Int, canDrawOverlays: Boolean): Boolean = when {
        sdkInt < FIRST_API_WITH_TARGET_SDK_GUARD -> true
        sdkInt <= LAST_API_WITH_OVERLAY_BYPASS -> canDrawOverlays
        else -> false
    }

    /** Below this a station is on 2.4 GHz; the 5 GHz band starts at 5170 MHz. */
    private const val MAX_24GHZ_FREQUENCY_MHZ = 4000

    /**
     * Whether to stand the station down now.
     *
     * @param networkId `WifiInfo.getNetworkId()`, which is -1 both when nothing is joined and when
     *   the caller cannot satisfy the location gate. Either way there is no network to name, and
     *   guessing one would disable a network the user never joined.
     * @param supports5Ghz [WifiBandCapability.supports5Ghz]; only a `false` is acted on.
     * @param stationFrequencyMhz `WifiInfo.getFrequency()`, or 0 where it cannot be read, which
     *   counts as not 5 GHz so a capable unit still stands down.
     * @param groupBand what the group is about to ask for. This runs before `createGroup`, so a
     *   group that falls back to 2.4 GHz from a refused 5 GHz create is not covered here.
     */
    fun shouldStandDown(
        mode: StationStandDownMode,
        sdkInt: Int,
        canDrawOverlays: Boolean,
        associated: Boolean,
        networkId: Int,
        supports5Ghz: Boolean?,
        stationFrequencyMhz: Int,
        groupBand: P2pBandPreference,
    ): Boolean = associated &&
        networkId >= 0 &&
        isAvailable(sdkInt, canDrawOverlays) &&
        when (mode) {
            StationStandDownMode.NEVER -> false
            StationStandDownMode.ALWAYS -> true
            // A 2.4 GHz group beside a 5 GHz station is the split measured to cost the session:
            // the handshake completes and the phone never finishes joining.
            StationStandDownMode.AUTO ->
                (sdkInt <= LAST_API_WITH_LEGACY_P2P_STATION_CONFLICT && supports5Ghz == false) ||
                    (supports5Ghz != false &&
                        (!isFiveGhz(stationFrequencyMhz) ||
                            groupBand == P2pBandPreference.FORCE_2_4GHZ))
        }

    fun isFiveGhz(frequencyMhz: Int): Boolean = frequencyMhz > MAX_24GHZ_FREQUENCY_MHZ

    /**
     * Why the mode left the station joined, or null when it stands down. Only the mode's own
     * reasons: the platform gate and the association are described by the caller.
     */
    fun describeSkipped(
        mode: StationStandDownMode,
        sdkInt: Int,
        supports5Ghz: Boolean?,
        stationFrequencyMhz: Int,
        groupBand: P2pBandPreference,
    ): String? = when {
        mode == StationStandDownMode.NEVER ->
            "the setting keeps this unit joined to its own WiFi network, so the group shares the " +
                "radio with it."
        mode == StationStandDownMode.ALWAYS -> null
        supports5Ghz == false && sdkInt <= LAST_API_WITH_LEGACY_P2P_STATION_CONFLICT -> null
        supports5Ghz == false && sdkInt > LAST_API_WITH_LEGACY_P2P_STATION_CONFLICT ->
            "this unit's WiFi radio has no 5 GHz band, so leaving its own network could not move " +
                "the group to another channel; the station stays joined, because a disconnected " +
                "one scans for networks and every scan takes the radio off the group."
        groupBand == P2pBandPreference.FORCE_2_4GHZ -> null
        isFiveGhz(stationFrequencyMhz) ->
            "this unit's own WiFi network is on $stationFrequencyMhz MHz and the group is asking " +
                "for 5 GHz as well, which is the state measured to run clean; the station stays " +
                "joined."
        else -> null
    }

    /**
     * Why the stand-down is not going to happen on this unit, or null when it will.
     *
     * Logged rather than shown: on 29-34 the answer is a permission the user can actually grant,
     * and above that it is a fact about the unit worth having in a bug report.
     */
    fun describeUnavailable(sdkInt: Int, canDrawOverlays: Boolean): String? = when {
        isAvailable(sdkInt, canDrawOverlays) -> null
        sdkInt <= LAST_API_WITH_OVERLAY_BYPASS ->
            "This unit's Android will only let the app drop its own WiFi connection while the app " +
                "has the \"display over other apps\" permission. Granting it frees the group's radio."
        else ->
            "Android $sdkInt does not let an app drop this unit's own WiFi connection, so the " +
                "group has to share its channel. Disconnecting this unit's WiFi by hand before " +
                "connecting is the only way to free it."
    }

    /**
     * Whether a recorded stand-down still has to be undone.
     *
     * Unconditional on purpose. A record with no matching restore leaves the unit unable to rejoin
     * the owner's home network with nothing in the app saying why, which is the worst outcome this
     * whole feature can produce - so every teardown restores, and so does the next start.
     */
    fun shouldRestore(networkId: Int): Boolean = networkId >= 0
}
