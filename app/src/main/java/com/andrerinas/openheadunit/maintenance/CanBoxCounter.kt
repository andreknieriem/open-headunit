package com.andrerinas.openheadunit.maintenance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Counts the mileage from the CAN box on units where it can be read, next to the GPS tracker.
 * AapService owns it like the tracker. Only Reglink units for now; another maker is another
 * [CanBoxSource] picked in [sourceFor].
 */
class CanBoxCounter(private val context: Context, private val store: MaintenanceStore) {

    private var source: CanBoxSource? = null
    private var loggedAccess: DistanceSourcePolicy.Access? = null
    private var firstReading = true

    /** Starts reading the box when it can. Harmless to call again. */
    fun start() {
        val access = access(context)
        store.boxAccess = access
        if (access != loggedAccess) {
            loggedAccess = access
            AppLog.i("$TAG: CAN box access is $access")
        }
        if (access != DistanceSourcePolicy.Access.READY || source != null) return
        val box = sourceFor(context) ?: return
        source = box
        firstReading = true
        store.boxStarted(SystemClock.elapsedRealtime())
        box.start(::onReading)
        AppLog.i("$TAG: reading the ${box.name} CAN box for the mileage")
    }

    fun stop() {
        val box = source ?: return
        source = null
        box.stop()
        store.boxStopped()
        store.flush()
        AppLog.i("$TAG: stopped reading the ${box.name} CAN box, mileage saved")
    }

    private fun onReading(reading: TripReading) {
        if (source == null) return
        val step = store.addBoxReading(reading, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        val first = firstReading
        firstReading = false
        when (step.kind) {
            CanBoxDistancePolicy.Kind.SAME, CanBoxDistancePolicy.Kind.ADDED -> if (first) {
                AppLog.i("$TAG: first box reading of this run added ${Math.round(step.addMeters)} m " +
                    "(${Math.round(step.boxMeters)} m measured since the last reading kept)")
            }
            CanBoxDistancePolicy.Kind.FIRST ->
                AppLog.i("$TAG: first box reading kept as the starting point, nothing added")
            CanBoxDistancePolicy.Kind.COUNTED_BY_OTHER ->
                AppLog.i("$TAG: a trip counter went back (reset or rollover); the other one counted ${Math.round(step.addMeters)} m")
            CanBoxDistancePolicy.Kind.RESET ->
                AppLog.i("$TAG: both trip counters went back; added only the ${Math.round(step.addMeters)} m since the reset")
            CanBoxDistancePolicy.Kind.UNIT_CHANGED ->
                AppLog.i("$TAG: the dashboard switched to ${if (reading.miles) "miles" else "km"}; starting over from this reading")
            CanBoxDistancePolicy.Kind.IMPLAUSIBLE ->
                AppLog.w("$TAG: the box jumped ${Math.round(step.boxMeters)} m, more than the car could drive; " +
                    "not counted, starting over from this reading")
        }
    }

    companion object {
        private const val TAG = "CanBoxCounter"

        @Volatile
        private var reglinkUnit: Boolean? = null

        /** The process's groups are fixed at its start, so this is read once. */
        private val inLogGroup: Boolean by lazy { ReglinkLogSource.inLogGroup() }

        private fun isReglinkUnit(context: Context): Boolean =
            reglinkUnit ?: ReglinkLogSource.isReglinkUnit(context).also { reglinkUnit = it }

        private fun sourceFor(context: Context): CanBoxSource? =
            if (isReglinkUnit(context)) ReglinkLogSource() else null

        fun access(context: Context): DistanceSourcePolicy.Access {
            val boxUnit = isReglinkUnit(context)
            if (!boxUnit) return DistanceSourcePolicy.Access.NO_BOX
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) ==
                PackageManager.PERMISSION_GRANTED
            return DistanceSourcePolicy.access(Build.VERSION.SDK_INT, boxUnit, granted, granted && inLogGroup)
        }

        /** The command that grants the log permission, for a computer or this unit's own ADB. */
        fun grantCommand(context: Context): String =
            "pm grant ${context.packageName} ${Manifest.permission.READ_LOGS}"
    }
}
