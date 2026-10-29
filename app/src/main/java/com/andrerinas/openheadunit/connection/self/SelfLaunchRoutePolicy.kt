package com.andrerinas.openheadunit.connection.self

/** The legacy launch needs a non-null `activeNetwork`, so it needs the tun; the 5277 route does not. */
object SelfLaunchRoutePolicy {

    /**
     * Null when Android Auto is not installed: there is nothing to launch, so Self Mode refuses
     * before any launcher. An unreadable version stays [SelfLaunchPath.LEGACY], which keeps the VPN.
     */
    fun pathFor(aaInstalled: Boolean, aaVersionName: String?): SelfLaunchPath? {
        if (!aaInstalled) return null
        val parts = (aaVersionName ?: "").split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return if (major > 17 || (major == 17 && minor >= 4)) SelfLaunchPath.HEADUNIT_SERVER
        else SelfLaunchPath.LEGACY
    }

    fun needsDummyVpn(path: SelfLaunchPath?, offline: Boolean, vpnAvailable: Boolean): Boolean =
        path == SelfLaunchPath.LEGACY && offline && vpnAvailable
}
