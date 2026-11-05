package com.andrerinas.openheadunit.maintenance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Counts the mileage from this unit's GPS while the app is alive. AapService owns it, so it runs
 * inside the foreground service the app already has, with no notification of its own.
 *
 * It subscribes on its own rather than through GpsLocation, which only runs with a projection
 * session and resends stale fixes for Android Auto's sake. Two listeners on the same provider
 * cost nothing extra: the chip is already running.
 */
class MaintenanceTracker(
    private val context: Context,
    private val store: MaintenanceStore,
) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var listening = false
    private var reference: OdometerStepPolicy.Fix? = null
    private var trip = TripStopPolicy.State()

    /** The first good fix of this run has been compared with where the last run ended. */
    private var positionChecked = false

    /**
     * Starts counting when it can. Harmless to call again, which is how a permission granted
     * later takes effect without restarting the service.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (listening) return
        if (!hasPermission(context)) {
            AppLog.i("$TAG: no precise location permission, the mileage is paused (date based items still work)")
            return
        }
        val manager = locationManager ?: return
        try {
            // Time only, no distance gate, the same 1 Hz subscription GpsLocation makes: the step
            // policy needs the parked fixes too, to tell drift from driving.
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper()
            )
            listening = true
            reference = null
            trip = TripStopPolicy.State()
            positionChecked = false
            AppLog.i("$TAG: counting the mileage, GPS enabled=${manager.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
        } catch (e: Exception) {
            // A unit with no GPS provider, or one that refuses the subscription.
            AppLog.w("$TAG: could not subscribe to GPS, the mileage is paused: ${e.message}")
        }
    }

    // Lint wants a permission check here only up to API 22, where location is granted at install,
    // and this only undoes a subscription start() made with the permission.
    @SuppressLint("MissingPermission")
    fun stop() {
        if (!listening) return
        listening = false
        locationManager?.removeUpdates(this)
        savePosition()
        store.flush()
        AppLog.i("$TAG: stopped counting, mileage saved")
    }

    override fun onLocationChanged(location: Location) {
        // A callback already queued when stop() ran must not count after the save.
        if (!listening) return
        val fix = OdometerStepPolicy.Fix(
            latitude = location.latitude,
            longitude = location.longitude,
            atMs = SystemClock.elapsedRealtime(),
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
        )
        val step = OdometerStepPolicy.step(reference, fix)
        reference = step.reference
        if (step.kind == OdometerStepPolicy.Kind.INACCURATE) return
        store.lastFixElapsedMs = fix.atMs

        if (!positionChecked) {
            positionChecked = true
            checkMissedDriving(fix)
        }
        when (step.kind) {
            OdometerStepPolicy.Kind.BRIDGED ->
                AppLog.i("$TAG: signal back after a gap, bridged ${Math.round(step.addMeters)} m")
            OdometerStepPolicy.Kind.JUMP ->
                AppLog.i("$TAG: a jump no car makes, or a gap too long to trust; restarting from this fix")
            else -> Unit
        }

        if (store.addDistance(step.addMeters)) savePosition()
        val stop = TripStopPolicy.onFix(trip, step.addMeters, fix.speedMps, fix.atMs)
        trip = stop.state
        if (stop.stopped) {
            savePosition()
            store.flush()
            AppLog.i("$TAG: the car stopped, mileage saved")
        }
    }

    // Before API 30 these are abstract, and a missing override is an AbstractMethodError there.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        AppLog.i("$TAG: $provider enabled")
    }

    override fun onProviderDisabled(provider: String) {
        AppLog.i("$TAG: $provider disabled, nothing to count until it is back")
    }

    private fun checkMissedDriving(fix: OdometerStepPolicy.Fix) {
        val last = store.lastPosition ?: return
        val missed = PositionJumpPolicy.missedMeters(last.first, last.second, fix.latitude, fix.longitude) ?: return
        store.noteMissedDriving(Math.round(missed))
    }

    private fun savePosition() {
        val at = reference ?: return
        store.lastPosition = at.latitude to at.longitude
    }

    companion object {
        private const val TAG = "MaintenanceTracker"
        private const val UPDATE_INTERVAL_MS = 1000L

        /** The mileage needs precise location; approximate is a few hundred meters off. */
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
