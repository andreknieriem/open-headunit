package com.andrerinas.openheadunit.automation

/**
 * Guards what a configuring command may hand out or write. "Allow external configuration" is one
 * switch that stays on once turned on, so it decides whether the door opens and this decides what
 * may go through it.
 */
object AutomationOutputPolicy {

    /**
     * Settings withheld from an exported copy: the AP and WiFi Direct passphrases in clear text, and
     * the keys naming the car's network and the phones paired to it. An export is `prefs.all`, so a
     * credential added anywhere reaches it unless it is named here.
     */
    val WITHHELD_KEYS = setOf(
        "hotspot-password",
        "hotspot-ssid",
        "wifi-direct-group-name",
        "wifi-direct-group-passphrase",
        // Withheld with the pair rather than on its own merit: restored alone it would mark an
        // app-drawn pair as one the user typed, and the row would say so.
        "wifi-direct-identity-user-set",
        "wifi-direct-readback-name",
        "wifi-direct-readback-passphrase",
        "wifi-direct-readback-bssid",
        "auto-start-wifi-ssid",
        "auto-start-bt-macs",
        "auto-start-bt-name",
        "auto-disconnect-bt-macs",
        "native-poke-bt-macs",
        "static-bssid",
        "static-p2p-bssid"
    )

    /** Directory names an automation command may write into, relative to external storage. */
    private const val LOG_DIR = "OpenHeadunitLogs"

    /**
     * Whether [path] is somewhere a command may write. The write runs with the app's own privileges,
     * so an unconstrained path would let a caller put chosen bytes into private storage. Only places
     * an export can be collected from pass: [allowedRoots], or [LOG_DIR] under one.
     */
    fun mayWriteTo(
        path: String,
        allowedRoots: List<String>,
        externalStorageRoot: String? = null
    ): Boolean {
        val normalized = canonicalize(normalize(path) ?: return false, externalStorageRoot)
        // A path that climbs out of an allowed root must not pass because it started inside one.
        if (normalized.contains("/../") || normalized.endsWith("/..")) return false

        return allowedRoots.filter { it.isNotBlank() }.any { root ->
            val normalizedRoot = normalize(root)?.trimEnd('/') ?: return@any false
            normalized.startsWith("$normalizedRoot/")
        }
    }

    /** Symlinks Android keeps to primary external storage; a caller may reasonably use any of them. */
    val EXTERNAL_STORAGE_ALIASES = listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")

    /** The reason [mayWriteTo] gives when it refuses, phrased for whoever sent the command. */
    fun writeRefusedReason(path: String): String =
        "$path is not a directory this app may write to; use the app's Downloads or files directory"

    private fun normalize(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return null
        return trimmed.replace(Regex("/+"), "/")
    }

    /**
     * Rewrites a primary-external-storage symlink onto the real path the roots are expressed in.
     * Without this the everyday `/sdcard/Download/...` is refused, which fails safe but reads as
     * a bug to whoever typed it.
     */
    private fun canonicalize(path: String, externalStorageRoot: String?): String {
        val root = externalStorageRoot?.let { normalize(it) }?.trimEnd('/')
        if (root.isNullOrEmpty()) return path
        for (alias in EXTERNAL_STORAGE_ALIASES) {
            if (path == alias) return root
            if (path.startsWith("$alias/")) return root + path.removePrefix(alias)
        }
        return path
    }
}
