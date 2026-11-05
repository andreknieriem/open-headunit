package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.PanelDensityPolicy.Entry
import com.andrerinas.openheadunit.utils.PanelDensityPolicy.Panels
import com.andrerinas.openheadunit.utils.SystemOptimizer.DisplaySizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PanelDensityPolicyTest {
    private val global = Entry(300, false)
    private val a = "2376x968"
    private val b = "2160x1856"

    @Test
    fun `key puts the long side first and differs per panel`() {
        assertEquals("2376x968", PanelDensityPolicy.key(2376, 968))
        assertEquals(PanelDensityPolicy.key(2376, 968), PanelDensityPolicy.key(968, 2376))
        assertEquals("2160x1856", PanelDensityPolicy.key(2160, 1856))
    }

    @Test
    fun `key is null below the minimum sdk or for a bad size`() {
        assertNull(PanelDensityPolicy.key(2376, 968, 27))
        assertNull(PanelDensityPolicy.key(0, 0))
        assertNull(PanelDensityPolicy.key(-1, 968))
    }

    @Test
    fun `resolve returns the mapped entry or the global one`() {
        val map = mapOf(a to Entry(175, false))
        assertEquals(Entry(175, false), PanelDensityPolicy.resolve(map, a, true, global))
        assertEquals(global, PanelDensityPolicy.resolve(map, b, true, global))
        assertEquals(global, PanelDensityPolicy.resolve(emptyMap(), a, true, global))
    }

    @Test
    fun `an entry of zero beats a non-zero global value`() {
        val map = mapOf(a to Entry(0, false))
        assertEquals(Entry(0, false), PanelDensityPolicy.resolve(map, a, true, global))
    }

    @Test
    fun `resolve and save ignore the map without a key or while off`() {
        val map = mapOf(a to Entry(175, false))
        assertEquals(global, PanelDensityPolicy.resolve(map, null, true, global))
        assertEquals(global, PanelDensityPolicy.resolve(map, a, false, global))
        assertNull(PanelDensityPolicy.save(map, null, true, Entry(132, false), global))
        assertNull(PanelDensityPolicy.save(map, a, false, Entry(132, false), global))
    }

    @Test
    fun `save keeps the other panel and adds no entry for the value in force`() {
        val map = mapOf(b to Entry(175, false))
        val saved = PanelDensityPolicy.save(map, a, true, Entry(132, false), global)!!
        assertEquals(Entry(175, false), saved[b])
        assertEquals(Entry(132, false), saved[a])
        val same = PanelDensityPolicy.save(map, a, true, global, global)
        assertSame(map, same)
    }

    @Test
    fun `format then parse round-trips`() {
        val map = linkedMapOf(a to Entry(175, false), b to Entry(0, true))
        assertEquals("2376x968=175;2160x1856=0!", PanelDensityPolicy.format(map))
        assertEquals(map, PanelDensityPolicy.parse(PanelDensityPolicy.format(map)))
    }

    @Test
    fun `format then parse round-trips a layout, and an entry without one reads as plain Automatic`() {
        val map = linkedMapOf(a to Entry(0, false, "ONE_APP"), b to Entry(400, true, "COMPACT"))
        assertEquals("2376x968=0@ONE_APP;2160x1856=400!@COMPACT", PanelDensityPolicy.format(map))
        assertEquals(map, PanelDensityPolicy.parse(PanelDensityPolicy.format(map)))
        assertEquals(DisplayLayoutPolicy.Pane.ONE_APP, map.getValue(a).pane)
        assertNull(PanelDensityPolicy.parse("2376x968=0").getValue(a).pane)
        assertNull(PanelDensityPolicy.parse("2376x968=0@NO_SUCH_PANE").getValue(a).pane)
    }

    @Test
    fun `a layout saved on one panel leaves the other panel's layout`() {
        val map = mapOf(b to Entry(0, false, "COMPACT"))
        val saved = PanelDensityPolicy.save(map, a, true, Entry(0, false, "ONE_APP"), global)!!
        assertEquals("COMPACT", PanelDensityPolicy.resolve(saved, b, true, global).layout)
        assertEquals("ONE_APP", PanelDensityPolicy.resolve(saved, a, true, global).layout)
    }

    @Test
    fun `parse returns an empty map for any bad string`() {
        listOf(null, "", "garbage", "2376x968", "2376x968=abc", "2376x968=-1", "2376x968=175;bad",
            "2376x968=175@", "2376x968=175@one", "2376x968=@ONE_APP")
            .forEach { assertEquals(it, emptyMap<String, Entry>(), PanelDensityPolicy.parse(it)) }
    }

    @Test
    fun `a floor override applies to its own panel only`() {
        val map = mapOf(a to Entry(300, true), b to Entry(300, false))
        fun decide(key: String?): AnnouncedDensityPolicy.Decision {
            val e = PanelDensityPolicy.resolve(map, key, true, Entry(300, false))
            return AnnouncedDensityPolicy.decide(e.dpi, DisplaySizePreset.STANDARD_9_10, 1280, 720, floorOverride = e.floorOverride, autoLayout = e.pane)
        }
        assertEquals(AnnouncedDensityPolicy.Reason.FLOOR_OVERRIDE, decide(a).reason)
        assertEquals(300, decide(a).announced)
        assertEquals(AnnouncedDensityPolicy.Reason.HEIGHT_FLOOR, decide(b).reason)
        assertEquals(256, decide(b).announced)
        assertEquals(256, decide("1000x1000").announced)
    }

    @Test
    fun `observe turns on at a second key and never off`() {
        var s = Panels(null, false)
        s = PanelDensityPolicy.observe(s, a)
        s = PanelDensityPolicy.observe(s, PanelDensityPolicy.key(968, 2376))
        s = PanelDensityPolicy.observe(s, null)
        assertEquals(Panels(a, false), s)
        s = PanelDensityPolicy.observe(s, b)
        assertEquals(Panels(a, true), s)
        assertEquals(Panels(a, true), PanelDensityPolicy.observe(s, a))
    }
}
