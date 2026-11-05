package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.maintenance.MaintenanceHomeStatusPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaintenanceHomeStatusPolicyTest {

    private val now = 1_700_000_000_000L
    private val day = MaintenanceDuePolicy.DAY_MS

    private fun entry(remainingMeters: Long?, warnMeters: Long = 1_000) = MaintenanceReminderPolicy.Entry(
        MaintenanceItem(
            id = "x", templateId = null, customName = "x", intervalMeters = 10_000, intervalMonths = 12,
            warnMeters = warnMeters, warnDays = 30, mode = MaintenanceMode.REMIND, lastMeters = 0, lastMs = now,
        ),
        MaintenanceDuePolicy.Due(
            MaintenanceDuePolicy.State.COMING_UP, null, remainingMeters, now + 10 * day, 10 * day, 0.9,
        ),
    )

    private fun summary(overdue: Int = 0, comingUp: List<MaintenanceReminderPolicy.Entry> = emptyList()) =
        MaintenanceReminderPolicy.Summary(List(overdue) { entry(-1) }, comingUp)

    @Test
    fun `an overdue item comes first and counts the rest`() {
        val status = MaintenanceHomeStatusPolicy.status(summary(2, listOf(entry(500))), false, true)
        assertEquals(Kind.OVERDUE, status.kind)
        assertEquals(2, status.more)
    }

    @Test
    fun `missing location comes before a mileage check and before coming up`() {
        assertEquals(Kind.LOCATION_OFF, MaintenanceHomeStatusPolicy.status(summary(comingUp = listOf(entry(500))), false, true).kind)
        assertEquals(Kind.CHECK_MILEAGE, MaintenanceHomeStatusPolicy.status(summary(comingUp = listOf(entry(500))), true, true).kind)
    }

    @Test
    fun `coming up says whether distance or date brings it close`() {
        assertEquals(Kind.COMING_UP_DISTANCE, MaintenanceHomeStatusPolicy.status(summary(comingUp = listOf(entry(500))), true, false).kind)
        assertEquals(Kind.COMING_UP_TIME, MaintenanceHomeStatusPolicy.status(summary(comingUp = listOf(entry(5_000))), true, false).kind)
        assertEquals(Kind.COMING_UP_TIME, MaintenanceHomeStatusPolicy.status(summary(comingUp = listOf(entry(null))), true, false).kind)
    }

    @Test
    fun `nothing pending is all good`() {
        val status = MaintenanceHomeStatusPolicy.status(summary(), true, false)
        assertEquals(Kind.ALL_GOOD, status.kind)
        assertNull(status.entry)
        assertEquals(0, status.more)
    }
}
