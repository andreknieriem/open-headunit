package com.andrerinas.openheadunit.contract

import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.view.KeyEvent

/**
 * @author algavris
 * *
 * @date 30/05/2016.
 */

object HeadUnit {
    const val packageName = "com.andrerinas.headunitrevived"
}

class KeyIntent(event: KeyEvent): Intent(action) {
    init {
        putExtra(extraEvent, event)
    }

    companion object {
        const val extraEvent = "event"
        const val action = "${HeadUnit.packageName}.ACTION_KEYPRESS"
    }
}

class MediaKeyIntent(event: KeyEvent): Intent(action) {
    init {
        putExtra(KeyIntent.extraEvent, event)
    }

    companion object {
        const val action = "${HeadUnit.packageName}.ACTION_MEDIA_KEYPRESS"
    }
}

class LocationUpdateIntent(location: Location): Intent(action) {
    init {
        putExtra(LocationManager.KEY_LOCATION_CHANGED, location)
    }

    companion object {
        const val action = "${HeadUnit.packageName}.LOCATION_UPDATE"

        fun extractLocation(intent: Intent): Location {
            return intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)!!
        }
    }
}

class ProjectionActivityRequest: Intent(action) {
    companion object {
        const val action = "${HeadUnit.packageName}.ACTION_REQUEST_PROJECTION"
    }
}

/**
 * Broadcast sent when Open Headunit receives navigation updates from Android Auto
 * (from any nav app: Google Maps, Yandex Maps, etc.). Do not setPackage() — implicit broadcast
 * so any app can receive by registering for [action] with RECEIVER_EXPORTED.
 *
 * Other apps: registerReceiver(receiver, IntentFilter(NavigationUpdateIntent.action), RECEIVER_EXPORTED)
 * No special permission required.
 *
 * @param nextEventType **Deprecated (legacy):** wire values for AA `NextTurnDetail.NextEvent` (see [EXTRA_NEXT_EVENT_TYPE]).
 *   Mapped from instrument-cluster `NavigationManeuver` when the old message is absent; scheduled for removal — migrate
 *   consumers to instrument-cluster maneuver types.
 * @param turnSide **Deprecated (legacy):** wire values for AA `NextTurnDetail.Side` 1/2/3 (see [EXTRA_TURN_SIDE]).
 *   Scheduled for removal together with [nextEventType].
 * @param totalDistanceMeters Remaining distance to destination along the route (meters), [EXTRA_TOTAL_DISTANCE_METERS], or null.
 * @param totalTimeSeconds Remaining time to destination (seconds), [EXTRA_TOTAL_TIME_SECONDS], or null.
 * @param estimatedArrival ETA string from the nav app (`estimated_time_at_arrival`), [EXTRA_ESTIMATED_ARRIVAL], or null.
 */
