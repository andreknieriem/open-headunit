package com.andrerinas.openheadunit.thirdparty

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The whitelist of apps that receive the phone's navigation and media messages, and the album-art switch.
 * Kept in its own preferences file so it does not touch the app settings, their backup or their restore.
 * Empty by default: nothing is sent to anyone until the user adds an app.
 */
class ThirdPartyAccessStore(context: Context) {

    private val prefs = (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(): List<ThirdPartyForwardPolicy.Entry> =
        (prefs.getStringSet(KEY_ENTRIES, emptySet()) ?: emptySet())
            .mapNotNull { s -> s.split('|', limit = 2).takeIf { it.size == 2 }?.let { ThirdPartyForwardPolicy.Entry(it[0], it[1]) } }
            .sortedBy { it.pkg }

    fun add(entry: ThirdPartyForwardPolicy.Entry) {
        val set = (prefs.getStringSet(KEY_ENTRIES, emptySet()) ?: emptySet()).filterNot { it.startsWith(entry.pkg + "|") }.toMutableSet()
        set.add(entry.pkg + "|" + entry.certDigest)
        prefs.edit().putStringSet(KEY_ENTRIES, set).apply()
    }

    fun remove(pkg: String) {
        val set = (prefs.getStringSet(KEY_ENTRIES, emptySet()) ?: emptySet()).filterNot { it.startsWith("$pkg|") }.toSet()
        prefs.edit().putStringSet(KEY_ENTRIES, set).apply()
    }

    /** the whole list at once, as the settings screen saves it */
    fun replaceAll(entries: Collection<ThirdPartyForwardPolicy.Entry>) {
        prefs.edit().putStringSet(KEY_ENTRIES, entries.map { it.pkg + "|" + it.certDigest }.toSet()).apply()
    }

    /** off by default: only the text (title, artist, album) of the track is sent */
    var includeAlbumArt: Boolean
        get() = prefs.getBoolean(KEY_ALBUM_ART, false)
        set(value) = prefs.edit().putBoolean(KEY_ALBUM_ART, value).apply()

    companion object {
        private const val PREFS = "openhu_third_party"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_ALBUM_ART = "include_album_art"

        /** digest of the signing certificate(s) of an installed package, or null if it is not installed */
        @Suppress("DEPRECATION")
        fun installedDigest(pm: PackageManager, pkg: String): String? = try {
            val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                (info.signingInfo?.apkContentsSigners ?: return null).map { it.toByteArray() }
            } else {
                (pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: return null).map { it.toByteArray() }
            }
            if (certs.isEmpty()) null else ThirdPartyForwardPolicy.digestOf(certs)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}
