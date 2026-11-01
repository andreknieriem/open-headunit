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
        whenever(it.getString(R.string.nav_action_roundabout)).thenReturn("Roundabout")
        whenever(it.getString(R.string.night_overlay_left)).thenReturn("left")
        whenever(it.getString(R.string.night_overlay_direction, "Turn", "left")).thenReturn("Turn left")
        whenever(it.getString(R.string.night_overlay_exit_number, 3)).thenReturn("Exit 3")
        whenever(it.getString(R.string.night_overlay_exit_angle, 120)).thenReturn("120 deg")
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
                    timeToManeuverSeconds = null,
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
                    timeToManeuverSeconds = 30,
                    icon = NightOverlayState.NavigationIcon.TURN_LEFT
                ),
                received.last()
            )
        }
    }

    @Suppress("DEPRECATION")
    @Test fun broadcastKeepsTurnNumberAndAngleForOrdinaryTurnsButOverlayHidesDetails() {
        val context = context()
        val received = mutableListOf<NightOverlayState.Navigation?>()
        val helper = AapNavigationHelper(context) { received.add(it) }
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            nextTurnDetail = AapNavigationHelper.TimedMessage(
                NavigationStatus.NextTurnDetail.newBuilder()
                    .setNextTurn(NavigationStatus.NextTurnDetail.NextEvent.TURN)
                    .setSide(NavigationStatus.NextTurnDetail.Side.LEFT)
                    .setTurnNumber(2)
                    .setTurnAngle(135)
                    .buildPartial(),
                0L
            )
        )
        var constructorArgs: List<*>? = null
        mockConstruction(NavigationUpdateIntent::class.java) { _, construction ->
            constructorArgs = construction.arguments()
        }.use {
            helper.sendFullNavigationBroadcast(snapshot, 0)
            assertNull(received.last()?.details)
            assertEquals(2, constructorArgs?.get(6))
            assertEquals(135, constructorArgs?.get(7))
        }
    }

    @Test fun overlayShowsDetailsOnlyForRoundaboutManeuvers() {
        val context = context()
        val received = mutableListOf<NightOverlayState.Navigation?>()
        val helper = AapNavigationHelper(context) { received.add(it) }
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            navigationState = AapNavigationHelper.TimedMessage(
                NavigationStatus.NavigationState.newBuilder().addSteps(
                    NavigationStatus.NavigationStep.newBuilder().setManeuver(
                        NavigationStatus.NavigationManeuver.newBuilder()
                            .setType(NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE)
                            .setRoundaboutExitNumber(3)
                            .setRoundaboutExitAngle(120)
                    )
                ).build(),
                0L
            )
        )
        mockConstruction(NavigationUpdateIntent::class.java).use {
            helper.sendFullNavigationBroadcast(snapshot, 0)
            assertEquals("Exit 3 · 120 deg", received.last()?.details)
        }
    }
}
