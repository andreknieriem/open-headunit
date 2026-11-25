package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * One network interface, reduced to the four things that decide whether it is the head unit's
 * access point. Built from `java.net.NetworkInterface` by the caller so the choice itself stays
 * testable without a device.
 */
data class ApInterfaceCandidate(
    val name: String,
    val isLoopback: Boolean,
    val isUp: Boolean,
    /** The interface's own site-local IPv4 address, or null if it has none. */
    val siteLocalIpv4: String?
)

/**
 * Which network interface is the hotspot we are hosting.
 *
 * No public API answers this — `WifiManager` will not give an ordinary app its own soft AP's
 * address — so the interface is recognised from outside, by shape as well as name: vendors are
 * inconsistent, and ZLink's head unit serves Android Auto off a plain `wlan0`. Getting it wrong
 * hands the phone an address it cannot reach.
 */
object SoftApNetworkPolicy {

    /**
     * Names we prefer, best first. An interface that matches none of these is still eligible —
     * a vendor can call it anything — it just loses to one that does.
     *
     * MediaTek's `ra0`/`rai0` access points are deliberately absent. Two letters is too loose a
     * prefix to match on, and they do not need to be here: an unrecognised name is still eligible,
     * so once `apcli*` is excluded below, `ra0` wins on an MTK unit by being what is left.
     */
    private val PREFERRED_PREFIXES = listOf("ap", "swlan", "softap", "wlan")

    /**
     * Interfaces that can hold a site-local IPv4 and are never an access point.
     *
     * `p2p-*` is the WiFi Direct transport, which has its own credential source. `tun*`/`dummy*`
     * is this app's own VPN service, which parks a 10.x address on the device while it runs.
     *
     * `apcli*` and `sta*` are stations wearing an access point's name. On MediaTek and Ralink
     * drivers `apcli0` is the AP *client* — the interface that joins someone else's network,
     * configured `mode: sta` — while `ra0` is the access point; without this exclusion `apcli0`
     * would rank best of all, since it starts with "ap". `sta*` is the same trap spelled the
     * obvious way, and is what aa-proxy-rs names its own managed station interface.
     *
     * `seth_lte*` is a Unisoc cellular bridge. It is up and holds a private address the moment
     * there is signal, so with the real access point down it was the only candidate left and got
     * advertised as one — the right network name against an address no phone could reach.
     *
     * `rmnet*` is the Qualcomm equivalent, and fails the same way: the phone is handed a cellular
     * address with no BSSID behind it, which it refuses at once.
     *
     * This list is a guess about which unknown interfaces are *not* access points, and it has
     * needed extending four times. [NativeCredentialsPolicy.shouldPublishCredentials] is the
     * defence that does not depend on knowing every name in advance.
     */
    private val EXCLUDED_PREFIXES = listOf("p2p-", "tun", "dummy", "apcli", "sta", "seth_lte", "rmnet")

    /**
     * The interface most likely to be our access point, or null if none qualifies. Must hold a
     * site-local IPv4 — a running AP has one, and it is the address the phone is given — and be up,
     * or failing that be an access point still waiting for its first station
     * ([isWaitingForFirstStation]).
     *
     * [stationIpv4] is this device's address on the WiFi network it is *joined to*, if any. It is
     * the one thing that reliably separates the access point from the station interface: on a real
     * head unit the AP runs as `wlan2` while `wlan0` is the station, and the two are
     * indistinguishable by name. Pass null when the device is not associated with anything.
     */
    fun pickApInterface(
        candidates: List<ApInterfaceCandidate>,
        stationIpv4: String? = null
    ): ApInterfaceCandidate? = eligible(candidates, stationIpv4).minByOrNull { rank(it.name) }
        ?: candidates.filter { isWaitingForFirstStation(it, stationIpv4) }.minByOrNull { rank(it.name) }

    /**
     * Everything [pickApInterface] considered, for a caller that wants to say so. More than one
     * survivor means the choice came down to the name, which is a guess — worth a log line, since
     * picking wrong hands the phone an address it cannot reach and nothing else reports it.
     */
    fun eligible(
        candidates: List<ApInterfaceCandidate>,
        stationIpv4: String? = null
    ): List<ApInterfaceCandidate> = candidates.filter { isEligible(it, stationIpv4) }

    /**
     * Whether [candidate] is an access point this device is hosting.
     *
     * Same rules as [pickApInterface] applied to one interface, for callers asking "is there an
     * access point at all" rather than "which of these is it".
     */
    fun isApHost(candidate: ApInterfaceCandidate, stationIpv4: String? = null): Boolean =
        isEligible(candidate, stationIpv4) || isWaitingForFirstStation(candidate, stationIpv4)

    /**
     * An access point that is configured but has no station yet, on drivers that raise the link only
     * when one associates.
     *
     * `NetworkInterface.isUp` means up *and running*. On a Unisoc UIS8581 head unit (Android 10)
     * `wlan0` holds 192.168.43.1 from the moment hostapd reports AP-ENABLED, but is not running
     * until the first station joins. The phone joins only after it was given the credentials, and
     * the credentials waited for this interface: nothing connected until some other device joined
     * the hotspot. So an interface that carries a known access point name, holds a site-local
     * address and is merely not running counts, as a second choice behind any that is up.
     */
    private fun isWaitingForFirstStation(candidate: ApInterfaceCandidate, stationIpv4: String?): Boolean =
        !candidate.isUp && !candidate.isLoopback && candidate.siteLocalIpv4 != null &&
            !isExcluded(candidate.name) && rank(candidate.name) < PREFERRED_PREFIXES.size &&
            !(stationIpv4 != null && candidate.siteLocalIpv4 == stationIpv4)

    private fun isEligible(candidate: ApInterfaceCandidate, stationIpv4: String?): Boolean =
        candidate.isUp && !candidate.isLoopback && candidate.siteLocalIpv4 != null &&
            !isExcluded(candidate.name) &&
            !(stationIpv4 != null && candidate.siteLocalIpv4 == stationIpv4)

    private fun isExcluded(name: String): Boolean =
        name.lowercase().let { lower -> EXCLUDED_PREFIXES.any { lower.startsWith(it) } }

    /** Lower is better; anything unrecognised sorts last but is still usable. */
    private fun rank(name: String): Int {
        val lower = name.lowercase()
        val index = PREFERRED_PREFIXES.indexOfFirst { lower.startsWith(it) }
        return if (index >= 0) index else PREFERRED_PREFIXES.size
    }
}
