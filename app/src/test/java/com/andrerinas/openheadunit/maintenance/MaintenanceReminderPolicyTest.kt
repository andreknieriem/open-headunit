package com.andrerinas.openheadunit.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenanceReminderPolicyTest {

    private val now = 1_700_000_000_000L

    private fun item(id: String, lastMeters: Long, mode: MaintenanceMode = MaintenanceMode.REMIND) = MaintenanceItem(
        id = id, templateId = null, customName = id,
        intervalMeters = 10_000, intervalMonths = null, warnMeters = 1_000, warnDays = 0,
        mode = mode, lastMeters = lastMeters, lastMs = now,
    )

    @Test
    fun `overdue and coming up are split and sorted by urgency`() {
        val items = listOf(
            item("fine", 5_000),
            item("soon", 0),
            item("late", -2_000),
            item("later", -5_000),
            item("sooner", -300),
        )
        val summary = MaintenanceReminderPolicy.summary(items, 9_500, now)
        assertEquals(listOf("later", "late"), summary.overdue.map { it.item.id })
        assertEquals(listOf("sooner", "soon"), summary.comingUp.map { it.item.id })
        assertEquals(4, summary.needsAttention)
    }

    @Test
    fun `track only and off items never ask for attention`() {
        val items = listOf(item("tracked", -9_000, MaintenanceMode.TRACK), item("off", -9_000, MaintenanceMode.OFF))
        assertEquals(0, MaintenanceReminderPolicy.summary(items, 9_500, now).needsAttention)
    }
}
