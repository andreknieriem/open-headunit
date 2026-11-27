package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeAvailability.Unavailable
import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySignerPolicy.Choice
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPolicy
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPolicy.Attach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayNativeAvailabilityTest {

    @Test fun theFirstReasonWinsInTheOrderOfTheAttachVerdicts() {
        assertEquals(Unavailable.BelowApiFloor, CarPlayNativeAvailability.check(20, false, Choice.NONE))
        assertEquals(Unavailable.NoTunnel, CarPlayNativeAvailability.check(21, false, Choice.NONE))
        assertEquals(Unavailable.NoSigner, CarPlayNativeAvailability.check(21, true, Choice.NONE))
        assertNull(CarPlayNativeAvailability.check(21, true, Choice.FILE))
    }

    @Test fun everySignerChoiceButNoneCounts() {
        for (choice in listOf(Choice.CHIP, Choice.LINK, Choice.FILE)) assertNull(CarPlayNativeAvailability.check(34, true, choice))
    }

    @Test fun theFloorItselfIsAvailable() {
        assertNull(CarPlayNativeAvailability.check(IphoneUsbPolicy.API_FLOOR, true, Choice.FILE))
        assertEquals(Unavailable.BelowApiFloor, CarPlayNativeAvailability.check(IphoneUsbPolicy.API_FLOOR - 1, true, Choice.FILE))
    }

    @Test fun theToggleAndTheAttachVerdictAgreeForEveryInput() {
        for (sdk in listOf(16, 20, 21, 35)) for (tun in listOf(false, true)) for (choice in Choice.values()) {
            val verdict = IphoneUsbPolicy.attach(
                isIphone = true, enabled = true, sdk = sdk, tunAvailable = tun, signerAvailable = choice != Choice.NONE,
                sessionLive = false, userExited = false, aaSessionLive = false, hasPermission = true, configurationCount = 6,
            )
            val reason = CarPlayNativeAvailability.check(sdk, tun, choice)
            assertEquals("sdk $sdk tun $tun $choice", reason?.verdict ?: Attach.Open, verdict)
        }
    }

    @Test fun eachStoppingVerdictHasALineAndTheOthersHaveNone() {
        val named = listOf(Attach.BelowApiFloor, Attach.NoTunnel, Attach.NoSigner, Attach.AlreadyLive, Attach.UserExited, Attach.RefusedAaLive)
        for (verdict in named) assertEquals(true, CarPlayNativeAvailability.line(verdict, 19)!!.startsWith("CarPlayNative: "))
        for (verdict in Attach.values().filter { it !in named }) assertNull(CarPlayNativeAvailability.line(verdict, 19))
        assertEquals("CarPlayNative: refused, an Android Auto session is live", CarPlayNativeAvailability.line(Attach.RefusedAaLive, 34))
        assertEquals("CarPlayNative: iPhone attached; native CarPlay needs API 21, this unit is 19", CarPlayNativeAvailability.line(Attach.BelowApiFloor, 19))
    }

    @Test fun aStoredTrueCountsOnlyWhileTheUnitCanRunASession() {
        assertEquals(true, CarPlayNativeAvailability.effectiveEnabled(true, null))
        for (reason in Unavailable.values()) assertEquals("$reason", false, CarPlayNativeAvailability.effectiveEnabled(true, reason))
        assertEquals(false, CarPlayNativeAvailability.effectiveEnabled(false, null))
    }
}
