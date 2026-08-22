package com.andrerinas.openheadunit.aap

/** One of the values the Native AA handshake has to hand the phone. */
enum class CredentialField {
    /** The access point's name. Hotspot transport only. */
    HOTSPOT_NAME,

    /** Its passphrase. Hotspot transport only. */
    HOTSPOT_PASSWORD,

    /** The access point's MAC. Both transports need one; see [NativeCredentialsPolicy]. */
    BSSID
}

/** Where one [CredentialField] is going to come from, or that it has nowhere to come from. */
enum class FieldVerdict {
    /** The user has already set an override, and it is usable. Never ask again. */
    SUPPLIED_BY_USER,

    /** The device answered when asked. Nothing to do. */
    AVAILABLE_FROM_DEVICE,

    /** Asked and refused, and asking later will be refused too. Only the user can supply it. */
    MUST_BE_ENTERED,

    /**
     * Could not be established yet. Not the same as [MUST_BE_ENTERED] and deliberately never
     * prompted for: the network this reads from may simply not be up at the moment, and a value
     * typed to silence a question we could not ask is worse than no value at all on the WiFi
     * Direct route, where a wrong BSSID beats every automatic source.
     */
    UNKNOWN
}

/**
 * What a caller found out about this head unit, before any network is on air.
 *
 * No Android types, so the rules below can be tested. Gathering these is
 * `connection.NativeCredentialsPreflight`'s job and involves no decisions.
 */
data class PreflightProbe(
    /** [com.andrerinas.openheadunit.utils.Settings.hotspotSsid], "" when unset. */
    val manualSsid: String,
    /** [com.andrerinas.openheadunit.utils.Settings.hotspotPassword], "" when unset. */
    val manualPassword: String,
    /** [com.andrerinas.openheadunit.utils.Settings.staticBSSID] verbatim, which is "0" when unset. */
    val staticBssid: String?,
    /**
     * What the device says its own access point is called, or null where it refused to say.
     *
     * The refusal is what makes a check before connecting worth anything: it comes from
     * `getSoftApConfiguration()`, which reads the *stored* configuration, so a device that will not
     * answer with the hotspot off will not answer with it on either.
     */
    val systemConfig: SoftApCredentials?,
    /** The best MAC any source yielded without a live network, or null. */
    val probedBssid: String?,
    /**
     * Whether the sources that were tried are the ones that would decide it at handshake time.
     *
     * False when there was nothing to read from - no access point interface, no P2P group, the
     * framework not answering yet. The distinction is the whole reason this type exists: without it
     * every unit with its hotspot switched off looks like a unit that cannot report its own MAC.
     */
    val bssidProbeConclusive: Boolean,
    /** Whether either location provider is on, or null where it could not be asked. */
    val locationServicesEnabled: Boolean?
)

/** What the pre-flight came to, in the order a caller should act on it. */
data class PreflightReport(
    /** Every field this transport uses, and where each is coming from. */
    val verdicts: Map<CredentialField, FieldVerdict>,
    /** The fields to ask the user for, in the order they should be asked. Usually empty. */
    val mustEnter: List<CredentialField>,
    /**
     * Whether location services being off is the thing to fix, rather than any field.
     *
     * Offered ahead of [mustEnter] where both apply, because a MAC typed while location is off is a
     * workaround for a switch the user can simply turn on, and it outranks every automatic source
     * afterwards - see the note on the static override in [SoftApBssidPolicy].
     */
    val locationServicesOff: Boolean
) {
    /** Whether there is anything at all to tell the user. */
    val hasFindings: Boolean get() = locationServicesOff || mustEnter.isNotEmpty()
}

/**
 * Whether this head unit can describe its own network to a phone, asked *before* the user tries to
 * connect rather than after.
 *
 * Every part of this was already decided at connect time and said in the log:
 * [SoftApCredentialsPolicy] names the unreadable-configuration dead end, and
 * [NativeCredentialsPolicy] names the missing BSSID. What is new is only the timing. Those verdicts
 * arrive while a phone is mid-handshake, as one line in a log and one toast on a screen nobody is
 * reading, and the overrides that fix them are described as "if the log says the name could not be
 * read" - so the failure is reported to the one audience least able to act on it. Most reports that
 * call this route dead are this, and the values needed were available in Settings the whole time.
 *
 * Two rules keep it honest, and both matter more than coverage:
 *
 * - **Only certainties are reported.** [FieldVerdict.UNKNOWN] produces nothing. A check that cries
 *   wolf on a healthy unit gets ignored on a broken one.
 * - **Nothing is asked for that could not work.** The WiFi Direct route reports [CredentialField.BSSID]
 *   and nothing else: its network name and passphrase are generated by the framework for each group
 *   ([com.andrerinas.openheadunit.connection.WifiDirectManager] reads them back off `WifiP2pGroup`),
 *   so a name the user typed could not match the group the phone is told to join, and no setting for
 *   one exists.
 *
 * Pure, because the devices this is for are not devices available to test with. The outcome table in
 * `NativeCredentialsPreflightPolicyTest` is the only place the behaviour is checked.
 */
