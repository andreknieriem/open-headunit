package com.andrerinas.openheadunit.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.contract.LocationUpdateIntent
import com.andrerinas.openheadunit.utils.AppLog

class GpsLocation constructor(private val context: Context): LocationListener {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val settings by lazy { App.provide(context).settings }
    private var requested: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    // Only ever set from onLocationChanged, so a resend can never mislabel a network/passive
    // fallback fix as this head unit's real GPS.
    private var lastGpsFix: Location? = null

    // Taken from the monotonic clock rather than Location.time, because a resend deliberately
    // bumps that field and would otherwise keep declaring itself fresh.
    private var lastGpsFixAtMs: Long = 0L

    // When the subscription was made, so the first fix can be reported with how long it took.
    private var subscribedAtMs: Long = 0L

    private var staleFixReported = false

    // Android Auto is reported to prefer the car's GPS but fall back to the phone's own once the
    // car's signal looks interrupted, so a head unit that stops emitting looks like a head unit
    // with no GPS. The subscription below asks for fixes on time alone, which keeps a parked unit
    // emitting, but not every chip obliges - resend the last real fix on a timer as a backstop, the
    // way a real GPS chip keeps outputting NMEA sentences whether or not the car moves.
    //
    // A resend only ever stands in for a beat the chip actually missed: while real fixes arrive on
    // schedule it does nothing, so it duplicates nothing. And it stops once the last real fix is
    // older than RESEND_MAX_AGE_MS - without that bound a GPS that genuinely dies mid-drive
    // (tunnel, garage, antenna knocked loose) would be indistinguishable from a parked car forever,
    // and Android Auto would stay pinned to the last position instead of falling back to the
    // phone. A wrong position while moving is worse than the stall this resend exists to prevent.
    private val periodicResend = object : Runnable {
        override fun run() {
            val fix = lastGpsFix
            if (fix != null && settings.useGpsForNavigation) {
                val ageMs = SystemClock.elapsedRealtime() - lastGpsFixAtMs
                if (ageMs > RESEND_MAX_AGE_MS) {
                    if (!staleFixReported) {
                        staleFixReported = true
                        AppLog.i("GpsLocation: no GPS fix for ${ageMs / 1000}s, stopping resend so the phone can fall back to its own location")
                    }
                } else if (ageMs >= RESEND_INTERVAL_MS) {
                    val refreshed = Location(fix).apply {
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    context.sendBroadcast(LocationUpdateIntent(refreshed).setPackage(context.packageName))
                }
                // ageMs < RESEND_INTERVAL_MS: a real fix just went out; nothing to stand in for.
            }
            handler.postDelayed(this, RESEND_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (requested) {
            return
        }
        AppLog.i("Request location updates")
        val hasPermission = PermissionChecker.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED
        val providerEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (hasPermission && providerEnabled) {
            // No minDistance: Android ANDs the time and distance gates, so any non-zero distance
            // stops a stationary head unit after its very first fix. Asking on time alone is what
            // a real head unit does, and it costs nothing extra - the chip is already running.
            //
            // 1Hz is the automotive GPS norm and what Android Auto's navigation is tuned for.
            // The old 5m gate delivered a fix every ~0.4-0.6s at driving speed, so this is fewer
            // updates while moving, not more; the parked case goes from one fix ever to a steady
            // stream, and the fan-out cost of that is paid at the sinks, not by starving nav.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, this
            )
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            AppLog.i("Last known location:  ${location?.toString() ?: "Unknown"}")
            requested = true
            subscribedAtMs = SystemClock.elapsedRealtime()
            handler.postDelayed(periodicResend, RESEND_INTERVAL_MS)
        } else {
            AppLog.i("GpsLocation: not requesting updates, ACCESS_FINE_LOCATION granted=$hasPermission GPS_PROVIDER enabled=$providerEnabled")
        }
    }

    override fun onLocationChanged(location: Location) {
        // A callback dispatched before removeUpdates() took effect must not repopulate state a
        // stop() just cleared - the next start() would inherit a fix from the previous session.
        if (!requested) {
            return
        }
        // Once per subscription, and carrying no position: without it a head unit whose antenna is
        // not connected is indistinguishable from a working one in a log. It passes every gate in
        // start() - the provider exists and is enabled - and then simply never locks, so the only
        // other evidence is the absence of lines that were never going to appear. How long the
        // lock took separates a healthy chip from one that is barely hearing the satellites.
        // lastGpsFix is the flag: it is null exactly between start() and the first fix.
        if (lastGpsFix == null) {
            AppLog.i("GpsLocation: first fix after ${(SystemClock.elapsedRealtime() - subscribedAtMs) / 1000}s")
        }
        // Cadence only, never a position: this is what lets a parked verbose log show whether the
        // chip honours a time-only subscription (and so whether the resend backstop is needed).
        if (AppLog.LOG_VERBOSE) {
            AppLog.v("GpsLocation: fix received")
        }
        lastGpsFix = location
        lastGpsFixAtMs = SystemClock.elapsedRealtime()
        // Reset here rather than in the resend's fresh branch: on a healthy chip that branch never
        // runs, and the flag left latched would silence the stale line for every outage after the
        // first one in a session.
        staleFixReported = false
        LocationHolder.update(location)
        context.sendBroadcast(LocationUpdateIntent(location).setPackage(context.packageName))
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        AppLog.i("$provider: $status")
    }

    override fun onProviderEnabled(provider: String) {
        AppLog.i(provider)
    }

    override fun onProviderDisabled(provider: String) {
        AppLog.i(provider)
    }

    fun stop() {
        AppLog.i("Remove location updates")
        requested = false
        // Every field start() depends on goes back to its initial value, so a re-armed instance
        // cannot resend a fix acquired during the previous connection.
        lastGpsFix = null
        lastGpsFixAtMs = 0L
        subscribedAtMs = 0L
        staleFixReported = false
        locationManager.removeUpdates(this)
        handler.removeCallbacks(periodicResend)
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1000L
        const val RESEND_INTERVAL_MS = 3000L

        /** How stale the last real fix may be before a resend stops standing in for it. */
        const val RESEND_MAX_AGE_MS = 60_000L
    }
}
