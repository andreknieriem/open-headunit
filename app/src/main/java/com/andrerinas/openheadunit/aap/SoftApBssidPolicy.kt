package com.andrerinas.openheadunit.aap

/**
 * Picks the BSSID to advertise for our own access point, best source first.
 *
 * A chain rather than one source because `NetworkInterface.getHardwareAddress()` returns null or a
 * placeholder on plenty of devices since Android 6.0, while `/sys/class/net/<iface>/address` still
 * works on most head units. Pure and tested: case, placeholder detection and empty-vs-null are
 * easy to get subtly wrong and impossible to check by reading a log.
 */
object SoftApBssidPolicy {

    /**
     * Android's masking placeholders. Note what is *not* here: anything merely beginning with
     * `02:`. That bit marks a locally-administered MAC and Android's own soft AP routinely uses a
     * randomised one, so rejecting the range would discard the BSSID this exists to find.
     */
    private val PLACEHOLDERS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /** Six hex pairs separated by colons or dashes. */
    private val MAC_SHAPE = Regex("^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){5}$")

    /**
     * The first usable address of [staticOverride], [shellMac] and [hardwareAddress], normalised to
     * colon-separated upper case, or "" if none yields one. What to do about "" differs by
     * transport — see [NativeCredentialsPolicy].
     */
    fun choose(staticOverride: String?, shellMac: String?, hardwareAddress: String?): String =
        listOf(staticOverride, shellMac, hardwareAddress)
            .firstOrNull { isUsable(it) }
            ?.let { normalise(it) }
            ?: ""

    /**
     * Whether [mac] is a real address.
     *
     * [BUG_FIX] Checks the shape, not a list of known non-addresses. The setting this reads first
     * stores the string "0" when it is unset, which is not MAC-shaped but passed the old
     * placeholder check, won the chain over the real interface MAC, and was published verbatim —
     * the phone then rejected the credentials on every retry. A free-text field can produce any
     * number of such values, so validate what an address looks like instead.
     */
    fun isUsable(mac: String?): Boolean {
        val trimmed = mac?.trim().orEmpty()
        if (!MAC_SHAPE.matches(trimmed)) return false
        return normalise(trimmed).lowercase() !in PLACEHOLDERS
    }

    /**
     * Whether [resolvedBssid] shows this device read its own address, rather than repeating what
     * the user typed.
     *
     * [choose] takes [staticOverride] ahead of every automatic source, and `WifiDirectManager`
     * skips its whole six-deep fallback chain when the override is usable, so a run behind one
     * never asks the hardware the question `ConnectionIssue.BSSID_UNAVAILABLE` is about. The record
     * therefore survives an override, and `ConnectionIssueBannerPolicy.remedyApplied` is what keeps
     * it off the screen meanwhile.
     *
     * Compared after normalisation rather than by identity, because the override is hand-typed:
     * dashes, lower case and stray spaces all name the same address, while the automatic rungs
     * reach the call site only upper-cased.
     *
     * The deliberate false negative: a user who typed this unit's *real* address gets no disproof.
     * Nothing is lost by that, and a test pins that the banner hides it either way.
     */
    fun disprovesBssidUnavailable(resolvedBssid: String?, staticOverride: String?): Boolean {
        if (!isUsable(resolvedBssid)) return false
        if (!isUsable(staticOverride)) return true
        return normalise(resolvedBssid.orEmpty()) != normalise(staticOverride.orEmpty())
    }

    /** Dashes to colons, upper case. Accepts either separator so a hand-typed address still works. */
    private fun normalise(mac: String): String = mac.trim().replace('-', ':').uppercase()
}