class NavigationUpdateIntent(
    distanceMeters: Int?,
    timeSeconds: Int?,
    road: String,
    nextEventType: Int,
    actionText: String,
    turnSide: Int? = null,
    turnNumber: Int? = null,
    turnAngle: Int? = null,
    totalDistanceMeters: Int? = null,
    totalTimeSeconds: Long? = null,
    estimatedArrival: String? = null
) : Intent(action) {
    init {
        putExtra(EXTRA_DISTANCE_METERS, distanceMeters?.takeIf { it >= 0 } ?: -1)
        putExtra(EXTRA_TIME_SECONDS, timeSeconds?.takeIf { it >= 0 } ?: -1)
        putExtra(EXTRA_ROAD, road.ifBlank { "" })
        putExtra(EXTRA_NEXT_EVENT_TYPE, nextEventType.coerceIn(0, 31))
        putExtra(EXTRA_ACTION_TEXT, actionText.ifBlank { "" })
        putExtra(EXTRA_TURN_SIDE, turnSide?.coerceIn(1, 3) ?: TURN_SIDE_UNSPECIFIED)
        putExtra(EXTRA_TURN_NUMBER, turnNumber?.takeIf { it >= 0 } ?: -1)
        putExtra(EXTRA_TURN_ANGLE, turnAngle?.takeIf { it >= 0 } ?: -1)
        putExtra(EXTRA_TOTAL_DISTANCE_METERS, totalDistanceMeters?.takeIf { it >= 0 } ?: -1)
        putExtra(EXTRA_TOTAL_TIME_SECONDS, totalTimeSeconds?.takeIf { it >= 0 } ?: -1L)
        putExtra(EXTRA_ESTIMATED_ARRIVAL, estimatedArrival?.ifBlank { null } ?: "")
    }

    companion object {
        const val action = "${HeadUnit.packageName}.NAVIGATION_UPDATE"

        /** Distance to the next maneuver in meters, or -1 if not set. */
        const val EXTRA_DISTANCE_METERS = "distance_meters"

        /** Time to the next maneuver in seconds, or -1 if not set. */
        const val EXTRA_TIME_SECONDS = "time_seconds"

        /** Road/street name (e.g. current street or turn target). */
        const val EXTRA_ROAD = "road"

        /**
         * Legacy extra: NextTurnDetail.NextEvent wire values (0…19).
         * Scheduled for removal together with [NavigationUpdateIntent] `nextEventType` parameter; migrate to instrument-cluster maneuver types.
         */
        const val EXTRA_NEXT_EVENT_TYPE = "next_event_type"

        /** Human-readable action string (e.g. "Turn", "Exit ramp") in the app's locale. */
        const val EXTRA_ACTION_TEXT = "action_text"

        /**
         * Legacy extra: NextTurnDetail.Side (1=LEFT, 2=RIGHT, 3=UNSPECIFIED).
         * Scheduled for removal together with [NavigationUpdateIntent] `turnSide` parameter.
         */
        const val EXTRA_TURN_SIDE = "turn_side"
        const val TURN_SIDE_LEFT = 1
        const val TURN_SIDE_RIGHT = 2
        const val TURN_SIDE_UNSPECIFIED = 3

        /** Roundabout exit/turn number if provided, otherwise -1. */
        const val EXTRA_TURN_NUMBER = "turn_number"

        /** Turn angle in degrees if provided, otherwise -1. */
        const val EXTRA_TURN_ANGLE = "turn_angle"

        /**
         * Remaining distance to destination along the route (meters), from instrument-cluster
         * `NavigationDestinationDistance`, or -1 if not set.
         */
        const val EXTRA_TOTAL_DISTANCE_METERS = "total_distance_meters"

        /**
         * Remaining time to destination along the route (seconds), `time_to_arrival_seconds`, or -1 if not set.
         */
        const val EXTRA_TOTAL_TIME_SECONDS = "total_time_seconds"

        /**
         * Estimated time at arrival as provided by the nav app (`estimated_time_at_arrival` string), or empty.
         */
        const val EXTRA_ESTIMATED_ARRIVAL = "estimated_arrival"

        /**
         * Signature-level permission required to receive or send [NavigationUpdateIntent] broadcasts.
         * Senders must call sendBroadcast(intent, BROADCAST_PERMISSION).
         * Receivers must request this permission using <uses-permission> and should enforce it in their manifest declaration.
         */
        const val BROADCAST_PERMISSION = "${HeadUnit.packageName}.permission.NAVIGATION_UPDATE"
    }
}

/**
 * The commands [HeadUnitCommand.RECEIVER_CLASS] accepts. The component is in [HeadUnit.packageName]
 * but actions are prefixed `com.andrerinas.openheadunit`: applicationId and namespace deliberately
 * differ, and the wrong one silently does nothing. Worked examples are in `contract/README.md`.
 */
object HeadUnitCommand {
    const val RECEIVER_CLASS = "com.andrerinas.openheadunit.automation.AutomationReceiver"

    private const val PREFIX = "com.andrerinas.openheadunit"

