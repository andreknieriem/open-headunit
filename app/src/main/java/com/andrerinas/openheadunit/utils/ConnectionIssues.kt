package com.andrerinas.openheadunit.utils

import android.content.Context
import com.andrerinas.openheadunit.App

/**
 * A reason the last connection attempt failed, in terms a user can act on.
 *
 * Only conditions the app can state with certainty are here. Each one is detected at exactly one
 * place in the connection path, and each has a remedy the user can reach.
 */
enum class ConnectionIssue {
    /**
     * The phone opened the Bluetooth channel, we wrote to it, and nothing ever came back.
     *
     * The one failure with no user-visible signal until now, and the most misread in the tracker:
     * a stack that accepts the write and puts nothing on the air logs every send exactly like a
     * healthy one, so the log reads as a session waiting for the user.
     */
    BLUETOOTH_SENT_NO_DATA,

    /** No usable BSSID, which the WiFi Direct route aborts on rather than sending. */
    BSSID_UNAVAILABLE,

    /** The device will not name its own access point, so the phone had nothing to join. */
    HOTSPOT_CONFIG_UNREADABLE
}

/** An issue that is currently true, and when it was last raised. */
data class StandingIssue(val issue: ConnectionIssue, val raisedAtEpochMs: Long)

/**
 * Where the stamps live. Behind an interface so the rules below can be tested without Android.
 *
 * Values are wall-clock milliseconds, 0 meaning "not standing".
 */
interface ConnectionIssueStore {
    fun read(issue: ConnectionIssue): Long
    fun write(issue: ConnectionIssue, atEpochMs: Long)
}

/**
 * Records why a connection attempt failed, so the reason can be shown long after the attempt.
 *
 * The verdicts behind these were already exact and already logged. What they were not was
 * *durable*: they are reached mid-drive, in a log nobody reads from the driver's seat, and by the
 * time the user is in front of the app with a keyboard nothing anywhere says what went wrong. This
 * is the copy that survives, and the main screen is where it is read.
 *
 * The one surface, deliberately. A toast and a notification carried the same three verdicts for a
 * while, and both were added before this existed - the toast gone in seconds, the notification gone
 * the moment somebody swipes it. Three places to say one thing is three places to keep in step, and
 * the durable one is the only one that answers the question the user actually arrives with.
 *
 * Every condition is raised at the site that detects it and cleared at the site that disproves it.
 * Nothing here is cleared by a manager restarting, a mode change or a user exit: those change what
 * the app is doing, not what the hardware did.
 *
 * **A workaround is not a disproof**, and that distinction was paid for. A hand-typed hotspot name
 * or static BSSID makes a route work without making the hardware answer, so a record survives its
 * own remedy and is merely hidden, by
 * [com.andrerinas.openheadunit.main.ConnectionIssueBannerPolicy.remedyApplied]. Retiring one on a
 * workaround used to delete the only durable instruction a half-finished remedy had left.
 */
object ConnectionIssues {

    fun raise(context: Context, issue: ConnectionIssue) {
        storeFor(context)?.let { raise(it, issue, System.currentTimeMillis()) }
    }

    fun clear(context: Context, issue: ConnectionIssue) {
        storeFor(context)?.let { clear(it, issue) }
    }

    fun standing(context: Context): List<StandingIssue> =
        storeFor(context)?.let { standing(it) } ?: emptyList()

    /**
     * Record [issue] as currently true.
     *
     * Re-raising a standing issue moves its stamp forward rather than adding a second one: the
     * banner answers "why did the last attempt fail", so the latest occurrence is the interesting
     * one, and a fresh stamp is also what brings the banner back after a dismissal.
     */
    fun raise(store: ConnectionIssueStore, issue: ConnectionIssue, nowMs: Long) {
        store.write(issue, nowMs)
    }

    /** Safe to call when the issue was never raised. */
    fun clear(store: ConnectionIssueStore, issue: ConnectionIssue) {
        if (store.read(issue) != 0L) store.write(issue, 0L)
    }

    /** Everything currently true, most recently raised first. */
    fun standing(store: ConnectionIssueStore): List<StandingIssue> =
        ConnectionIssue.values()
            .map { StandingIssue(it, store.read(it)) }
            .filter { it.raisedAtEpochMs != 0L }
            .sortedByDescending { it.raisedAtEpochMs }

    /**
     * Null when preferences cannot be reached.
     *
     * Settings throws rather than returning null before the user has unlocked the device, and the
     * connection path can run from a boot receiver. A failure we could not write down is worth
     * less than a crash on a road, so this degrades to doing nothing.
     */
    private fun storeFor(context: Context): ConnectionIssueStore? = try {
        SettingsIssueStore(App.provide(context).settings)
    } catch (e: Exception) {
        AppLog.d("ConnectionIssues: settings unavailable, not recording: ${e.message}")
        null
    }

    private class SettingsIssueStore(private val settings: Settings) : ConnectionIssueStore {
        override fun read(issue: ConnectionIssue): Long = try {
            when (issue) {
                ConnectionIssue.BLUETOOTH_SENT_NO_DATA -> settings.connectionIssueBluetoothSilentAtEpochMs
                ConnectionIssue.BSSID_UNAVAILABLE -> settings.connectionIssueBssidAtEpochMs
                ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE -> settings.connectionIssueHotspotConfigAtEpochMs
            }
        } catch (e: Exception) {
            0L
        }

        override fun write(issue: ConnectionIssue, atEpochMs: Long) {
            try {
                when (issue) {
                    ConnectionIssue.BLUETOOTH_SENT_NO_DATA -> settings.connectionIssueBluetoothSilentAtEpochMs = atEpochMs
                    ConnectionIssue.BSSID_UNAVAILABLE -> settings.connectionIssueBssidAtEpochMs = atEpochMs
                    ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE -> settings.connectionIssueHotspotConfigAtEpochMs = atEpochMs
                }
            } catch (e: Exception) {
                AppLog.d("ConnectionIssues: could not record $issue: ${e.message}")
            }
        }
    }
}
