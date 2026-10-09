package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which vehicle this head unit claims to be, and why the microphone setting can override the user. */
class VehicleTypePolicyTest {

    @Test
    fun `a head unit that records announces what the user picked`() {
        assertEquals(VehicleTypePolicy.CAR, VehicleTypePolicy.vehicleType(VehicleTypePolicy.CAR, true))
        assertEquals(VehicleTypePolicy.TRUCK, VehicleTypePolicy.vehicleType(VehicleTypePolicy.TRUCK, true))
        assertEquals(VehicleTypePolicy.MOTORCYCLE, VehicleTypePolicy.vehicleType(VehicleTypePolicy.MOTORCYCLE, true))
    }

    @Test
    fun `a head unit that will not record claims to be a motorcycle whatever the user picked`() {
        // Any other claim is refused by the phone once the microphone service is withheld.
        assertEquals(VehicleTypePolicy.MOTORCYCLE, VehicleTypePolicy.vehicleType(VehicleTypePolicy.CAR, false))
        assertEquals(VehicleTypePolicy.MOTORCYCLE, VehicleTypePolicy.vehicleType(VehicleTypePolicy.TRUCK, false))
    }

    @Test
    fun `a stored value that is not one of ours reads as a car`() {
        assertEquals(VehicleTypePolicy.CAR, VehicleTypePolicy.sanitised(0))
        assertEquals(VehicleTypePolicy.CAR, VehicleTypePolicy.sanitised(9))
        assertEquals(VehicleTypePolicy.CAR, VehicleTypePolicy.vehicleType(0, true))
    }

    @Test
    fun `the picker maps both ways`() {
        VehicleTypePolicy.SELECTABLE.forEachIndexed { index, type ->
            assertEquals(index, VehicleTypePolicy.indexOf(type))
            assertEquals(type, VehicleTypePolicy.atIndex(index))
        }
        assertEquals(VehicleTypePolicy.CAR, VehicleTypePolicy.atIndex(-1))
        assertEquals(VehicleTypePolicy.CAR, VehicleTypePolicy.atIndex(3))
    }

    @Test
    fun `the numbers are Android Auto's own`() {
        // Its enum is UNSPECIFIED, CAR, TRUCK, MOTORCYCLE. An absent field reads as a car, measured.
        assertEquals(1, VehicleTypePolicy.CAR)
        assertEquals(2, VehicleTypePolicy.TRUCK)
        assertEquals(3, VehicleTypePolicy.MOTORCYCLE)
    }

    @Test
    fun `the microphone note shows only when the pick is overridden`() {
        assertTrue(VehicleTypePolicy.isOverriddenByMicrophone(VehicleTypePolicy.CAR, headUnitMicEnabled = false))
        assertTrue(VehicleTypePolicy.isOverriddenByMicrophone(VehicleTypePolicy.TRUCK, headUnitMicEnabled = false))
        assertFalse(VehicleTypePolicy.isOverriddenByMicrophone(VehicleTypePolicy.MOTORCYCLE, headUnitMicEnabled = false))
        VehicleTypePolicy.SELECTABLE.forEach {
            assertFalse(VehicleTypePolicy.isOverriddenByMicrophone(it, headUnitMicEnabled = true))
        }
    }
}
