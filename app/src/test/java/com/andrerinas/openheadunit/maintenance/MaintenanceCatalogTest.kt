package com.andrerinas.openheadunit.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceCatalogTest {

    private val now = 1_700_000_000_000L

    private fun ids(items: List<MaintenanceItem>) = items.map { it.templateId }

    @Test
    fun `a manual car with hydraulic steering gets its gearbox oil and the steering fluid`() {
        val items = MaintenanceCatalog.build(Gearbox.MANUAL, Steering.HYDRAULIC, false, 50_000_000, now)
        assertEquals(12, items.size)
        assertTrue(MaintenanceCatalog.MANUAL_GEARBOX_OIL in ids(items))
        assertFalse(MaintenanceCatalog.AUTOMATIC_GEARBOX_FLUID in ids(items))
        assertFalse(MaintenanceCatalog.CVT_FLUID in ids(items))
        assertEquals(MaintenanceMode.REMIND, items.first { it.templateId == MaintenanceCatalog.POWER_STEERING_FLUID }.mode)
    }

    @Test
    fun `electric steering leaves the fluid out and not sure adds it switched off`() {
        val electric = MaintenanceCatalog.build(Gearbox.CVT, Steering.ELECTRIC, false, 0, now)
        assertFalse(MaintenanceCatalog.POWER_STEERING_FLUID in ids(electric))
        assertTrue(MaintenanceCatalog.CVT_FLUID in ids(electric))
        val unsure = MaintenanceCatalog.build(Gearbox.AUTOMATIC, Steering.UNSURE, false, 0, now)
        assertEquals(MaintenanceMode.OFF, unsure.first { it.templateId == MaintenanceCatalog.POWER_STEERING_FLUID }.mode)
    }

    @Test
    fun `every item starts as just serviced at the typed odometer`() {
        val items = MaintenanceCatalog.build(Gearbox.MANUAL, Steering.ELECTRIC, false, 123_000_000, now)
        assertTrue(items.all { it.lastMeters == 123_000_000L && it.lastMs == now && it.history.isEmpty() })
    }

    @Test
    fun `the battery check is due by date only`() {
        val battery = MaintenanceCatalog.build(Gearbox.MANUAL, Steering.ELECTRIC, false, 0, now)
            .first { it.templateId == MaintenanceCatalog.BATTERY_CHECK }
        assertNull(battery.intervalMeters)
        assertEquals(12, battery.intervalMonths)
        assertEquals(0L, battery.warnMeters)
        assertEquals(36, battery.warnDays)
    }

    @Test
    fun `the default warning is the last tenth of the interval`() {
        val oil = MaintenanceCatalog.build(Gearbox.MANUAL, Steering.ELECTRIC, false, 0, now)
            .first { it.templateId == MaintenanceCatalog.ENGINE_OIL }
        assertEquals(10_000_000L, oil.intervalMeters)
        assertEquals(1_000_000L, oil.warnMeters)
        assertEquals(36, oil.warnDays)
        assertEquals(3, MaintenanceCatalog.defaultWarnDays(1))
    }

    @Test
    fun `miles round to a figure that reads like an interval`() {
        assertEquals(Distance.fromUnits(6_000, true), MaintenanceCatalog.defaultIntervalMeters(10_000, true))
        assertEquals(Distance.fromUnits(600, true), MaintenanceCatalog.defaultIntervalMeters(1_000, true))
        assertEquals(Distance.fromUnits(56_000, true), MaintenanceCatalog.defaultIntervalMeters(90_000, true))
        assertEquals(10_000_000L, MaintenanceCatalog.defaultIntervalMeters(10_000, false))
    }

    @Test
    fun `a custom item starts like the engine oil and carries its name`() {
        val item = MaintenanceCatalog.custom("custom_1", "Wipers", false, 5_000, now)
        assertNull(item.templateId)
        assertEquals("Wipers", item.customName)
        assertEquals(10_000_000L, item.intervalMeters)
        assertEquals(12, item.intervalMonths)
        assertEquals(MaintenanceMode.REMIND, item.mode)
    }

    @Test
    fun `changing the gearbox swaps only its item and keeps everything else`() {
        val start = MaintenanceCatalog.build(Gearbox.MANUAL, Steering.HYDRAULIC, false, 0, now)
        val serviced = start.map {
            if (it.templateId == MaintenanceCatalog.ENGINE_OIL) it.copy(history = listOf(ServiceRecord(1, 2))) else it
        } + MaintenanceCatalog.custom("custom_1", "Wipers", false, 0, now)

        val result = MaintenanceCatalog.reconcile(serviced, Gearbox.CVT, Steering.HYDRAULIC, false, 9_000, now + 1)

        assertFalse(MaintenanceCatalog.MANUAL_GEARBOX_OIL in ids(result))
        val cvt = result.first { it.templateId == MaintenanceCatalog.CVT_FLUID }
        assertEquals(9_000L, cvt.lastMeters)
        assertEquals(1, result.first { it.templateId == MaintenanceCatalog.ENGINE_OIL }.history.size)
        assertEquals("Wipers", result.last().customName)
        // Built-in items stay in catalog order.
        val builtIn = result.mapNotNull { it.templateId }
        assertEquals(builtIn.sortedBy { id -> MaintenanceCatalog.templates.indexOfFirst { it.id == id } }, builtIn)
    }

    @Test
    fun `electric steering removes the fluid and the same car changes nothing`() {
        val start = MaintenanceCatalog.build(Gearbox.MANUAL, Steering.HYDRAULIC, false, 0, now)
        val electric = MaintenanceCatalog.reconcile(start, Gearbox.MANUAL, Steering.ELECTRIC, false, 0, now)
        assertFalse(MaintenanceCatalog.POWER_STEERING_FLUID in ids(electric))
        assertEquals(start, MaintenanceCatalog.reconcile(start, Gearbox.MANUAL, Steering.HYDRAULIC, false, 5, now + 5))
    }
}
