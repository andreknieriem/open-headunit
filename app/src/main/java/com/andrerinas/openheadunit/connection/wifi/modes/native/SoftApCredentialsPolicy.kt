package com.andrerinas.openheadunit.connection.wifi.modes.native

/** What one attempt at resolving this head unit's access-point credentials came to. */
enum class SoftApCredentialsAttempt {
    /** Everything needed is in hand; the caller can hand the credentials over. */
    PUBLISHED,

    /**
     * No access point yet, or the one we found is not on air. Polling is the right answer, and this
     * is the state auto-enable exists for.
     */
    NO_AP_YET,

    /**
     * There *is* an access point, and this device will not tell us its name. Polling can never fix
     * that: the read goes through a non-public API the device refuses, and it will refuse it again
     * next second. Only the manual override gets this route running here.
     */
    CONFIG_UNREADABLE
}

/** The name and passphrase to hand the phone, once [SoftApCredentialsPolicy] says there are any. */
data class SoftApCredentials(val ssid: String, val passphrase: String)

/**
 * Whether this head unit knows enough about its own access point to describe it to a phone, and if
 * not, whether waiting could ever change that.
 *
 * The distinction is the whole point. "No access point yet" is worth polling for and is what the
 * hotspot auto-enable exists to fix; "there is one and this device will not name it" is a dead end
 * that polling only hides. They were one boolean once, and on a locked-down unit the resolve loop
 * spent its entire budget re-reading a configuration the device had already refused — logging the
 * same line once a second, switching on an access point that was already up, and never reaching the
 * handshake's first message. From the car that looks like Bluetooth connecting and nothing else
 * happening.
 *
 * Pure so it can be tested at all: the device this matters on is not the device available to test
 * with, so the outcome table pinned in `SoftApCredentialsPolicyTest` is the only place the
 * behaviour is checked.
 */
object SoftApCredentialsPolicy {

    /**
     * @param manualSsid [com.andrerinas.openheadunit.utils.Settings.hotspotSsid] — the user's
     *   override, and the one thing that makes this route work on a device that hides its own
     *   configuration
     * @param manualPassphrase [com.andrerinas.openheadunit.utils.Settings.hotspotPassword]
     * @param systemConfig what the device says its own access point is named, or null where it will
     *   not say. The caller reads it only when [manualSsid] is empty — a name the user typed is a
     *   claim about their own hardware, and reading past it would cost a reflective call whose
     *   answer is already outranked.
     * @param siteLocalIpv4 the chosen interface's own address, or null if it has none yet
     */
    fun decide(
        manualSsid: String,
        manualPassphrase: String,
        systemConfig: SoftApCredentials?,
        siteLocalIpv4: String?
    ): SoftApCredentialsAttempt {
        if (siteLocalIpv4.isNullOrEmpty()) return SoftApCredentialsAttempt.NO_AP_YET
        return if (resolve(manualSsid, manualPassphrase, systemConfig).ssid.isEmpty()) {
            SoftApCredentialsAttempt.CONFIG_UNREADABLE
        } else {
            SoftApCredentialsAttempt.PUBLISHED
        }
    }

    /**
     * The credentials to send: each field the user's override where they set one, the device's own
     * configuration where they did not.
     *
     * Note what this means at the call site, since it is a real outcome and not an oversight. A user
     * who names the network but leaves the password blank gets an **empty passphrase**, not the
     * device's — the caller does not read the system configuration at all once a manual name is set,
     * so there is nothing to fall back to. Sending an open network is worse than sending nothing on
     * a protocol that refuses one, so the caller warns; it is not silently corrected here, because
     * pairing a name the user typed with a passphrase read off the device would hand the phone a
     * mismatched pair and fail with no line pointing at why.
     */
    fun resolve(
        manualSsid: String,
        manualPassphrase: String,
        systemConfig: SoftApCredentials?
    ): SoftApCredentials = SoftApCredentials(
        ssid = manualSsid.ifEmpty { systemConfig?.ssid.orEmpty() },
        passphrase = manualPassphrase.ifEmpty { systemConfig?.passphrase.orEmpty() }
    )
}
