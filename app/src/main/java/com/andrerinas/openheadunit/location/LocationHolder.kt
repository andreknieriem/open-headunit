package com.andrerinas.openheadunit.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.PermissionChecker

/**
 * Process-wide, single source of truth for the device's most recent live location.
 *
 * It aggregates two sources, whichever is available:
 *  - the head unit's own GPS (fed from [GpsLocation.onLocationChanged]); and
 *  - the phone-forwarded GPS delivered during projection via LocationUpdateIntent
 *    (fed from AapBroadcastReceiver).
 *
 * Kept as a plain object (not tied to AppComponent) so it can be consulted from
 * short-lived receivers and from the locked-boot automation gate without touching
 * credential-encrypted preferences.
 */
object LocationHolder {

    /** Fixes older than this are treated as "no live location". */
    const val MAX_AGE_MS = 10 * 60 * 1000L

    @Volatile
    private var lastFix: Location? = null

    /** Feed a fresh fix from either GPS source. Keeps the newest by timestamp. */
    fun update(location: Location?) {
        if (location == null) return
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        val previous = lastFix
        if (previous == null || location.time >= previous.time) {
            lastFix = location
        }
    }

    /**
     * The freshest known fix within [maxAgeMs], or null if none / too stale.
     * When [context] is provided, also consults the OS last-known GPS fix.
     */
    fun currentLocation(context: Context? = null, maxAgeMs: Long = MAX_AGE_MS): Location? {
        val candidates = mutableListOf<Location>()
        lastFix?.let { candidates.add(it) }
        if (context != null) {
            bestEffortDeviceFix(context)?.let { candidates.add(it) }
        }
        val newest = candidates.maxByOrNull { it.time } ?: return null
        val age = System.currentTimeMillis() - newest.time
        return if (age in 0..maxAgeMs) newest else newest.takeIf { maxAgeMs <= 0 }
    }

    /**
     * The freshest fix from this head unit's own GPS chip, within [maxAgeMs] - the only kind of
     * fix that may be forwarded to the phone as the car's LOCATION sensor. A network- or
     * passive-derived position must never travel under that label: on a unit whose GPS never
     * locks it is often derived from the very hotspot or P2P group we created. Returns null when
     * the GPS has produced nothing recent, which is the correct answer - the phone then keeps
     * using its own location.
     */
    fun currentGpsFix(context: Context, maxAgeMs: Long = MAX_AGE_MS): Location? {
        val newest = listOfNotNull(
            lastFix?.takeIf { it.provider == LocationManager.GPS_PROVIDER },
            bestEffortFix(context, listOf(LocationManager.GPS_PROVIDER))
        ).maxByOrNull { it.time } ?: return null
        // <= rather than in 0..: system clocks on these units routinely lag GPS time, which makes
        // a live fix look future-dated. A negative age means clock skew, not staleness.
        return newest.takeIf { System.currentTimeMillis() - newest.time <= maxAgeMs }
    }

    /**
     * The best available fix for geofence containment tests, IGNORING age. A parked
     * head unit may not get a fresh fix for a long time, but its last known position
     * is still exactly where it is, so for "am I inside this area?" the newest known
     * fix (fed or OS last-known) is the right answer regardless of how old it is.
     */
    fun geofenceFix(context: Context): Location? {
        val candidates = mutableListOf<Location>()
        lastFix?.let { candidates.add(it) }
        bestEffortDeviceFix(context)?.let { candidates.add(it) }
        return candidates.maxByOrNull { it.time }
    }

    /**
     * A best-effort one-shot fix straight from the OS (getLastKnownLocation), used
     * by the automation gate before any connection exists. Returns null if no
     * permission / provider / cached fix is available.
     */
    fun bestEffortDeviceFix(context: Context): Location? = bestEffortFix(
        context,
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    )

    @SuppressLint("MissingPermission")
    private fun bestEffortFix(context: Context, providers: List<String>): Location? {
        if (PermissionChecker.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PermissionChecker.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            providers.mapNotNull { p ->
                try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
            }.maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }
}
