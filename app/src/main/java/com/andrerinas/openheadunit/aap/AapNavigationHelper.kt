package com.andrerinas.openheadunit.aap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.NextEvent as LegacyNextEvent
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.Side as LegacySide
import com.andrerinas.openheadunit.contract.NavigationUpdateIntent
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Date

class AapNavigationHelper(
    private val context: Context,
    private val onNavigationUpdate: ((NightOverlayState.Navigation?) -> Unit)? = null
) {
    data class TimedMessage<T>(
        val payload: T,
        val updatedAtElapsedRealtimeMs: Long
    )

    data class NavigationSnapshot(
        var clusterStatus: TimedMessage<NavigationStatus.NavigationClusterStatus>? = null,
        @Deprecated(
            message = "This message may be not send by device",
            level = DeprecationLevel.WARNING
        )
        var nextTurnDetail: TimedMessage<NavigationStatus.NextTurnDetail>? = null,
        @Deprecated(
            message = "This message may be not send by device",
            level = DeprecationLevel.WARNING
        )
        var nextTurnDistance: TimedMessage<NavigationStatus.NextTurnDistanceEvent>? = null,
        var navigationState: TimedMessage<NavigationStatus.NavigationState>? = null,
        var currentPosition: TimedMessage<NavigationStatus.NavigationCurrentPosition>? = null
    )

    private data class FullNavigationMessage(
        val distanceMeters: Int?,
        val timeSeconds: Int?,
        val road: String,
        val currentRoad: String,
        @Deprecated(
            message = "Legacy NextTurnDetail.NextEvent wire value; remove with NavigationUpdateIntent.nextEventType and maneuverTypeToLegacyNextEvent.",
            level = DeprecationLevel.WARNING
        )
        val nextEventType: Int,
        val actionText: String,
        @Deprecated(
            message = "Legacy NextTurnDetail.Side (1/2/3); remove with NavigationUpdateIntent.turnSide and maneuverTypeToLegacyTurnSide.",
            level = DeprecationLevel.WARNING
        )
        val turnSide: Int?,
        val turnNumber: Int?,
        val turnAngle: Int?,
        val nextManeuver: Int?,
        val totalDistanceMeters: Int?,
        val totalTimeSeconds: Long?,
        val estimatedArrival: String?,
        val laneGuidance: String?
    )

    fun nowElapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    @Suppress("DEPRECATION") // Single bridge: legacy nextEventType/turnSide → NavigationUpdateIntent
    fun sendFullNavigationBroadcast(snapshot: NavigationSnapshot, navEventType: Int) {
        val prepared = prepareFullNavigationMessage(snapshot, navEventType)
        onNavigationUpdate?.let { publish ->
            val direction = when (prepared.turnSide) {
                LegacySide.LEFT.number -> context.getString(R.string.night_overlay_left)
                LegacySide.RIGHT.number -> context.getString(R.string.night_overlay_right)
                else -> null
            }
            val maneuver = if (direction == null) prepared.actionText
                else context.getString(R.string.night_overlay_direction, prepared.actionText, direction)
            publish(
                if (prepared.nextEventType != LegacyNextEvent.UNKNOWN.number) {
                    NightOverlayState.Navigation(
                        maneuver = maneuver,
                        distanceMeters = prepared.distanceMeters?.takeIf { it >= 0 },
                        road = prepared.road.takeIf { it.isNotBlank() },
                        details = buildManeuverDetails(prepared.turnNumber, prepared.turnAngle),
                        etaSummary = buildEtaSummary(
                            prepared.estimatedArrival,
                            prepared.totalTimeSeconds,
                            prepared.totalDistanceMeters
                        ),
                        currentRoad = prepared.currentRoad.takeIf { it.isNotBlank() },
                        routeNumber = extractRouteNumber(prepared.currentRoad, prepared.road),
                        laneGuidance = prepared.laneGuidance,
                        speedLimitKmh = null,
                        speedKmh = null,
                        trafficDelaySeconds = null,
                        icon = toOverlayIcon(
                            maneuverType = prepared.nextManeuver?.let {
                                NavigationStatus.NavigationManeuver.NavigationType.forNumber(it)
                            },
                            nextEventType = prepared.nextEventType,
                            turnSide = prepared.turnSide
                        )
                    )
                } else {
                    null
                }
            )
        }
        val intent = NavigationUpdateIntent(
            distanceMeters = prepared.distanceMeters,
            timeSeconds = prepared.timeSeconds,
            road = prepared.road,
            nextEventType = prepared.nextEventType,
            actionText = prepared.actionText,
            turnSide = prepared.turnSide,
            turnNumber = prepared.turnNumber,
            turnAngle = prepared.turnAngle,
            totalDistanceMeters = prepared.totalDistanceMeters,
            totalTimeSeconds = prepared.totalTimeSeconds,
            estimatedArrival = prepared.estimatedArrival,
            currentRoad = prepared.currentRoad
        )
        context.applicationContext.sendBroadcast(intent, NavigationUpdateIntent.BROADCAST_PERMISSION)
    }

    fun showNotificationForSnapshot(snapshot: NavigationSnapshot, distanceMeters: Int?) {
        val detail = snapshot.nextTurnDetail?.payload
        val state = snapshot.navigationState?.payload
        val actionFromDetail = detail
            ?.takeIf { it.hasNextTurn() }
            ?.let { nextEventToAction(it.nextTurn) }
        val actionFromState = state?.stepsList?.firstOrNull()
            ?.takeIf { it.hasManeuver() }
            ?.maneuver
            ?.type
            ?.let { maneuverTypeToAction(it) }
        val action = actionFromDetail ?: actionFromState ?: context.getString(R.string.nav_action_unknown)

        val street = NavigationRoadPolicy.maneuverRoad(state, detail) ?: "—"
        showNotification(distanceMeters = distanceMeters, action = action, street = street)
    }

    fun cancelNotification() {
        val appContext = context.applicationContext
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NAV_NOTIFICATION_ID)
    }

    @Suppress("DEPRECATION") // Localized use of deprecated legacy mapping until broadcast API drops NextTurnDetail fields
    private fun prepareFullNavigationMessage(snapshot: NavigationSnapshot, navEventType: Int): FullNavigationMessage {
        val detail = snapshot.nextTurnDetail?.payload
        val turnDistance = snapshot.nextTurnDistance?.payload
        val state = snapshot.navigationState?.payload
        val currentPosition = snapshot.currentPosition?.payload

        val stepDistance = currentPosition?.takeIf { it.hasStepDistance() }?.stepDistance
        val distanceMeters = stepDistance
            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
            ?.distance
            ?.meters
            ?: turnDistance?.takeIf { it.hasDistanceMeters() }?.distanceMeters?.takeIf { it >= 0 }
        val timeSeconds = stepDistance
            ?.takeIf { it.hasTimeToStepSeconds() }
            ?.timeToStepSeconds
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: turnDistance?.timeToTurnSeconds?.takeIf { it >= 0 }

        val road = NavigationRoadPolicy.maneuverRoad(state, detail).orEmpty()
        val currentRoad = NavigationRoadPolicy.currentRoad(currentPosition).orEmpty()

        val maneuverType = state?.stepsList?.firstOrNull()
            ?.takeIf { it.hasManeuver() }
            ?.maneuver
            ?.takeIf { it.hasType() }
            ?.type

        val nextEventType = detail?.takeIf { it.hasNextTurn() }?.nextTurn?.number
            ?: (maneuverType?.let { maneuverTypeToLegacyNextEvent(it) }
                ?: LegacyNextEvent.UNKNOWN.number)
        val turnSide = detail
            ?.takeIf { it.hasSide() }
            ?.side
            ?.number
            ?: maneuverType?.let { maneuverTypeToLegacyTurnSide(it) }
        val nextManeuver = state?.stepsList?.firstOrNull()?.maneuver?.type?.number
        val turnNumber = state?.stepsList?.firstOrNull()?.maneuver?.roundaboutExitNumber
            ?: detail?.takeIf { it.hasTurnNumber() }?.turnNumber
        val turnAngle = state?.stepsList?.firstOrNull()?.maneuver?.roundaboutExitAngle
            ?: detail?.takeIf { it.hasTurnAngle() }?.turnAngle
val actionText = state?.stepsList?.firstOrNull()?.maneuver?.type?.let { maneuverTypeToAction(it) }
    ?: detail?.takeIf { it.hasNextTurn() }?.let { nextEventToAction(it.nextTurn) }
    ?: context.getString(R.string.nav_action_unknown)

        val destFirst = currentPosition?.destinationDistancesList?.firstOrNull()
        val totalDistanceMeters = destFirst
            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
            ?.distance
            ?.meters
            ?.takeIf { it >= 0 }
        val totalTimeSeconds = destFirst
            ?.takeIf { it.hasTimeToArrivalSeconds() }
            ?.timeToArrivalSeconds
            ?.takeIf { it >= 0L }
        val estimatedArrival = destFirst
            ?.takeIf { it.hasEstimatedTimeAtArrival() }
            ?.estimatedTimeAtArrival
            ?.takeIf { it.isNotBlank() }
        val laneGuidance = state?.stepsList?.firstOrNull()?.let(::buildLaneGuidance)

        AppLog.d(
            "Nav: emit debounced eventType=$navEventType " +
                "statusAt=${snapshot.clusterStatus?.updatedAtElapsedRealtimeMs} " +
                "turnAt=${snapshot.nextTurnDetail?.updatedAtElapsedRealtimeMs} " +
                "distanceAt=${snapshot.nextTurnDistance?.updatedAtElapsedRealtimeMs} " +
                "stateAt=${snapshot.navigationState?.updatedAtElapsedRealtimeMs} " +
                "positionAt=${snapshot.currentPosition?.updatedAtElapsedRealtimeMs}"
        )
        AppLog.d(
            "Nav: AA data availability " +
                "nextManeuver=${nextManeuver ?: "(none)"} " +
                "action='$actionText' " +
                "nextDistance=${distanceMeters ?: "(none)"} " +
                "nextTime=${timeSeconds ?: "(none)"} " +
                "nextRoad='${if (road.isBlank()) "(none)" else road}' " +
                "currentRoad='${if (currentRoad.isBlank()) "(none)" else currentRoad}' " +
                "eta='${estimatedArrival ?: "(none)"}' " +
                "remainingDistance=${totalDistanceMeters ?: "(none)"} " +
                "remainingTime=${totalTimeSeconds ?: "(none)"} " +
                "lanes='${laneGuidance ?: "(none)"}' " +
                "speedLimit=(not provided by current AA nav messages) " +
                "trafficDelayBaselineEta=(not provided by current AA nav messages)"
        )

        return FullNavigationMessage(
            distanceMeters = distanceMeters,
            timeSeconds = timeSeconds,
            road = road,
            currentRoad = currentRoad,
            nextEventType = nextEventType,
            actionText = actionText,
            turnSide = turnSide,
            turnNumber = turnNumber,
            turnAngle = turnAngle,
            nextManeuver = nextManeuver,
            totalDistanceMeters = totalDistanceMeters,
            totalTimeSeconds = totalTimeSeconds,
            estimatedArrival = estimatedArrival,
            laneGuidance = laneGuidance
        )
    }

    private fun buildEtaSummary(
        estimatedArrival: String?,
        totalTimeSeconds: Long?,
        totalDistanceMeters: Int?
    ): String? {
        val eta = estimatedArrival?.takeIf { it.isNotBlank() } ?: totalTimeSeconds?.let {
            val clock = DateFormat.getTimeFormat(context)
            clock.format(Date(System.currentTimeMillis() + it * 1000L))
        }
        val duration = totalTimeSeconds?.let(::formatTripDuration)
        val distance = totalDistanceMeters?.let(::formatDistance)
        if (eta == null && duration == null && distance == null) return null
        return context.getString(
            R.string.night_overlay_eta_summary,
            eta ?: "—",
            duration ?: "—",
            distance ?: "—"
        )
    }

    private fun formatTripDuration(totalSeconds: Long): String {
        val minutes = (totalSeconds / 60L).coerceAtLeast(0L)
        val hours = minutes / 60L
        val restMinutes = minutes % 60L
        return if (hours > 0L) {
            context.getString(R.string.night_overlay_duration_hours_minutes, hours, restMinutes)
        } else {
            context.getString(R.string.night_overlay_duration_minutes, restMinutes)
        }
    }

    private fun formatDistance(meters: Int): String {
        return if (meters < 1000) {
            context.getString(R.string.night_overlay_meters, meters)
        } else {
            context.getString(R.string.night_overlay_kilometers, meters / 1000.0)
        }
    }

    private fun extractRouteNumber(currentRoad: String, nextRoad: String): String? {
        val roadText = listOf(currentRoad, nextRoad)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val match = Regex("\\b([A-Za-zА-Яа-я]{1,3}[\\s-]?\\d{1,4}[A-Za-zА-Яа-я]?)\\b")
            .find(roadText)
            ?.groupValues
            ?.getOrNull(1)
        return match?.replace("\\s+".toRegex(), " ")
    }

    private fun buildLaneGuidance(step: NavigationStatus.NavigationStep): String? {
        if (step.lanesCount == 0) return null
        val lanes = step.lanesList.mapNotNull { lane ->
            val direction = lane.laneDirectionsList.firstOrNull() ?: return@mapNotNull null
            val token = when (direction.shape) {
                NavigationStatus.NavigationLane.LaneDirection.Shape.STRAIGHT -> "S"
                NavigationStatus.NavigationLane.LaneDirection.Shape.SLIGHT_LEFT -> "SL"
                NavigationStatus.NavigationLane.LaneDirection.Shape.SLIGHT_RIGHT -> "SR"
                NavigationStatus.NavigationLane.LaneDirection.Shape.NORMAL_LEFT -> "L"
                NavigationStatus.NavigationLane.LaneDirection.Shape.NORMAL_RIGHT -> "R"
                NavigationStatus.NavigationLane.LaneDirection.Shape.SHARP_LEFT -> "HL"
                NavigationStatus.NavigationLane.LaneDirection.Shape.SHARP_RIGHT -> "HR"
                NavigationStatus.NavigationLane.LaneDirection.Shape.U_TURN_LEFT -> "UL"
                NavigationStatus.NavigationLane.LaneDirection.Shape.U_TURN_RIGHT -> "UR"
                else -> "?"
            }
            if (direction.isHighlighted) "[$token]" else token
        }
        if (lanes.isEmpty()) return null
        return context.getString(R.string.night_overlay_lanes, lanes.joinToString(" | "))
    }

    private fun showNotification(distanceMeters: Int?, action: String, street: String) {
        val appContext = context.applicationContext
        val title = if (distanceMeters != null && distanceMeters >= 0) {
            context.getString(R.string.nav_notification_title_format, distanceMeters, action)
        } else {
            action
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val notification = NotificationCompat.Builder(appContext, NAV_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.nav_notification_street_format, street))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    0,
                    AapProjectionActivity.intent(appContext),
                    pendingIntentFlags
                )
            )
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NAV_NOTIFICATION_ID, notification)
    }

    private fun nextEventToAction(nextEvent: NavigationStatus.NextTurnDetail.NextEvent): String {
        return when (nextEvent) {
            NavigationStatus.NextTurnDetail.NextEvent.UNKNOWN -> context.getString(R.string.nav_action_unknown)
            NavigationStatus.NextTurnDetail.NextEvent.DEPART -> context.getString(R.string.nav_action_depart)
            NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE -> context.getString(R.string.nav_action_name_change)
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> context.getString(R.string.nav_action_slight_turn)
            NavigationStatus.NextTurnDetail.NextEvent.TURN -> context.getString(R.string.nav_action_turn)
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> context.getString(R.string.nav_action_sharp_turn)
            NavigationStatus.NextTurnDetail.NextEvent.U_TURN -> context.getString(R.string.nav_action_uturn)
            NavigationStatus.NextTurnDetail.NextEvent.ON_RAMP -> context.getString(R.string.nav_action_on_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> context.getString(R.string.nav_action_off_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.FORK -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER -> context.getString(R.string.nav_action_roundabout_enter)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT -> context.getString(R.string.nav_action_roundabout_exit)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> context.getString(R.string.nav_action_roundabout)
            NavigationStatus.NextTurnDetail.NextEvent.STRAIGHT -> context.getString(R.string.nav_action_straight)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT -> context.getString(R.string.nav_action_ferry)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAIN -> context.getString(R.string.nav_action_ferry_train)
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> context.getString(R.string.nav_action_destination)
        }
    }

    private fun buildManeuverDetails(turnNumber: Int?, turnAngle: Int?): String? {
        val parts = mutableListOf<String>()
        turnNumber?.takeIf { it > 0 }?.let {
            parts.add(context.getString(R.string.night_overlay_exit_number, it))
        }
        turnAngle?.takeIf { it > 0 }?.let {
            parts.add(context.getString(R.string.night_overlay_exit_angle, it))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun toOverlayIcon(
        maneuverType: NavigationStatus.NavigationManeuver.NavigationType?,
        nextEventType: Int,
        turnSide: Int?
    ): NightOverlayState.NavigationIcon? {
        return maneuverType?.let(::overlayIconForManeuverType)
            ?: LegacyNextEvent.forNumber(nextEventType)?.let { overlayIconForLegacyEvent(it, turnSide) }
    }

    private fun overlayIconForManeuverType(
        type: NavigationStatus.NavigationManeuver.NavigationType
    ): NightOverlayState.NavigationIcon? {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN,
            NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE -> null
            NavigationStatus.NavigationManeuver.NavigationType.DEPART -> NightOverlayState.NavigationIcon.DEPART
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT ->
                NightOverlayState.NavigationIcon.TURN_SLIGHT_LEFT
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT ->
                NightOverlayState.NavigationIcon.TURN_LEFT
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT ->
                NightOverlayState.NavigationIcon.TURN_SHARP_LEFT
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT ->
                NightOverlayState.NavigationIcon.U_TURN_LEFT
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT ->
                NightOverlayState.NavigationIcon.RAMP_LEFT
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT ->
                NightOverlayState.NavigationIcon.FORK_LEFT
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED ->
                NightOverlayState.NavigationIcon.MERGE
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE ->
                NightOverlayState.NavigationIcon.ROUNDABOUT
            NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT ->
                NightOverlayState.NavigationIcon.STRAIGHT
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT ->
                NightOverlayState.NavigationIcon.TURN_SLIGHT_RIGHT
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT ->
                NightOverlayState.NavigationIcon.TURN_RIGHT
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT ->
                NightOverlayState.NavigationIcon.TURN_SHARP_RIGHT
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT ->
                NightOverlayState.NavigationIcon.U_TURN_RIGHT
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT ->
                NightOverlayState.NavigationIcon.RAMP_RIGHT
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT ->
                NightOverlayState.NavigationIcon.FORK_RIGHT
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT ->
                NightOverlayState.NavigationIcon.FERRY_BOAT
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN ->
                NightOverlayState.NavigationIcon.FERRY_TRAIN
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_STRAIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT ->
                NightOverlayState.NavigationIcon.DESTINATION
        }
    }

    private fun overlayIconForLegacyEvent(
        event: LegacyNextEvent,
        turnSide: Int?
    ): NightOverlayState.NavigationIcon? {
        return when (event) {
            LegacyNextEvent.UNKNOWN,
            LegacyNextEvent.NAME_CHANGE -> null
            LegacyNextEvent.DEPART -> NightOverlayState.NavigationIcon.DEPART
            LegacyNextEvent.STRAIGHT -> NightOverlayState.NavigationIcon.STRAIGHT
            LegacyNextEvent.ROUNDABOUT_ENTER,
            LegacyNextEvent.ROUNDABOUT_EXIT,
            LegacyNextEvent.ROUNDABOUT_ENTER_AND_EXIT -> NightOverlayState.NavigationIcon.ROUNDABOUT
            LegacyNextEvent.FERRY_BOAT -> NightOverlayState.NavigationIcon.FERRY_BOAT
            LegacyNextEvent.FERRY_TRAIN -> NightOverlayState.NavigationIcon.FERRY_TRAIN
            LegacyNextEvent.DESTINATION -> NightOverlayState.NavigationIcon.DESTINATION
            LegacyNextEvent.MERGE -> NightOverlayState.NavigationIcon.MERGE
            LegacyNextEvent.SLIGHT_TURN -> when (turnSide) {
                LegacySide.LEFT.number -> NightOverlayState.NavigationIcon.TURN_SLIGHT_LEFT
                LegacySide.RIGHT.number -> NightOverlayState.NavigationIcon.TURN_SLIGHT_RIGHT
                else -> null
            }
            LegacyNextEvent.TURN -> when (turnSide) {
                LegacySide.LEFT.number -> NightOverlayState.NavigationIcon.TURN_LEFT
                LegacySide.RIGHT.number -> NightOverlayState.NavigationIcon.TURN_RIGHT
                else -> null
            }
            LegacyNextEvent.SHARP_TURN -> when (turnSide) {
                LegacySide.LEFT.number -> NightOverlayState.NavigationIcon.TURN_SHARP_LEFT
                LegacySide.RIGHT.number -> NightOverlayState.NavigationIcon.TURN_SHARP_RIGHT
                else -> null
            }
            LegacyNextEvent.U_TURN -> when (turnSide) {
                LegacySide.LEFT.number -> NightOverlayState.NavigationIcon.U_TURN_LEFT
                LegacySide.RIGHT.number -> NightOverlayState.NavigationIcon.U_TURN_RIGHT
                else -> null
            }
            LegacyNextEvent.ON_RAMP,
            LegacyNextEvent.OFFRAMP -> when (turnSide) {
                LegacySide.LEFT.number -> NightOverlayState.NavigationIcon.RAMP_LEFT
                LegacySide.RIGHT.number -> NightOverlayState.NavigationIcon.RAMP_RIGHT
                else -> null
            }
            LegacyNextEvent.FORK -> when (turnSide) {
                LegacySide.LEFT.number -> NightOverlayState.NavigationIcon.FORK_LEFT
                LegacySide.RIGHT.number -> NightOverlayState.NavigationIcon.FORK_RIGHT
                else -> null
            }
        }
    }

    /**
     * Maps instrument-cluster [NavigationManeuver.NavigationType] to legacy broadcast
     * [NextTurnDetail.NextEvent] wire values expected by external consumers.
     */
    @Deprecated(
        message = "Remove together with FullNavigationMessage.nextEventType and NavigationUpdateIntent.nextEventType.",
        level = DeprecationLevel.WARNING
    )
    private fun maneuverTypeToLegacyNextEvent(
        type: NavigationStatus.NavigationManeuver.NavigationType
    ): Int {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN -> LegacyNextEvent.UNKNOWN.number
            NavigationStatus.NavigationManeuver.NavigationType.DEPART -> LegacyNextEvent.DEPART.number
            NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE -> LegacyNextEvent.NAME_CHANGE.number
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT -> LegacyNextEvent.STRAIGHT.number
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT -> LegacyNextEvent.SLIGHT_TURN.number
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT -> LegacyNextEvent.TURN.number
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT -> LegacyNextEvent.SHARP_TURN.number
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT -> LegacyNextEvent.U_TURN.number
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT -> LegacyNextEvent.ON_RAMP.number
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT -> LegacyNextEvent.OFFRAMP.number
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT -> LegacyNextEvent.FORK.number
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED -> LegacyNextEvent.MERGE.number
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER -> LegacyNextEvent.ROUNDABOUT_ENTER.number
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT -> LegacyNextEvent.ROUNDABOUT_EXIT.number
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE ->
                LegacyNextEvent.ROUNDABOUT_ENTER_AND_EXIT.number
            NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT -> LegacyNextEvent.STRAIGHT.number
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT -> LegacyNextEvent.FERRY_BOAT.number
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN -> LegacyNextEvent.FERRY_TRAIN.number
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_STRAIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> LegacyNextEvent.DESTINATION.number
        }
    }

    /**
     * Maps [NavigationManeuver.NavigationType] to legacy [NextTurnDetail.Side] (1=LEFT, 2=RIGHT).
     * Returns null when side is not meaningful → [NavigationUpdateIntent] uses UNSPECIFIED (3).
     */
    @Deprecated(
        message = "Remove together with FullNavigationMessage.turnSide and NavigationUpdateIntent.turnSide.",
        level = DeprecationLevel.WARNING
    )
    private fun maneuverTypeToLegacyTurnSide(
        type: NavigationStatus.NavigationManeuver.NavigationType
    ): Int? {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT -> LegacySide.LEFT.number

            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> LegacySide.RIGHT.number

            else -> null
        }
    }

    private fun maneuverTypeToAction(type: NavigationStatus.NavigationManeuver.NavigationType): String {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.DEPART -> context.getString(R.string.nav_action_depart)
            NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE -> context.getString(R.string.nav_action_name_change)
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED -> context.getString(R.string.nav_action_turn)
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> context.getString(R.string.nav_action_roundabout)
            NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT -> context.getString(R.string.nav_action_straight)
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT -> context.getString(R.string.nav_action_ferry)
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN -> context.getString(R.string.nav_action_ferry_train)
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_STRAIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> context.getString(R.string.nav_action_destination)
            NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN -> context.getString(R.string.nav_action_unknown)
        }
    }

    companion object {
        const val NAV_CHANNEL_ID = "headunit_navigation"
        private const val NAV_NOTIFICATION_ID = 2

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NAV_CHANNEL_ID,
                    context.getString(R.string.nav_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.nav_notification_channel_description)
                    setShowBadge(false)
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
        }
    }
}
