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
}
