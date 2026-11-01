package com.andrerinas.openheadunit.connection.usb

import java.util.Locale

/**
 * The user's "never touch this device" list: how a device is named in it, and the membership test.
 *
 * The key is the manufacturer and product strings rather than VID:PID, because a phone's VID:PID
 * changes with its USB mode while its strings do not. Keyed on the numbers, a blacklisted phone
 * walks straight back in on the next mode, and one that arrives already in accessory mode is never
 * matched at all. A device with no string descriptors falls back to VID:PID, which is all it had.
 *
 * The format is pinned because three places have to agree on it: the list UI writes it, the
 * device-protected mirror copies it, and a settings export hands it to the user as an editable file.
 */
object UsbBlacklistPolicy {

    private const val NAME_PREFIX = "name:"
    private const val VID_PID_PREFIX = "vidpid:"

    /**
     * The name the device is stored under. Pass null strings when they are unreadable, which is
     * every device below API 21 and any device that ships no string descriptors.
     */
    fun key(manufacturer: String?, product: String?, vendorId: Int, productId: Int): String {
        val name = listOfNotNull(manufacturer, product)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return if (name.isNotEmpty()) NAME_PREFIX + name.lowercase(Locale.US)
        else vidPidKey(vendorId, productId)
    }

    /** A VID:PID key matches one USB mode only, so a phone that switches modes is not held by it. */
    fun bindsOneMode(key: String): Boolean = key.startsWith(VID_PID_PREFIX)

    /** What the USB list tells the user after it blacklists a device. */
    enum class Note { ONE_MODE, ANDROID_ASKS }

    /**
     * Android's own dialog follows the manifest filter, which the blacklist cannot change; only a
     * default the user ticks there silences it. A device the filter misses gets no dialog at all.
     */
    fun notesOnAdd(key: String, filterMatches: Boolean): List<Note> = listOfNotNull(
        Note.ONE_MODE.takeIf { bindsOneMode(key) },
        Note.ANDROID_ASKS.takeIf { filterMatches },
    )

    fun vidPidKey(vendorId: Int, productId: Int): String =
        String.format(Locale.US, "%s%04x:%04x", VID_PID_PREFIX, vendorId, productId)

    /**
     * Matches on the device's own key, and also on its bare VID:PID. The second is for entries
     * written before the key carried a prefix, so an old list keeps working until the user redoes
     * it rather than failing silently.
     */
    fun isBlacklisted(blacklist: Set<String>, key: String, vendorId: Int, productId: Int): Boolean {
        if (blacklist.isEmpty()) return false
        val legacy = String.format(Locale.US, "%04x:%04x", vendorId, productId)
        val wanted = setOf(key, vidPidKey(vendorId, productId), legacy)
        return normalise(blacklist).any { it in wanted }
    }

    fun add(blacklist: Set<String>, key: String): Set<String> = normalise(blacklist) + key

    fun remove(blacklist: Set<String>, key: String, vendorId: Int, productId: Int): Set<String> {
        val legacy = String.format(Locale.US, "%04x:%04x", vendorId, productId)
        val gone = setOf(key, vidPidKey(vendorId, productId), legacy)
        return normalise(blacklist).filterTo(LinkedHashSet()) { it !in gone }
    }

    /** Writes the stored set back in the canonical form, so a rewrite fixes what a read tolerates. */
    fun normalise(blacklist: Set<String>): Set<String> =
        blacklist.mapNotNullTo(LinkedHashSet()) {
            it.trim().lowercase(Locale.US).takeIf(String::isNotEmpty)
        }
}
