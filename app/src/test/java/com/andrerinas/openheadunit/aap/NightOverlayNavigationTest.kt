package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.contract.NavigationUpdateIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class NightOverlayNavigationTest {
    private fun context() = mock<Context>().also {
        whenever(it.applicationContext).thenReturn(it)
        whenever(it.getString(R.string.nav_action_unknown)).thenReturn("Unknown")
        whenever(it.getString(R.string.nav_action_turn)).thenReturn("Turn")
        whenever(it.getString(R.string.night_overlay_left)).thenReturn("left")
        whenever(it.getString(R.string.night_overlay_direction, "Turn", "left")).thenReturn("Turn left")
    }

    @Test fun modernNavigationPublishesManeuverDirectionAndDistanceThenClearsOnStop() {
        val context = context()
        val received = mutableListOf<NightOverlayState.Navigation?>()
        val helper = AapNavigationHelper(context) { received.add(it) }
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            navigationState = AapNavigationHelper.TimedMessage(
                NavigationStatus.NavigationState.newBuilder().addSteps(
                    NavigationStatus.NavigationStep.newBuilder().setManeuver(
                        NavigationStatus.NavigationManeuver.newBuilder()
                            .setType(NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT)
                    )
                ).build(), 0L
            ),
            currentPosition = AapNavigationHelper.TimedMessage(
                NavigationStatus.NavigationCurrentPosition.newBuilder().setStepDistance(
                    NavigationStatus.NavigationStepDistance.newBuilder().setDistance(
                        NavigationStatus.NavigationDistance.newBuilder().setMeters(350)
                    )
                ).build(), 0L
            )
        )
        mockConstruction(NavigationUpdateIntent::class.java).use {
            helper.sendFullNavigationBroadcast(snapshot, 4)
            assertEquals(
                NightOverlayState.Navigation(
                    maneuver = "Turn left",
                    distanceMeters = 350,
                    icon = NightOverlayState.NavigationIcon.TURN_LEFT
                ),
                received.last()
            )
            helper.sendFullNavigationBroadcast(AapNavigationHelper.NavigationSnapshot(), 2)
            assertNull(received.last())
        }
    }

    @Suppress("DEPRECATION")
    @Test fun legacyManeuverWithoutDistanceDoesNotInventZeroMeters() {
        val received = mutableListOf<NightOverlayState.Navigation?>()
        val helper = AapNavigationHelper(context()) { received.add(it) }
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            nextTurnDetail = AapNavigationHelper.TimedMessage(
                NavigationStatus.NextTurnDetail.newBuilder()
                    .setNextTurn(NavigationStatus.NextTurnDetail.NextEvent.TURN)
                    .setSide(NavigationStatus.NextTurnDetail.Side.LEFT).buildPartial(), 0L
            ),
            nextTurnDistance = AapNavigationHelper.TimedMessage(
                NavigationStatus.NextTurnDistanceEvent.newBuilder().setTimeToTurnSeconds(30).buildPartial(), 0L
            )
        )
        mockConstruction(NavigationUpdateIntent::class.java).use {
            helper.sendFullNavigationBroadcast(snapshot, 0)
            assertEquals(
                NightOverlayState.Navigation(
                    maneuver = "Turn left",
                    distanceMeters = null,
                    icon = NightOverlayState.NavigationIcon.TURN_LEFT
                ),
                received.last()
            )
        }
    }
}
