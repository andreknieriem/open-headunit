package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.maintenance.MaintenanceBannerPolicy.Placement
import com.andrerinas.openheadunit.maintenance.MaintenanceBannerPolicy.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceBannerPolicyTest {

    private val now = 1_700_000_000_000L
    private val driving = 12f
    private val start = MaintenanceBannerPolicy.State()

    private fun item(id: String, lastMeters: Long, snoozeMeters: Long? = null) = MaintenanceItem(
        id = id, templateId = null, customName = id,
        intervalMeters = 10_000, intervalMonths = null, warnMeters = 1_000, warnDays = 30,
        mode = MaintenanceMode.REMIND, lastMeters = lastMeters, lastMs = now,
        snoozeUntilMeters = snoozeMeters,
    )

    private fun pending(items: List<MaintenanceItem>, odometer: Long = 9_500, setAside: Set<String> = emptySet()) =
        MaintenanceNoticePolicy.pending(MaintenanceReminderPolicy.summary(items, odometer, now), odometer, now, setAside)

    private fun look(
        state: MaintenanceBannerPolicy.State = start,
        enabled: Boolean = true,
        wanted: Boolean = true,
        onScreen: Boolean = true,
        speed: Float? = driving,
        at: Long = 0L,
    ) = MaintenanceBannerPolicy.shouldLook(state, enabled, wanted, onScreen, speed, at)

    @Test
    fun `a moving car with Android Auto on screen looks at the items`() {
        assertTrue(look())
    }

    @Test
    fun `nothing shows while the car stands or the speed is unknown`() {
        assertFalse(look(speed = 0.2f))
        assertFalse(look(speed = null))
        assertTrue(look(speed = OdometerStepPolicy.STILL_SPEED_MPS))
    }

    @Test
    fun `the switches and the projection gate it`() {
        assertFalse(look(enabled = false))
        assertFalse(look(wanted = false))
        assertFalse(look(onScreen = false))
    }

    @Test
    fun `the items are read again only every so often`() {
        val looked = MaintenanceBannerPolicy.looked(start, 1_000)
        assertFalse(look(looked, at = 1_000 + MaintenanceBannerPolicy.LOOK_EVERY_MS - 1))
        assertTrue(look(looked, at = 1_000 + MaintenanceBannerPolicy.LOOK_EVERY_MS))
    }

    @Test
    fun `the most urgent item shows first`() {
        val entries = pending(listOf(item("soon", 0), item("late", -3_000)))
        assertEquals("late", MaintenanceBannerPolicy.pick(start, entries)?.item?.id)
    }

    @Test
    fun `an item shows once per drive and the next waits for the gap`() {
        val entries = pending(listOf(item("soon", 0), item("late", -3_000)))
        val after = MaintenanceBannerPolicy.shown(start, "late", 10_000)
        assertEquals("soon", MaintenanceBannerPolicy.pick(after, entries)?.item?.id)
        assertFalse(look(after, at = 10_000 + MaintenanceBannerPolicy.GAP_MS - 1))
        assertTrue(look(after, at = 10_000 + MaintenanceBannerPolicy.GAP_MS))

        val both = MaintenanceBannerPolicy.shown(after, "soon", 200_000)
        assertNull(MaintenanceBannerPolicy.pick(both, entries))
    }

    @Test
    fun `a new drive lets every item speak again`() {
        val entries = pending(listOf(item("late", -3_000)))
        val shown = MaintenanceBannerPolicy.shown(start, "late", 10_000)
        assertNull(MaintenanceBannerPolicy.pick(shown, entries))
        assertEquals("late", MaintenanceBannerPolicy.pick(MaintenanceBannerPolicy.driveEnded(), entries)?.item?.id)
        assertTrue(look(MaintenanceBannerPolicy.driveEnded(), at = 10_001))
    }

    @Test
    fun `snoozed and set aside items never reach the banner`() {
        val items = listOf(item("late", -3_000), item("snoozed", -3_000, snoozeMeters = 20_000))
        assertNull(MaintenanceBannerPolicy.pick(start, pending(items, setAside = setOf("late"))))
    }

    @Test
    fun `automatic is the top, or the side edge on an ultrawide screen`() {
        assertEquals(Placement.TOP, MaintenanceBannerPolicy.placement(Position.AUTO, 1024, 600))
        assertEquals(Placement.TOP, MaintenanceBannerPolicy.placement(Position.AUTO, 1280, 720))
        assertEquals(Placement.SIDE, MaintenanceBannerPolicy.placement(Position.AUTO, 1920, 720))
        assertEquals(Placement.SIDE, MaintenanceBannerPolicy.placement(Position.AUTO, 1280, 480))
        assertEquals(Placement.TOP, MaintenanceBannerPolicy.placement(Position.AUTO, 1024, 0))
    }

    @Test
    fun `a chosen position is kept whatever the screen`() {
        assertEquals(Placement.TOP, MaintenanceBannerPolicy.placement(Position.TOP, 1920, 720))
        assertEquals(Placement.ABOVE_BAR, MaintenanceBannerPolicy.placement(Position.ABOVE_BAR, 1024, 600))
        assertEquals(Placement.SIDE, MaintenanceBannerPolicy.placement(Position.SIDE, 1024, 600))
    }

    @Test
    fun `an unknown stored position reads as automatic`() {
        assertEquals(Position.AUTO, Position.fromName("SOMEWHERE"))
        assertEquals(Position.AUTO, Position.fromName(null))
        assertEquals(Position.SIDE, Position.fromName("SIDE"))
    }

    @Test
    fun `the passenger side follows right-hand drive`() {
        assertTrue(MaintenanceBannerPolicy.passengerOnLeft(rightHandDrive = true))
        assertFalse(MaintenanceBannerPolicy.passengerOnLeft(rightHandDrive = false))
    }
}
