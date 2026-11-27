package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.airplay.InfoPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class CarPlayNativeInfoTest {

    private val product = CarPlayNativeProduct("Open Headunit", "OHU-1", "Open Headunit", "1.0", "1.0")

    private fun config(width: Int = 1280, height: Int = 720, dpi: Int = 160) = CarPlayNativeInfo.config(product, "02:00:00:00:00:02", width, height, dpi)

    @Test fun theProductionFlagsOfStageTwoAreOff() {
        val config = config()
        assertFalse(config.hevc)
        assertFalse(config.offerMainHighMedia)
        assertFalse(config.offerOpus)
        assertFalse(config.disableAudioOutput)
        assertEquals(null, config.cluster)
    }

    @Test fun theNamesAreOursNotAnotherApps() {
        val config = config()
        assertEquals("Open Headunit", config.manufacturer)
        assertEquals("OHU-1", config.model)
        assertEquals("02:00:00:00:00:02", config.deviceId)
    }

    @Test fun theDisplayIsEvenAndHasAViewAreaSoTheFeatureIsOffered() {
        val main = config(1281, 721).main
        assertEquals(1280L, main.widthPixels)
        assertEquals(720L, main.heightPixels)
        assertNotNull(main.viewArea)
        assertEquals(203L, main.widthPhysicalMm)
    }

    @Test fun theOfferHasNoOpusAndNoMainHighMedia() {
        val dict = InfoPlist.build(config())
        val formats = dict["audioFormats"]!!.asItems!!.map { (it as PlistValue.Dict) }
        assertFalse(formats.any { entry -> entry["type"]?.asLong == 102L })
        assertEquals(true, formats.isNotEmpty())
    }
}
