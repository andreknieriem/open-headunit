package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.MacAddressPolicy

/**
 * Picks the BSSID to advertise for our own access point, best source first.
 *
 * A chain rather than one source because `NetworkInterface.getHardwareAddress()` returns null or a
 * placeholder on plenty of devices since Android 6.0. What answers instead varies by unit:
 * `/sys/class/net/<iface>/address` on most head units, and on a device that blocks even that, the
 * address the kernel built the interface's own IPv6 link-local from. Pure and tested: case,
 * placeholder detection and empty-vs-null are easy to get subtly wrong and impossible to check by
 * reading a log.
 */
object SoftApBssidPolicy {

    /**
     * The first usable address of [staticOverride], [shellMac] and [hardwareAddress], normalised to
     * colon-separated upper case, or "" if none yields one. What to do about "" differs by
     * transport — see [NativeCredentialsPolicy].
     */
    fun choose(staticOverride: String?, shellMac: String?, hardwareAddress: String?): String =
        choose(staticOverride, listOf(shellMac, hardwareAddress))

    /**
     * The same rule over an arbitrary list of detected addresses, for a route with more than two of
     * them. [staticOverride] still outranks every one: a hand-typed address is a specific claim.
     */
    fun choose(staticOverride: String?, detected: List<String?>): String =
        MacAddressPolicy.firstUsable(listOf(staticOverride) + detected).orEmpty()

    /**
     * Whether [mac] is a real address.
     *
     * [BUG_FIX] Checks the shape, not a list of known non-addresses. The setting this reads first
     * stores the string "0" when it is unset, which is not MAC-shaped but passed the old
     * placeholder check, won the chain over the real interface MAC, and was published verbatim —
     * the phone then rejected the credentials on every retry. A free-text field can produce any
     * number of such values, so validate what an address looks like instead.
     */
    fun isUsable(mac: String?): Boolean = MacAddressPolicy.isUsable(mac)

    /**
     * The same chain with the hand-typed address **last**, which is where it belongs.
     *
     * Where a rung answers, that address is this interface's; one typed by hand can only match it
     * or be wrong, and a wrong one is handed to the phone as a network it will never find.
     */
    fun chooseDetectedFirst(detected: List<String?>, staticOverride: String?): String =
        choose(null, detected + listOf(staticOverride))

    /** True when [staticOverride] is what [chooseDetectedFirst] fell back on, nothing having read one. */
    fun overrideAnswered(detected: List<String?>, staticOverride: String?): Boolean =
        isUsable(staticOverride) && choose(null, detected).isEmpty()

    /**
     * Whether [resolvedBssid] shows this device read its own address, rather than repeating what
     * the user typed.
     *
     * Both routes now ask the hardware first and fall back to the override, so a resolved address
     * that equals the override means the rungs came back empty and the typed value answered. The
     * record `ConnectionIssue.BSSID_UNAVAILABLE` describes therefore still stands behind one, and
     * `ConnectionIssueBannerPolicy.remedyApplied` is what keeps it off the screen meanwhile.
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
        return MacAddressPolicy.parse(resolvedBssid) != MacAddressPolicy.parse(staticOverride)
    }

}