    // Control. Open to any caller, like the OEM key receivers already are.
    const val ACTION_CONNECT = "$PREFIX.ACTION_CONNECT"
    const val ACTION_DISCONNECT = "$PREFIX.ACTION_DISCONNECT"
    const val ACTION_START_SELF_MODE = "$PREFIX.ACTION_START_SELF_MODE"
    /**
     * Ends the session and stops the service. The public name, which the shortcuts and existing
     * automations already send; the service uses a different string internally.
     */
    const val ACTION_STOP_SERVICE = "$PREFIX.ACTION_STOP_SERVICE"

    /** Older alias for [ACTION_STOP_SERVICE], still accepted. */
    const val ACTION_EXIT = "$PREFIX.ACTION_EXIT"
    const val ACTION_SET_NIGHT_MODE = "$PREFIX.ACTION_SET_NIGHT_MODE"
    const val ACTION_START_WIRELESS = "$PREFIX.ACTION_START_WIRELESS"
    const val ACTION_STOP_WIRELESS = "$PREFIX.ACTION_STOP_WIRELESS"

    /**
     * Stops the wireless bring-up and keeps it down, as the status pill's X does. Unlike
     * [ACTION_STOP_WIRELESS] no automatic trigger undoes it; asking for wireless by hand does.
     */
    const val ACTION_CANCEL_WIRELESS = "$PREFIX.ACTION_CANCEL_WIRELESS"
    const val ACTION_START_WIRELESS_SCAN = "$PREFIX.ACTION_START_WIRELESS_SCAN"

    /**
     * Ends the session but leaves the wireless network up, so the phone's saved profile still names
     * one that exists. Unlike [ACTION_DISCONNECT] the stack stays armed and the phone may come back
     * on its own. Native AA on WiFi Direct only; inert elsewhere.
     */
    const val ACTION_END_SESSION_STAY_ARMED = "$PREFIX.ACTION_END_SESSION_STAY_ARMED"
    const val ACTION_NATIVE_AA_POKE = "$PREFIX.ACTION_NATIVE_AA_POKE"

    /**
     * Cancels a Native AA wake in progress, as the driver selector's own Cancel does. Inert on a
     * build without the driver-selection feature, which is where the service handler lives.
     */
    const val ACTION_NATIVE_AA_CANCEL_POKE = "$PREFIX.ACTION_NATIVE_AA_CANCEL_POKE"
    const val ACTION_NEARBY_CONNECT = "$PREFIX.ACTION_NEARBY_CONNECT"
    const val ACTION_CHECK_USB = "$PREFIX.ACTION_CHECK_USB"
    const val ACTION_REFRESH_SENSORS = "$PREFIX.ACTION_REFRESH_SENSORS"
    const val ACTION_RESTART_AUDIO = "$PREFIX.ACTION_RESTART_AUDIO"
    const val ACTION_RAISE_PROJECTION = "$PREFIX.ACTION_RAISE_PROJECTION"

    /**
     * The original spellings of the three above, still accepted. They were the internal service
     * actions before this surface existed, so anything already scripted against them keeps working.
     */
    const val LEGACY_ACTION_REFRESH_SENSORS = "$PREFIX.aap.action.REFRESH_SENSORS"
    const val LEGACY_ACTION_RESTART_AUDIO = "$PREFIX.aap.action.RESTART_AUDIO"
    const val LEGACY_ACTION_RAISE_PROJECTION = "$PREFIX.aap.action.RAISE_PROJECTION"
    const val ACTION_QUERY_STATE = "$PREFIX.ACTION_QUERY_STATE"

    // Configuration. Refused unless the user has turned on "Allow external configuration",
    // because these rewrite the unit's setup and drive the log capture.
    const val ACTION_SET_SETTINGS = "$PREFIX.ACTION_SET_SETTINGS"
    const val ACTION_GET_SETTINGS = "$PREFIX.ACTION_GET_SETTINGS"
    const val ACTION_RESET_SETTINGS = "$PREFIX.ACTION_RESET_SETTINGS"
    const val ACTION_SET_LOG_LEVEL = "$PREFIX.ACTION_SET_LOG_LEVEL"
    const val ACTION_START_LOG_CAPTURE = "$PREFIX.ACTION_START_LOG_CAPTURE"
    const val ACTION_STOP_LOG_CAPTURE = "$PREFIX.ACTION_STOP_LOG_CAPTURE"
    const val ACTION_EXPORT_LOG = "$PREFIX.ACTION_EXPORT_LOG"
    const val ACTION_LOG_MARKER = "$PREFIX.ACTION_LOG_MARKER"

