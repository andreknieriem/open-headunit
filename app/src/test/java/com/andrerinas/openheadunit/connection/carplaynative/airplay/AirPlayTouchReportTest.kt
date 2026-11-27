package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayTouchReport.Contact
import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayTouchReport.Knob
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `touch` and `knob` vectors come from LIVI `ts_vectors.json`; the other cases from LIVI `hid.rs` tests. */
class AirPlayTouchReportTest {

    @Test
    fun theLiviTouchVectorMatches() {
        val report = AirPlayTouchReport.touch(listOf(Contact(0, 100.5, 719.49, true), Contact(1, 3.0, 4.0, false)))
        assertEquals("00016500cf02010003000400", report.toHex())
    }

    @Test
    fun theLiviKnobVectorMatches() {
        assertEquals("018104ff", AirPlayTouchReport.knob(Knob(select = true, x = -200.0, y = 3.5, wheel = -1.0)).toHex())
    }

    @Test
    fun oneContactFillsItsSlotAndTheOtherReadsUp() {
        assertArrayEquals(
            byteArrayOf(0, 1, 100, 0, 51, 0, 1, 0, 0, 0, 0, 0),
            AirPlayTouchReport.touch(listOf(Contact(0, 100.4, 50.6, true))),
        )
    }

    @Test
    fun theReportClampsAtTheEdges() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 1, 1, 0x2c, 1, 2, 0),
            AirPlayTouchReport.touch(listOf(Contact(0, -3.0, 70000.0, false), Contact(1, 300.0, 2.0, true))),
        )
        assertArrayEquals(
            byteArrayOf(0, 1, 0xff.toByte(), 0xff.toByte(), 0, 0, 1, 0, 0, 0, 0, 0),
            AirPlayTouchReport.touch(listOf(Contact(0, 65535.4, 0.0, true), Contact(1, Double.NaN, Double.NaN, false))),
        )
    }

    @Test
    fun aContactFillsTheSlotOfItsIdNotItsListPosition() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 10, 0, 20, 0),
            AirPlayTouchReport.touch(listOf(Contact(1, 10.0, 20.0, true))),
        )
    }

    @Test
    fun aContactOutsideTheTwoSlotsIsIgnored() {
        assertArrayEquals(
            ByteArray(12).also { it[6] = 1 },
            AirPlayTouchReport.touch(listOf(Contact(2, 5.0, 5.0, true), Contact(-1, 5.0, 5.0, true))),
        )
    }

    @Test
    fun theKnobPacksButtonsAndClampsAxes() {
        assertArrayEquals(
            byteArrayOf(0b110, 0x81.toByte(), 5, 1),
            AirPlayTouchReport.knob(Knob(home = true, back = true, x = -300.0, y = 5.4, wheel = 1.0)),
        )
        assertArrayEquals(byteArrayOf(0, 127, 127, 0), AirPlayTouchReport.knob(Knob(x = 500.0, y = 127.4, wheel = Double.NaN)))
    }

    @Test
    fun toDisplayScalesThePictureToTheDeclaredDisplay() {
        val mapped = AirPlayTouchReport.toDisplay(listOf(Contact(1, 160.0, 120.0, true)), 320, 240, 1280, 720)
        assertEquals(1, mapped[0].id)
        assertEquals(640.0, mapped[0].x, 0.0)
        assertEquals(360.0, mapped[0].y, 0.0)
        assertEquals(true, mapped[0].down)
    }

    @Test
    fun toDisplayClampsToTheDeclaredSizeNotTo65535() {
        val mapped = AirPlayTouchReport.toDisplay(
            listOf(Contact(0, 9999.0, -5.0, true), Contact(1, Double.NaN, 240.0, true)), 320, 240, 1280, 720,
        )
        assertEquals(1280.0, mapped[0].x, 0.0)
        assertEquals(0.0, mapped[0].y, 0.0)
        assertEquals(0.0, mapped[1].x, 0.0)
        assertEquals(720.0, mapped[1].y, 0.0)
    }

    @Test
    fun toDisplayGivesNothingForASizeOfZero() {
        assertEquals(emptyList<Contact>(), AirPlayTouchReport.toDisplay(listOf(Contact(0, 1.0, 1.0, true)), 0, 240, 1280, 720))
        assertEquals(emptyList<Contact>(), AirPlayTouchReport.toDisplay(listOf(Contact(0, 1.0, 1.0, true)), 320, 240, 1280, 0))
    }
}
