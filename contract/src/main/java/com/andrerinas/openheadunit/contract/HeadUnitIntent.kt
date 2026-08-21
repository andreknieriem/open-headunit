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

class ConnectedIntent: Intent(action) {
    companion object {
        const val action = "${HeadUnit.packageName}.ACTION_CONNECTED"
    }
}

class DisconnectIntent(isClean: Boolean = false) : Intent(action) {
    init {
        setPackage(HeadUnit.packageName)
        putExtra(EXTRA_CLEAN, isClean)
    }

    companion object {
        const val action = "${HeadUnit.packageName}.ACTION_DISCONNECT"
        const val EXTRA_CLEAN = "is_clean"
    }
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
