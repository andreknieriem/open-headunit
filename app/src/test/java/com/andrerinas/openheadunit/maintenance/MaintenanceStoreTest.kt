package com.andrerinas.openheadunit.maintenance

import android.app.Application
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MaintenanceStoreTest {

    private val now = 1_700_000_000_000L
    private val settings = Settings(RuntimeEnvironment.getApplication())
    private val store = MaintenanceStore(settings).apply {
        setUp(100_000_000, false, Gearbox.MANUAL, Steering.ELECTRIC, now)
    }

    @Test
    fun `setting up turns the feature on with a fresh stretch`() {
        assertTrue(settings.maintenanceEnabled)
        assertEquals(100_000_000L, store.odometerMeters())
        assertEquals(100_000_000L, settings.maintenanceCorrectionBaseMeters)
        assertEquals(11, store.items().size)
    }

    @Test
    fun `distance is held in memory until about a kilometer, then written`() {
        assertFalse(store.addDistance(400.0))
        assertEquals(100_000_400L, store.odometerMeters())
        assertEquals(100_000_000L, settings.maintenanceOdometerMeters)

        assertTrue(store.addDistance(700.0))
        assertEquals(100_001_100L, settings.maintenanceOdometerMeters)
        assertEquals(1_100.0, settings.maintenanceRawSinceCorrectionMeters, 0.0)
    }

    @Test
    fun `the correction factor scales the counted distance`() {
        settings.maintenanceCorrectionFactor = 1.05
        store.addDistance(2_000.0)
        assertEquals(100_002_100L, store.odometerMeters())
        assertEquals(2_000.0, settings.maintenanceRawSinceCorrectionMeters, 0.0)
    }

    @Test
    fun `a correction takes the typed number and drops the distance not written yet`() {
        store.addDistance(300.0)
        val result = store.correct(100_500_000, now + 1)
        assertFalse(result.updated)
        assertEquals(100_500_000L, store.odometerMeters())
        // The short stretch keeps building up from the first number.
        assertEquals(100_000_000L, settings.maintenanceCorrectionBaseMeters)
        assertEquals(300.0, settings.maintenanceRawSinceCorrectionMeters, 0.0)
    }

    @Test
    fun `enough distance works out a factor and starts a new stretch`() {
        store.addDistance(60_000.0)
        val result = store.correct(100_063_000, now + 1)
        assertTrue(result.updated)
        assertEquals(1.05, store.correctionFactor, 1e-9)
        assertEquals(100_063_000L, settings.maintenanceCorrectionBaseMeters)
        assertEquals(0.0, settings.maintenanceRawSinceCorrectionMeters, 0.0)
    }

    @Test
    fun `missed driving asks for a check and ends the stretch until the next number`() {
        store.addDistance(500.0)
        store.noteMissedDriving(30_000)
        assertEquals(30_000L, store.missedMeters)
        assertNull(settings.maintenanceCorrectionBaseMeters)
        assertEquals(100_000_500L, settings.maintenanceOdometerMeters)

        store.correct(100_040_000, now + 1)
        assertEquals(0L, store.missedMeters)
        assertEquals(100_040_000L, settings.maintenanceCorrectionBaseMeters)
    }

    @Test
    fun `marking an item done records it in the history`() {
        store.markDone(MaintenanceCatalog.ENGINE_OIL, 110_000_000, now + 5)
        val oil = store.item(MaintenanceCatalog.ENGINE_OIL)!!
        assertEquals(110_000_000L, oil.lastMeters)
        assertEquals(now + 5, oil.lastMs)
        assertEquals(listOf(ServiceRecord(110_000_000, now + 5)), oil.history)
    }

    @Test
    fun `a custom item joins the list and can be removed`() {
        val added = store.addCustomItem("Wipers", now + 7)
        assertEquals("Wipers", store.item(added.id)!!.customName)
        store.removeItem(added.id)
        assertNull(store.item(added.id))
    }

    @Test
    fun `turning it back on keeps the items and ends the stretch`() {
        store.turnOff()
        assertFalse(settings.maintenanceEnabled)
        store.resume()
        assertTrue(settings.maintenanceEnabled)
        assertEquals(11, store.items().size)
        assertNull(settings.maintenanceCorrectionBaseMeters)
    }

    @Test
    fun `the last position round trips and clears`() {
        store.lastPosition = -16.5 to -68.15
        assertEquals(-16.5 to -68.15, store.lastPosition)
        store.lastPosition = null
        assertNull(store.lastPosition)
    }

    /** The engine oil, made overdue: its last change long before the current odometer. */
    private fun overdueOil(): MaintenanceItem {
        val oil = store.items().first { it.templateId == MaintenanceCatalog.ENGINE_OIL }
        store.updateItem(oil.copy(lastMeters = 0))
        return store.item(oil.id)!!
    }

    @Test
    fun `an item set aside comes back with the next start`() {
        val oil = overdueOil()
        assertTrue(store.beginRun(1_000))
        store.setAside(listOf(oil.id))
        store.notificationDismissed = true
        assertTrue(store.pendingReminders(now).none { it.item.id == oil.id })

        // The same start heard again changes nothing.
        assertFalse(store.beginRun(20_000))
        assertTrue(store.pendingReminders(now).none { it.item.id == oil.id })

        assertTrue(store.beginRun(1_000 + MaintenanceNoticePolicy.SAME_START_MS))
        assertTrue(store.pendingReminders(now).any { it.item.id == oil.id })
        assertFalse(store.notificationDismissed)
    }

    @Test
    fun `a snoozed item waits and a service clears the snooze`() {
        val oil = overdueOil()
        store.snooze(oil.id, MaintenanceNoticePolicy.Later(500_000, null), now)
        assertEquals(100_500_000L, store.item(oil.id)!!.snoozeUntilMeters)
        assertTrue(store.pendingReminders(now).none { it.item.id == oil.id })

        store.markDone(oil.id, 100_000_000, now)
        assertNull(store.item(oil.id)!!.snoozeUntilMeters)
    }

    // --- the CAN box ---

    private fun box(a: Long, b: Long? = null) = TripReading(a, b, miles = false)

    @Test
    fun `while the box delivers it counts and the GPS does not`() {
        store.addBoxReading(box(5000), 1_000, now)
        assertEquals(DistanceSourcePolicy.Source.CAN_BOX, store.countingSource(1_500))
        assertFalse(store.addDistance(800.0, 1_500))
        assertEquals(100_000_000L, store.odometerMeters())

        store.addBoxReading(box(5003), 31_000, now + 30_000)
        assertEquals(100_000_300L, store.odometerMeters())
    }

    @Test
    fun `box distance takes no correction factor and is written with its anchor`() {
        settings.maintenanceCorrectionFactor = 1.08
        store.addBoxReading(box(5000), 1_000, now)
        store.addBoxReading(box(5012), 100_000, now + 99_000)
        assertEquals(100_001_200L, settings.maintenanceOdometerMeters)
        assertEquals(1_200.0, settings.maintenanceBoxSinceCorrectionMeters, 1e-9)
        assertEquals(0.0, settings.maintenanceRawSinceCorrectionMeters, 0.0)
        assertEquals(5012L, CanBoxDistancePolicy.decode(settings.maintenanceBoxAnchor)!!.tripATenths)
    }

    @Test
    fun `once the box is silent the GPS counts, and the box takes that part off when it is back`() {
        store.addBoxReading(box(5000), 1_000, now)
        store.addDistance(700.0, 20_000)
        assertEquals(100_000_700L, store.odometerMeters())
        // The box measured 1 km over the same time: only the 300 m the GPS missed are added.
        store.addBoxReading(box(5010), 80_000, now + 79_000)
        assertEquals(100_001_000L, store.odometerMeters())
    }

    @Test
    fun `the next run counts the drive the box measured while the app was closed`() {
        store.addBoxReading(box(5000), 1_000, now)
        store.flush()

        val next = MaintenanceStore(settings)
        next.boxStarted(500)
        assertEquals(DistanceSourcePolicy.Coverage.PENDING, next.gapCoverage(1_000))
        next.addBoxReading(box(5085), 2_000, now + 3_600_000)
        assertEquals(100_008_500L, next.odometerMeters())
        assertEquals(DistanceSourcePolicy.Coverage.COVERED, next.gapCoverage(3_000))
    }

    @Test
    fun `without an anchor the first reading of a run does not cover the gap`() {
        store.boxStarted(500)
        store.addBoxReading(box(5000), 1_000, now)
        assertEquals(DistanceSourcePolicy.Coverage.NOT_COVERED, store.gapCoverage(2_000))
    }

    @Test
    fun `a typed number restarts the box from its current reading`() {
        store.addBoxReading(box(5000), 1_000, now)
        store.addBoxReading(box(5004), 30_000, now + 29_000)
        store.correct(100_100_000, now + 30_000, 30_500)
        assertEquals(100_100_000L, store.odometerMeters())
        // Too little GPS for a factor: the stretch goes on, with the box part kept apart.
        assertEquals(400.0, settings.maintenanceBoxSinceCorrectionMeters, 1e-9)
        assertEquals(5004L, CanBoxDistancePolicy.decode(settings.maintenanceBoxAnchor)!!.tripATenths)
        store.addBoxReading(box(5006), 60_000, now + 59_000)
        assertEquals(100_100_200L, store.odometerMeters())
    }

    @Test
    fun `turning it back on forgets the box anchor`() {
        store.addBoxReading(box(5000), 1_000, now)
        store.flush()
        store.turnOff()
        store.resume()
        assertEquals("", settings.maintenanceBoxAnchor)
    }
}
