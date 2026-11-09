package com.andrerinas.openheadunit.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReglinkTripDecoderTest {

    private val decoder = ReglinkTripDecoder()

    private fun tripA(a: String, units: String = "15") =
        "CANBox[IN ] type:0x33, data:01,04,00,16,00,74,00,5e,$a,01,16,$units,03"

    private fun tripB(b: String) =
        "CANBox[IN ] type:0x33, data:05,00,$b,00,5c,00,00,00,00,00,00,00,00,00,00,00,00,00,00,00,1a,03"

    @Test
    fun `reads TRIP A in tenths from the packet the unit logs`() {
        // 0x001646 = 5702, the dashboard's 570.2 km.
        val reading = decoder.onLine(tripA("00,16,46"), 0)!!
        assertEquals(5702L, reading.tripATenths)
        assertNull(reading.tripBTenths)
        assertEquals(false, reading.miles)
    }

    @Test
    fun `TRIP B comes from its own packet and rides along with the next TRIP A`() {
        assertNull(decoder.onLine(tripB("08,1c"), 0))
        val reading = decoder.onLine(tripA("00,16,46"), 100)!!
        assertEquals(0x081cL, reading.tripBTenths)
    }

    @Test
    fun `an old TRIP B is left out`() {
        decoder.onLine(tripB("08,1c"), 0)
        val reading = decoder.onLine(tripA("00,16,46"), ReglinkTripDecoder.TRIP_B_FRESH_MS + 1)!!
        assertNull(reading.tripBTenths)
    }

    @Test
    fun `bit 7 of byte 13 means the dashboard counts in miles`() {
        assertTrue(decoder.onLine(tripA("00,16,46", "95"), 0)!!.miles)
    }

    @Test
    fun `an unchanged reading waits for the heartbeat`() {
        decoder.onLine(tripA("00,16,46"), 0)
        assertNull(decoder.onLine(tripA("00,16,46"), 1_000))
        assertTrue(decoder.onLine(tripA("00,16,46"), ReglinkTripDecoder.HEARTBEAT_MS) != null)
        assertEquals(5703L, decoder.onLine(tripA("00,16,47"), ReglinkTripDecoder.HEARTBEAT_MS + 10)!!.tripATenths)
    }

    @Test
    fun `a counter of all ones has no value`() {
        assertNull(decoder.onLine(tripA("ff,ff,ff"), 0))
        decoder.onLine(tripB("08,1c"), 0)
        val reading = decoder.onLine(tripA("ff,ff,ff"), 10)!!
        assertNull(reading.tripATenths)
        assertEquals(0x081cL, reading.tripBTenths)
    }

    @Test
    fun `other packets, short packets and damaged lines are ignored`() {
        assertNull(decoder.onLine("CANBox[IN ] type:0x7D, data:03,e8,03,00,00", 0))
        assertNull(decoder.onLine("CANBox[IN ] type:0x33, data:02,00,00,00,00,00,00,00,00,00,00,00,00,00,00,00,15,03", 0))
        assertNull(decoder.onLine("CANBox[IN ] type:0x33, data:01,04,00,16", 0))
        assertNull(decoder.onLine("CANBox[IN ] type:0x33, data:01,zz,00", 0))
        assertNull(decoder.onLine("CANBox[IN ] type:0x33, data:01,100,00,16,00,74,00,5e,00,16,46,01,16,15,03", 0))
        assertNull(decoder.onLine("something else entirely", 0))
        assertNull(decoder.onLine("", 0))
    }

    @Test
    fun `a full line from logcat with its prefix still parses`() {
        val line = "11-03 23:44:12.244  1162  2395 D Reglink-Script: " + tripA("00,16,46")
        assertEquals(5702L, decoder.onLine(line, 0)!!.tripATenths)
    }
}