    /** Target address for [ACTION_CONNECT]; without it the USB path is checked instead. */
    const val EXTRA_IP = "ip"

    /** [ACTION_CONNECT] and [ACTION_START_SELF_MODE]: connect without raising the projection. */
    const val EXTRA_NO_UI = "no_ui"

    /** `day`, `night` or `auto` for [ACTION_SET_NIGHT_MODE]. */
    const val EXTRA_STATE = "state"

    /** Bluetooth MAC for [ACTION_NATIVE_AA_POKE]. */
    const val EXTRA_MAC = "extra_mac"

    /** Google Nearby endpoint for [ACTION_NEARBY_CONNECT]. */
    const val EXTRA_ENDPOINT_ID = "extra_endpoint_id"

    /** Inline settings JSON for [ACTION_SET_SETTINGS], in the settings-backup format. */
    const val EXTRA_JSON = "json"

    /** File to read or write, for [ACTION_SET_SETTINGS], [ACTION_GET_SETTINGS], [ACTION_EXPORT_LOG]. */
    const val EXTRA_PATH = "path"

    /** `verbose`, `debug`, `info`, `warn`, `error` or `silent` for [ACTION_SET_LOG_LEVEL]. */
    const val EXTRA_LEVEL = "level"

    /** The text [ACTION_LOG_MARKER] writes into the log. */
    const val EXTRA_TEXT = "text"
}

/**
 * Broadcast when the session changes state, so automation can react without polling. Sent implicitly
 * and unguarded, because an automation app cannot hold an app-declared permission; everything here is
 * readable by any app, which is why it carries no credentials and never names the phone.
 * Receive with `registerReceiver(r, IntentFilter(SessionStateIntent.action), RECEIVER_EXPORTED)`.
 */
class SessionStateIntent(
    state: String,
    transport: String,
    reason: String,
    uptimeMs: Long
) : Intent(action) {
    init {
        putExtra(EXTRA_STATE, state)
        putExtra(EXTRA_TRANSPORT, transport)
        putExtra(EXTRA_REASON, reason)
        putExtra(EXTRA_UPTIME_MS, uptimeMs)
    }

    companion object {
        const val action = "${HeadUnit.packageName}.SESSION_STATE"

        /** One of [STATE_CONNECTING], [STATE_CONNECTED], [STATE_PROJECTING], [STATE_DISCONNECTED], [STATE_FAILED]. */
        const val EXTRA_STATE = "state"

        /** `usb`, `wifi`, `self` or `unknown`. */
        const val EXTRA_TRANSPORT = "transport"

        /**
         * Why the state changed. An open string, not a closed set: treat an unrecognised value as
         * "some other reason" rather than failing.
         */
        const val EXTRA_REASON = "reason"

        /** Milliseconds the session had been up, or 0 outside a session. */
        const val EXTRA_UPTIME_MS = "uptime_ms"

        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"

        /** The session is live and carrying video; this is what "Android Auto is running" means. */
        const val STATE_PROJECTING = "projecting"
        const val STATE_DISCONNECTED = "disconnected"

        /** The session never started. [EXTRA_REASON] says what failed. */
        const val STATE_FAILED = "failed"

        const val REASON_USER_EXIT = "user_exit"
        const val REASON_LINK_LOST = "link_lost"
        const val REASON_PHONE_LEFT = "phone_left"
        const val REASON_HANDSHAKE_FAILED = "handshake_failed"
        const val REASON_PEER_SILENT = "peer_silent"
        const val REASON_CONNECT_FAILED = "connect_failed"
        const val REASON_NONE = ""
    }
}
