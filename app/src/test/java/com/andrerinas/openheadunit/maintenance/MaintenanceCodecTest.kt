package com.andrerinas.openheadunit.maintenance

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is an Android class, so this runs on Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MaintenanceCodecTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `items survive a round trip with their history, modes and snoozes`() {
        val items = MaintenanceCatalog.build(Gearbox.CVT, Steering.UNSURE, true, 80_000_000, now).map {
            when (it.templateId) {
                MaintenanceCatalog.ENGINE_OIL -> it.copy(
                    mode = MaintenanceMode.TRACK,
                    history = listOf(ServiceRecord(70_000_000, now - 1), ServiceRecord(null, now)),
                    snoozeUntilMeters = 80_500_000,
                )
                MaintenanceCatalog.CABIN_FILTER -> it.copy(snoozeUntilMs = now + 3 * MaintenanceDuePolicy.DAY_MS)
                else -> it
            }
        } + MaintenanceCatalog.custom("custom_1", "Wipers \"front\"", true, 80_000_000, now).copy(intervalMeters = null)

        assertEquals(items, MaintenanceCodec.decode(MaintenanceCodec.encode(items)))
    }

    @Test
    fun `nothing stored or a broken string is an empty list`() {
        assertTrue(MaintenanceCodec.decode(null).isEmpty())
        assertTrue(MaintenanceCodec.decode("").isEmpty())
        assertTrue(MaintenanceCodec.decode("{not json").isEmpty())
    }

    @Test
    fun `a broken entry is dropped on its own`() {
        val good = MaintenanceCatalog.custom("custom_1", "Wipers", false, 0, now)
        val stored = MaintenanceCodec.encode(listOf(good)).removeSuffix("]") + ",{\"mode\":\"REMIND\"},{\"id\":\"x\"}]"
        assertEquals(listOf(good), MaintenanceCodec.decode(stored))
    }

    @Test
    fun `an unknown mode falls back to remind`() {
        val stored = "[{\"id\":\"a\",\"name\":\"A\",\"mode\":\"LATER\",\"warnM\":0,\"warnDays\":0}]"
        assertEquals(MaintenanceMode.REMIND, MaintenanceCodec.decode(stored).single().mode)
    }
}
