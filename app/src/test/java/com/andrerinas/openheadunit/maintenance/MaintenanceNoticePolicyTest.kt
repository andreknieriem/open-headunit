package com.andrerinas.openheadunit.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceNoticePolicyTest {

    private val now = 1_700_000_000_000L
    private val day = MaintenanceDuePolicy.DAY_MS

    private fun item(
        id: String,
        lastMeters: Long,
        intervalMonths: Int? = null,
        snoozeMeters: Long? = null,
        snoozeMs: Long? = null,
    ) = MaintenanceItem(
        id = id, templateId = null, customName = id,
        intervalMeters = 10_000, intervalMonths = intervalMonths, warnMeters = 1_000, warnDays = 30,
        mode = MaintenanceMode.REMIND, lastMeters = lastMeters, lastMs = now,
        snoozeUntilMeters = snoozeMeters, snoozeUntilMs = snoozeMs,
    )

    private fun pending(items: List<MaintenanceItem>, odometer: Long, setAside: Set<String> = emptySet()) =
        MaintenanceNoticePolicy.pending(MaintenanceReminderPolicy.summary(items, odometer, now), odometer, now, setAside)
            .map { it.item.id }

    @Test
    fun `overdue items come before the ones coming up`() {
        val items = listOf(item("soon", 0), item("late", -3_000), item("fine", 5_000))
        assertEquals(listOf("late", "soon"), pending(items, 9_500))
    }

    @Test
    fun `an item set aside in this run stays quiet`() {
        val items = listOf(item("soon", 0), item("late", -3_000))
        assertEquals(listOf("soon"), pending(items, 9_500, setOf("late")))
    }

    @Test
    fun `a distance snooze holds until the odometer reaches it`() {
        val items = listOf(item("late", -3_000, snoozeMeters = 10_000))
        assertTrue(pending(items, 9_500).isEmpty())
        assertEquals(listOf("late"), pending(items, 10_000))
    }

    @Test
    fun `a date snooze holds until that day`() {
        val snoozed = item("late", -3_000, snoozeMs = now + day)
        assertTrue(MaintenanceNoticePolicy.isSnoozed(snoozed, 9_500, now))
        assertFalse(MaintenanceNoticePolicy.isSnoozed(snoozed, 9_500, now + day))
    }

    @Test
    fun `nothing pending means no notice and no notification`() {
        val plan = MaintenanceNoticePolicy.plan(0, true, true, false, false, false)
        assertFalse(plan.showScreen)
        assertFalse(plan.notify)
    }

    @Test
    fun `each way follows its own switch`() {
        assertEquals(MaintenanceNoticePolicy.Plan(true, false), MaintenanceNoticePolicy.plan(2, true, false, false, false, false))
        assertEquals(MaintenanceNoticePolicy.Plan(false, true), MaintenanceNoticePolicy.plan(2, false, true, false, false, false))
    }

    @Test
    fun `the notice waits while the settings are open or one is already up`() {
        assertFalse(MaintenanceNoticePolicy.plan(1, true, true, settingsOpen = true, noticeShowing = false, notificationDismissed = false).showScreen)
        assertFalse(MaintenanceNoticePolicy.plan(1, true, true, settingsOpen = false, noticeShowing = true, notificationDismissed = false).showScreen)
    }

    @Test
    fun `a notification swiped away in this run is not posted again`() {
        val plan = MaintenanceNoticePolicy.plan(1, false, true, false, false, notificationDismissed = true)
        assertFalse(plan.notify)
    }

    @Test
    fun `later offers distance, dates or both depending on the item`() {
        val byDistance = item("a", 0)
        val both = item("b", 0, intervalMonths = 12)
        val byTime = both.copy(intervalMeters = null)
        assertEquals(3, MaintenanceNoticePolicy.laterChoices(byDistance, miles = false).size)
        assertEquals(6, MaintenanceNoticePolicy.laterChoices(both, miles = false).size)
        assertTrue(MaintenanceNoticePolicy.laterChoices(byTime, miles = false).all { it.meters == null })
        assertEquals(321_869L, MaintenanceNoticePolicy.laterChoices(byDistance, miles = true)[0].meters)
    }

    @Test
    fun `a snooze replaces the previous one`() {
        val snoozedByDate = item("a", 0, snoozeMs = now + day)
        val byDistance = MaintenanceNoticePolicy.snooze(snoozedByDate, MaintenanceNoticePolicy.Later(500_000, null), 9_000, now)
        assertEquals(509_000L, byDistance.snoozeUntilMeters)
        assertNull(byDistance.snoozeUntilMs)
        val byDays = MaintenanceNoticePolicy.snooze(byDistance, MaintenanceNoticePolicy.Later(null, 3), 9_000, now)
        assertNull(byDays.snoozeUntilMeters)
        assertEquals(now + 3 * day, byDays.snoozeUntilMs)
    }

    @Test
    fun `starts heard twice within a minute are one start`() {
        assertTrue(MaintenanceNoticePolicy.isNewStart(0, 5_000))
        assertFalse(MaintenanceNoticePolicy.isNewStart(5_000, 30_000))
        assertTrue(MaintenanceNoticePolicy.isNewStart(5_000, 70_000))
    }

    @Test
    fun `the start notice waits for the car to be standing`() {
        assertTrue(MaintenanceNoticePolicy.startNoticeOnFix(0f))
        assertTrue(MaintenanceNoticePolicy.startNoticeOnFix(null))
        assertFalse(MaintenanceNoticePolicy.startNoticeOnFix(12f))
    }
}
