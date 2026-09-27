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
    HOTSPOT_CONFIG_UNREADABLE,

    /**
     * No access point is up at all, so there is no network for the phone to be sent to.
     *
     * The commonest way the hotspot route fails and, until now, the quietest: the resolve loop said
     * so once per run into a log that a busy unit drops, and nothing anywhere carried it afterwards.
     */
    HOTSPOT_NOT_RUNNING,

    /**
     * The platform would not create a WiFi Direct group, so there was no network to hand the phone.
     *
     * Distinct from the two hotspot records in that no setting reaches it: the refusal is the unit's
     * radio saying it cannot be a group owner right now, most often because it is already an
     * associated station on some other network. The user's levers are leaving that network or
     * switching the Native transport to the head unit's own access point.
     */
    WIFI_DIRECT_GROUP_REFUSED,

    /**
     * Something else on this unit keeps switching WiFi Direct off and on, so no group can form.
     *
     * The refusal above is the symptom this produces, and both stand at once when it happens. This
     * one is separate because its remedy is different in kind: the unit is capable of hosting a
     * group, and would, if the app cycling the interface out from under it stopped.
     */
    WIFI_DIRECT_STACK_CYCLED,

    /**
     * This unit's WiFi is off, and this Android will not let an app switch it on.
     *
     * Separate from [WIFI_DIRECT_GROUP_REFUSED], which is a radio that was asked and said no: here
     * nothing is ever asked, so there is no associated station to go looking for. The user's levers
     * are switching WiFi on, or the head unit's own access point as the Native transport.
     */
    WIFI_RADIO_OFF,

    /**
     * Sessions keep starting and then ending without a single video frame ever arriving.
     *
     * The one verdict about a link that works. The phone joins, the AAP session and SSL complete,
     * the video channel opens, and the phone closes the socket seconds later having sent nothing:
     * measured on a 2.4 GHz access point at 1080p/60, where the same access point carried 800x480/30
     * indefinitely. The user's levers are the frame rate, the resolution, AAC audio, and 5 GHz on a
     * radio that has it.
     */
    VIDEO_LINK_TOO_SLOW,

    /**
     * The unit refused a group owner on every 5 GHz channel the pinned one was walked across.
     *
     * Not the same as [WIFI_DIRECT_GROUP_REFUSED]: a group does form, on whatever channel the
     * driver picks for itself. That is the failure - the setting exists because the driver's pick
     * is one the user's phone cannot see, and a phone whose country forbids that channel does not
     * merely refuse it, it never scans it and so never lists the network at all. The user's lever
     * is the band, not the channel.
     */
    FIVE_GHZ_CHANNEL_REFUSED,

    /**
     * Android Auto's own head unit server is accepting connections and answering none of them.
     *
     * It hands every accepted socket to its car service and waits there with no timeout, so one
     * peer that went away without closing leaves it deaf to everyone after. Nothing on this side
     * recovers it; stopping and starting the server on the phone does.
     */
    HEADUNIT_SERVER_NOT_ANSWERING,

    /**
     * Another device holds this unit's hands-free link, so the phone being woken cannot have it.
     *
     * The wake still goes out, because the link is not that phone's and nothing of its is taken.
     * But Android Auto will not start wireless setup against a head unit whose Bluetooth is not
     * connected with a profile, and a unit serving one hands-free link at a time has none left to
     * give. No setting reaches it: the lever is the other device.
     */
    HANDS_FREE_HELD_ELSEWHERE,

    /**
     * This unit's Bluetooth would not publish a hands-free record for the app to stand in with.
     *
     * Android Auto will not start wireless setup against a head unit whose Bluetooth is not
     * connected with a profile, and a unit with no such record can never offer one. Asked for
     * again a few times before this is raised, in case it was the Android Auto record registering
     * milliseconds earlier. No setting reaches it.
     */
    HANDS_FREE_RECORD_REFUSED,

    /**
     * The phone is dialling a TCP endpoint this unit no longer honours, and cannot be told to stop.
     *
     * Android Auto stores that endpoint beside the network it was given and prefers it over the
     * Bluetooth handshake, with no fallback when the network is gone. This unit turns the dial away
     * and, where its Bluetooth listeners are open, withdraws the endpoint on the wire. A served
     * dial retires it, and so does a Bluetooth handshake landing with none beside it, because a
     * rejection that works is what stops the phone ever dialling again.
     */
    PHONE_HOLDS_STALE_ENDPOINT
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

    fun raiseOnce(context: Context, issue: ConnectionIssue) {
        storeFor(context)?.let { raiseOnce(it, issue, System.currentTimeMillis()) }
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

    /**
     * Record [issue] as true without moving a stamp that is already standing.
     *
     * For a condition re-detected on a loop rather than at an event. [raise] would push the stamp
     * past the user's dismissal on every pass, so the banner could never be dismissed at all.
     */
    fun raiseOnce(store: ConnectionIssueStore, issue: ConnectionIssue, nowMs: Long) {
        if (store.read(issue) == 0L) store.write(issue, nowMs)
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
                ConnectionIssue.HOTSPOT_NOT_RUNNING -> settings.connectionIssueHotspotOffAtEpochMs
                ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED -> settings.connectionIssueWifiDirectRefusedAtEpochMs
                ConnectionIssue.WIFI_DIRECT_STACK_CYCLED -> settings.connectionIssueWifiDirectCycledAtEpochMs
                ConnectionIssue.WIFI_RADIO_OFF -> settings.connectionIssueWifiRadioOffAtEpochMs
                ConnectionIssue.VIDEO_LINK_TOO_SLOW -> settings.connectionIssueVideoLinkTooSlowAtEpochMs
                ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED -> settings.connectionIssueFiveGhzChannelRefusedAtEpochMs
                ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING -> settings.connectionIssueHeadUnitServerDeafAtEpochMs
                ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE -> settings.connectionIssueHandsFreeHeldAtEpochMs
                ConnectionIssue.HANDS_FREE_RECORD_REFUSED -> settings.connectionIssueHandsFreeRecordRefusedAtEpochMs
                ConnectionIssue.PHONE_HOLDS_STALE_ENDPOINT -> settings.connectionIssueStaleEndpointAtEpochMs
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
                    ConnectionIssue.HOTSPOT_NOT_RUNNING -> settings.connectionIssueHotspotOffAtEpochMs = atEpochMs
                    ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED -> settings.connectionIssueWifiDirectRefusedAtEpochMs = atEpochMs
                    ConnectionIssue.WIFI_DIRECT_STACK_CYCLED -> settings.connectionIssueWifiDirectCycledAtEpochMs = atEpochMs
                    ConnectionIssue.WIFI_RADIO_OFF -> settings.connectionIssueWifiRadioOffAtEpochMs = atEpochMs
                    ConnectionIssue.VIDEO_LINK_TOO_SLOW -> settings.connectionIssueVideoLinkTooSlowAtEpochMs = atEpochMs
                    ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED -> settings.connectionIssueFiveGhzChannelRefusedAtEpochMs = atEpochMs
                    ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING -> settings.connectionIssueHeadUnitServerDeafAtEpochMs = atEpochMs
                    ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE -> settings.connectionIssueHandsFreeHeldAtEpochMs = atEpochMs
                    ConnectionIssue.HANDS_FREE_RECORD_REFUSED -> settings.connectionIssueHandsFreeRecordRefusedAtEpochMs = atEpochMs
                    ConnectionIssue.PHONE_HOLDS_STALE_ENDPOINT -> settings.connectionIssueStaleEndpointAtEpochMs = atEpochMs
                }
            } catch (e: Exception) {
                AppLog.d("ConnectionIssues: could not record $issue: ${e.message}")
            }
        }
    }
}
