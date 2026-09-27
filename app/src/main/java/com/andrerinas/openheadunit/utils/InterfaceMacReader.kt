package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.connection.wifi.MacAddressPolicy
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Reads an interface's MAC from outside the framework, for when
 * `NetworkInterface.getHardwareAddress()` returns null or a placeholder — the norm for ordinary
 * apps since Android 6.0.
 *
 * Separate from `WifiDirectManager.getMacFromShell()` on purpose: that one carries `[BUG_FIX]`
 * annotations and a chain tuned against specific hardware. Only the reading is duplicated now,
 * minus its leak - it never destroys the `ip link` process or closes its streams, and this runs
 * on a poll loop. What an address looks like is [MacAddressPolicy]'s, for both.
 *
 * [fromIpv6LinkLocal] is the exception, and both routes call it: it reads an address rather than a
 * hardware identity, so none of the restrictions the shell chain exists to work around apply to it.
 */
object InterfaceMacReader {

    /** The MAC of [iface] from sysfs, falling back to `ip link`, or null if neither yields a real one. */
    fun read(iface: String): String? = fromSysfs(iface) ?: fromIpLink(iface)

    /**
     * The MAC encoded in an interface's IPv6 link-local address, [iface] first and then any
     * interface [nameFilter] accepts. Every judgement lives in [Eui64BssidPolicy]; this only
     * enumerates. The WiFi Direct route passes a P2P-only filter, since a station's address
     * cannot be a group's.
     */
    fun fromIpv6LinkLocal(
        iface: String?,
        nameFilter: (String?) -> Boolean = Eui64BssidPolicy::looksLikeApOrP2p,
    ): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence().map { nic ->
            Eui64BssidPolicy.Candidate(
                name = nic.name.orEmpty(),
                linkLocalIpv6 = nic.inetAddresses.asSequence()
                    .filterIsInstance<Inet6Address>()
                    .filter { it.isLinkLocalAddress }
                    .map { it.address }
                    .toList()
            )
        }.toList()
        usableOrNull(Eui64BssidPolicy.choose(candidates, iface, nameFilter)?.mac)
    } catch (e: Exception) {
        AppLog.d("InterfaceMacReader: IPv6 link-local scan for ${iface ?: "any"} failed: ${e.message}")
        null
    }

    /** `/sys/class/net/<iface>/address`. Readable without root on most head units. */
    fun fromSysfs(iface: String): String? = try {
        val file = File("/sys/class/net/$iface/address")
        if (file.exists()) usableOrNull(file.readText()) else null
    } catch (e: Exception) {
        AppLog.d("InterfaceMacReader: sysfs read for $iface failed: ${e.message}")
        null
    }

    /** `ip link show <iface>`, parsed for its `link/ether` line. */
    fun fromIpLink(iface: String): String? {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("ip", "link", "show", iface))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val match = Regex("link/ether (([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})").find(output)
            usableOrNull(match?.groupValues?.get(1))
        } catch (e: Exception) {
            AppLog.d("InterfaceMacReader: 'ip link show $iface' failed: ${e.message}")
            null
        } finally {
            // An undestroyed Process keeps its file descriptors, and on some ROMs its zombie,
            // for the life of the app.
            try { process?.errorStream?.close() } catch (e: Exception) {}
            try { process?.outputStream?.close() } catch (e: Exception) {}
            try { process?.destroy() } catch (e: Exception) {}
        }
    }

    /** [mac] canonical, or null if it is blank, malformed or a masking placeholder. */
    private fun usableOrNull(mac: String?): String? = MacAddressPolicy.parse(mac)
}
