package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.DriverCandidatePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class NamedPhonePolicyTest {

    private fun paired(mac: String, vararg names: String, verdict: Verdict = Verdict.PHONE) =
        NamedPhonePolicy.Paired(mac, names.toList(), verdict)

    @Test fun `exact name matches the bonded address`() {
        val r = NamedPhonePolicy.resolve("Pixel 7", listOf(paired("AA:00", "Pixel 7")))
        assertEquals(NamedPhonePolicy.Result.Match("AA:00"), r)
    }

    @Test fun `case difference still matches`() {
        val r = NamedPhonePolicy.resolve("pixel 7", listOf(paired("AA:00", "Pixel 7")))
        assertEquals(NamedPhonePolicy.Result.Match("AA:00"), r)
    }

    @Test fun `padding is ignored`() {
        val r = NamedPhonePolicy.resolve("  Pixel 7 ", listOf(paired("AA:00", "Pixel 7")))
        assertEquals(NamedPhonePolicy.Result.Match("AA:00"), r)
    }

    @Test fun `two phones with one name are ambiguous`() {
        val r = NamedPhonePolicy.resolve("Pixel 7", listOf(paired("AA:00", "Pixel 7"), paired("BB:00", "Pixel 7")))
        assertEquals(NamedPhonePolicy.Result.Ambiguous(listOf("AA:00", "BB:00")), r)
    }

    @Test fun `a name only a car carries is not a phone`() {
        val r = NamedPhonePolicy.resolve("Car", listOf(paired("CC:00", "Car", verdict = Verdict.NOT_A_PHONE)))
        assertEquals(NamedPhonePolicy.Result.NotAPhone, r)
    }

    @Test fun `a phone and a car with one name match the phone`() {
        val r = NamedPhonePolicy.resolve(
            "Mine", listOf(paired("CC:00", "Mine", verdict = Verdict.NOT_A_PHONE), paired("AA:00", "Mine"))
        )
        assertEquals(NamedPhonePolicy.Result.Match("AA:00"), r)
    }

    @Test fun `an unknown verdict still matches`() {
        val r = NamedPhonePolicy.resolve("X", listOf(paired("AA:00", "X", verdict = Verdict.UNKNOWN)))
        assertEquals(NamedPhonePolicy.Result.Match("AA:00"), r)
    }

    @Test fun `an alias matches when the remote name differs`() {
        val r = NamedPhonePolicy.resolve("My phone", listOf(paired("AA:00", "SM-G991B", "My phone")))
        assertEquals(NamedPhonePolicy.Result.Match("AA:00"), r)
    }

    @Test fun `equal name and alias on one device are not ambiguous`() {
        val r = NamedPhonePolicy.resolve("P", listOf(paired("AA:00", "P", "P")))
        assertEquals(NamedPhonePolicy.Result.Match("AA:00"), r)
    }

    @Test fun `an empty list or a blank name is not found`() {
        assertEquals(NamedPhonePolicy.Result.NotFound, NamedPhonePolicy.resolve("P", emptyList()))
        assertEquals(NamedPhonePolicy.Result.NotFound, NamedPhonePolicy.resolve("  ", listOf(paired("AA:00", "P"))))
    }

    @Test fun `a lower case mac gives the bonded upper case address`() {
        val r = NamedPhonePolicy.resolveMac("aa:bb:cc:dd:ee:ff", listOf(paired("AA:BB:CC:DD:EE:FF", "P")))
        assertEquals(NamedPhonePolicy.Result.Match("AA:BB:CC:DD:EE:FF"), r)
    }

    @Test fun `an unknown mac is not paired`() {
        val r = NamedPhonePolicy.resolveMac("11:22:33:44:55:66", listOf(paired("AA:BB:CC:DD:EE:FF", "P")))
        assertEquals(NamedPhonePolicy.Result.NotPaired, r)
    }

    @Test fun `a paired mac is trusted even when it is not a phone`() {
        val r = NamedPhonePolicy.resolveMac("CC:00", listOf(paired("CC:00", "Car", verdict = Verdict.NOT_A_PHONE)))
        assertEquals(NamedPhonePolicy.Result.Match("CC:00"), r)
    }
}
