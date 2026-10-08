package com.andrerinas.openheadunit.utils

/**
 * What to show in the Bluetooth address field before the user has typed anything.
 *
 * The address is how the phone is told where to connect hands-free, and Android Auto disables
 * telephony until that link is up - so a blank field is why "calls stay on the phone" on a head
 * unit that has a perfectly good Bluetooth car kit. BluetoothHelper has resolved the real address
 * for years and only ever used it to build description strings.
 *
 * Never overwrites what the user typed: a hand-entered address is usually there because the
 * detected one was wrong.
 *
 * Pure: no Android.
 */
object BluetoothAddressSeedPolicy {

    /** The field's default up to 1.14.2: one developer's address, which a Save wrote to disk. */
    const val LEGACY_DEFAULT = "40:EF:4C:A3:CB:A5"

    fun isLegacyDefault(stored: String?): Boolean = stored?.trim().equals(LEGACY_DEFAULT, ignoreCase = true)

    fun seed(stored: String?, detected: String?): String {
        val kept = stored?.trim().orEmpty()
        if (kept.isNotEmpty()) return kept
        return detected?.trim().orEmpty()
    }
}
