package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Handles navigation messages from the ID_NAV channel from any Android Auto-enabled app
 * (Google Maps, Yandex Maps, etc.). Shows notifications with turn-by-turn directions and the road names.
 */
internal class AapNavigation(
    private val context: Context,
    private val settings: Settings,
    private val presentation: PresentationQueue,
) {
    private val helper = AapNavigationHelper(context)
    private val snapshot = AapNavigationHelper.NavigationSnapshot()
    fun process(message: AapMessage): Boolean {
        if (message.channel != Channel.ID_NAV) return false

        return when (message.type) {
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE -> {
                AppLog.d("Nav: Instrument cluster start")
                clearAccumulatedData()
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_START)
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> {
                AppLog.d("Nav: Instrument cluster stop")
                clearAccumulatedData()
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STOP)
                scheduleNotificationCancel()
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> {
                try {
                    val status = message.parse(NavigationStatus.NavigationClusterStatus.newBuilder()).build()
                    AppLog.d("Nav: Navigation status=${status.status}")
                    updateClusterStatus(status)
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATUS)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationClusterStatus", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> {
                try {
                    val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).buildPartial()
                    snapshot.nextTurnDetail = AapNavigationHelper.TimedMessage(detail, helper.nowElapsedRealtimeMs())
                    AppLog.d(
                        "Nav: NextTurnDetail road=${detail.road} " +
                                "hasNextTurn=${detail.hasNextTurn()} nextTurn=${detail.nextTurn}"
                    )
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        scheduleNotification(null)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDetail", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                try {
                    val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).buildPartial()
                    snapshot.nextTurnDistance = AapNavigationHelper.TimedMessage(event, helper.nowElapsedRealtimeMs())
                    val distanceMeters = event.distanceMeters.takeIf { it >= 0 }
                    AppLog.d(
                        "Nav: NextTurnDistanceEvent hasDistance=${event.hasDistanceMeters()} " +
                                "distance=${event.distanceMeters} hasTime=${event.hasTimeToTurnSeconds()} " +
                                "time=${event.timeToTurnSeconds}"
                    )
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        scheduleNotification(distanceMeters)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDistanceEvent", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> {
                try {
                    val state = message.parse(NavigationStatus.NavigationState.newBuilder()).build()
                    snapshot.navigationState = AapNavigationHelper.TimedMessage(state, helper.nowElapsedRealtimeMs())
                    AppLog.d("Nav: NavigationState steps=${state.stepsCount} maneuverRoad=${NavigationRoadPolicy.maneuverRoad(state, null) ?: "(none)"}")
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATE)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationState", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> {
                try {
                    val position = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).build()
                    snapshot.currentPosition = AapNavigationHelper.TimedMessage(position, helper.nowElapsedRealtimeMs())
                    AppLog.d("Nav: CurrentPosition currentRoad=${NavigationRoadPolicy.currentRoad(position) ?: "(none)"}")
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_CURRENT_POSITION)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationCurrentPosition", e)
                    true
                }
            }
            else -> {
                AppLog.d("Nav: passthrough type ${message.type}")
                false
            }
        }
    }

    private fun clearAccumulatedData() {
        snapshot.clusterStatus = null
        snapshot.nextTurnDetail = null
        snapshot.nextTurnDistance = null
        snapshot.navigationState = null
        snapshot.currentPosition = null
    }

    private fun clearAccumulatedDataPreservingStatus(
        status: AapNavigationHelper.TimedMessage<NavigationStatus.NavigationClusterStatus>
    ) {
        clearAccumulatedData()
        snapshot.clusterStatus = status
    }

    private fun updateClusterStatus(status: NavigationStatus.NavigationClusterStatus) {
        val now = helper.nowElapsedRealtimeMs()
        val newStatus = AapNavigationHelper.TimedMessage(status, now)
        val previous = snapshot.clusterStatus?.payload?.status
        val changed = previous != null && previous != status.status
        if (changed) {
            clearAccumulatedDataPreservingStatus(newStatus)
            scheduleNotificationCancel()
        } else {
            snapshot.clusterStatus = newStatus
        }
    }

    private fun scheduleDebouncedBroadcast(navEventType: Int) {
        // TimedMessage contains immutable protobufs; copy the mutable container on the reader.
        // Main must never inspect the snapshot while the next packet is changing it.
        val copy = snapshot.copy()
        presentation.submit(PresentationQueue.Slot.NAV_BROADCAST, BROADCAST_DEBOUNCE_MS) {
            helper.sendFullNavigationBroadcast(copy, navEventType)
        }
    }

    private fun scheduleNotification(distanceMeters: Int?) {
        val copy = snapshot.copy()
        presentation.submit(PresentationQueue.Slot.NAV_NOTIFICATION) {
            if (settings.showNavigationNotifications) helper.showNotificationForSnapshot(copy, distanceMeters)
            else helper.cancelNotification()
        }
    }

    private fun scheduleNotificationCancel() {
        presentation.submit(PresentationQueue.Slot.NAV_NOTIFICATION) { helper.cancelNotification() }
    }

    /** Called on the presentation executor after session retirement, not on the reader. */
    fun cancelNotification() = helper.cancelNotification()

    companion object {
        private const val BROADCAST_DEBOUNCE_MS = 1000L
        private const val NAV_EVENT_TYPE_TURN = 0
        private const val NAV_EVENT_TYPE_START = 1
        private const val NAV_EVENT_TYPE_STOP = 2
        private const val NAV_EVENT_TYPE_STATUS = 3
        private const val NAV_EVENT_TYPE_STATE = 4
        private const val NAV_EVENT_TYPE_CURRENT_POSITION = 5

        fun createNotificationChannel(context: Context) {
            AapNavigationHelper.createNotificationChannel(context)
        }
    }
}