object NativeCredentialsPreflightPolicy {

    fun evaluate(transport: NativeTransport, probe: PreflightProbe): PreflightReport {
        val verdicts = LinkedHashMap<CredentialField, FieldVerdict>()

        if (transport == NativeTransport.HOTSPOT) {
            verdicts[CredentialField.HOTSPOT_NAME] = nameVerdict(probe)
            verdicts[CredentialField.HOTSPOT_PASSWORD] = passwordVerdict(probe)
        }
        verdicts[CredentialField.BSSID] = bssidVerdict(probe)

        // Location off is only worth raising when it would actually change something. Where the
        // BSSID is already in hand it changes nothing, and telling somebody to turn on location for
        // a value we already have is how a check trains people to dismiss it.
        val bssidSettled = verdicts[CredentialField.BSSID] == FieldVerdict.SUPPLIED_BY_USER ||
            verdicts[CredentialField.BSSID] == FieldVerdict.AVAILABLE_FROM_DEVICE
        val locationOff = probe.locationServicesEnabled == false && !bssidSettled

        val mustEnter = verdicts.filterValues { it == FieldVerdict.MUST_BE_ENTERED }.keys
            // Asking for a MAC is the wrong remedy while location is off, so it is dropped rather
            // than listed alongside the toggle that would supply it automatically.
            .filterNot { locationOff && it == CredentialField.BSSID }

        return PreflightReport(
            verdicts = verdicts,
            mustEnter = mustEnter,
            locationServicesOff = locationOff
        )
    }

    private fun nameVerdict(probe: PreflightProbe): FieldVerdict = when {
        probe.manualSsid.isNotEmpty() -> FieldVerdict.SUPPLIED_BY_USER
        !probe.systemConfig?.ssid.isNullOrEmpty() -> FieldVerdict.AVAILABLE_FROM_DEVICE
        else -> FieldVerdict.MUST_BE_ENTERED
    }

    /**
     * The passphrase, including the trap [SoftApCredentialsPolicy.resolve] documents.
     *
     * A user who names the network and leaves the password blank gets an *empty* passphrase, not the
     * device's: once a manual name is set the system configuration is never read, so there is
     * nothing to fall back to. That sends an open network, which the phone refuses, and it is the
     * one case where a half-finished override is worse than none - so it is asked for here rather
     * than left to a warning at connect time.
     */
    private fun passwordVerdict(probe: PreflightProbe): FieldVerdict = when {
        probe.manualPassword.isNotEmpty() -> FieldVerdict.SUPPLIED_BY_USER
        probe.manualSsid.isNotEmpty() -> FieldVerdict.MUST_BE_ENTERED
        // The passphrase itself, not merely a non-null configuration. The pre-Q read in
        // HotspotConfigReader returns whatever the fields held, with no emptiness check, so a
        // blanked configuration arrives as a present pair of empty strings. Reading that as "the
        // device has a passphrase" asked only for the name, and a manual name makes
        // SoftApCredentialsPolicy.resolve stop consulting the device — which is precisely the
        // open-network trap the branch above exists to prevent. It also catches a hotspot that
        // genuinely has no passphrase, which Android Auto will not join either.
        !probe.systemConfig?.passphrase.isNullOrEmpty() -> FieldVerdict.AVAILABLE_FROM_DEVICE
        else -> FieldVerdict.MUST_BE_ENTERED
    }

    private fun bssidVerdict(probe: PreflightProbe): FieldVerdict = when {
        // The same predicate the handshake will apply, so this cannot pass something Type 3 rejects.
        SoftApBssidPolicy.isUsable(probe.staticBssid) -> FieldVerdict.SUPPLIED_BY_USER
        SoftApBssidPolicy.isUsable(probe.probedBssid) -> FieldVerdict.AVAILABLE_FROM_DEVICE
        probe.bssidProbeConclusive -> FieldVerdict.MUST_BE_ENTERED
        else -> FieldVerdict.UNKNOWN
    }
}
