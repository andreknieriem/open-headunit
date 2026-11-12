package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.maintenance.MaintenanceDuePolicy.State
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaintenanceDuePolicyTest {

    private fun at(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month, day, 12, 0)
    }.timeInMillis

    private val serviced = at(2026, Calendar.MARCH, 14)

    private fun item(
        intervalMeters: Long? = 10_000_000,
        intervalMonths: Int? = 12,
        warnMeters: Long = 1_000_000,
        warnDays: Int = 30,
    ) = MaintenanceItem(
        id = "oil", templateId = "engine_oil", customName = null,
        intervalMeters = intervalMeters, intervalMonths = intervalMonths,
        warnMeters = warnMeters, warnDays = warnDays, mode = MaintenanceMode.REMIND,
        lastMeters = 50_000_000, lastMs = serviced,
    )

    @Test
    fun `far from both limits is fine`() {
        val due = MaintenanceDuePolicy.evaluate(item(), 52_000_000, at(2026, Calendar.APRIL, 1))
        assertEquals(State.OK, due.state)
        assertEquals(60_000_000L, due.dueMeters)
        assertEquals(8_000_000L, due.remainingMeters)
        assertEquals(0.2, due.progress, 1e-9)
    }

    @Test
    fun `inside the distance warning is coming up`() {
        val due = MaintenanceDuePolicy.evaluate(item(), 59_500_000, at(2026, Calendar.JUNE, 1))
        assertEquals(State.COMING_UP, due.state)
        assertEquals(500_000L, due.remainingMeters)
    }

    @Test
    fun `inside the date warning is coming up even with distance left`() {
        val due = MaintenanceDuePolicy.evaluate(item(), 51_000_000, at(2027, Calendar.MARCH, 1))
        assertEquals(State.COMING_UP, due.state)
    }

    @Test
    fun `whichever limit passes first makes it overdue`() {
        assertEquals(State.OVERDUE, MaintenanceDuePolicy.evaluate(item(), 60_000_000, at(2026, Calendar.JUNE, 1)).state)
        assertEquals(State.OVERDUE, MaintenanceDuePolicy.evaluate(item(), 51_000_000, at(2027, Calendar.APRIL, 1)).state)
    }

    @Test
    fun `months are calendar months`() {
        val due = MaintenanceDuePolicy.evaluate(item(), 50_000_000, serviced)
        assertEquals(at(2027, Calendar.MARCH, 14), due.dueMs)
    }

    @Test
    fun `an item due by date only has no distance figures`() {
        val due = MaintenanceDuePolicy.evaluate(item(intervalMeters = null, warnMeters = 0), 99_000_000, at(2026, Calendar.APRIL, 1))
        assertEquals(State.OK, due.state)
        assertNull(due.dueMeters)
        assertNull(due.remainingMeters)
    }

    @Test
    fun `an item due by distance only ignores the calendar`() {
        val due = MaintenanceDuePolicy.evaluate(item(intervalMonths = null), 51_000_000, at(2030, Calendar.JANUARY, 1))
        assertEquals(State.OK, due.state)
        assertNull(due.dueMs)
    }

    @Test
    fun `no last service is unknown`() {
        val unknown = item().copy(lastMeters = null, lastMs = null)
        assertEquals(State.UNKNOWN, MaintenanceDuePolicy.evaluate(unknown, 0, serviced).state)
    }
}
