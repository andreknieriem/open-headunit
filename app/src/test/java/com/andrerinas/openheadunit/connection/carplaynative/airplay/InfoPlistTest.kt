package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.airplay.InfoPlist.Config
import com.andrerinas.openheadunit.connection.carplaynative.airplay.InfoPlist.Display
import com.andrerinas.openheadunit.connection.carplaynative.airplay.InfoPlist.Icon
import com.andrerinas.openheadunit.connection.carplaynative.airplay.InfoPlist.Insets
import com.andrerinas.openheadunit.connection.carplaynative.codec.BinaryPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.hex
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `info` and `infoNoAudio` vectors come from LIVI `ts_vectors.json`, with the inputs of
 * `ts_vectors.rs` `info_config`.
 */
class InfoPlistTest {

    private fun liviConfig(offerMainHighMedia: Boolean) = Config(
        deviceName = "Golf",
        deviceId = "AA:BB:CC:DD:EE:FF",
        btMac = "11:22:33:44:55:66",
        sourceVersion = "950.7.1",
        model = "LIVI",
        manufacturer = "LIVI",
        hevc = true,
        main = Display(
            1280, 720, widthPhysicalMm = 200, fps = 60, viewArea = Insets(),
            safeArea = Insets(left = 10, right = 10), safeAreaDrawOutside = true, uiNight = true, mapNight = true,
        ),
        cluster = Display(
            800, 480, widthPhysicalMm = 150, fps = 30, initialUrl = "maps:/car/instrumentcluster",
            uiNight = true, mapNight = true,
        ),
        entertainmentSampleRate = 44100,
        disableAudioOutput = false,
        oemLabel = "App",
        icons = listOf(Icon(120, 120, byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))),
        rightHandDrive = true,
        appearanceSetting = 1,
        offerMainHighMedia = offerMainHighMedia,
        offerOpus = true,
    )

    private fun noAudio(offerMainHighMedia: Boolean) = Config(
        deviceName = "Golf", deviceId = "AA:BB:CC:DD:EE:FF", btMac = "11:22:33:44:55:66", sourceVersion = "950.7.1",
        model = "LIVI", manufacturer = "LIVI", hevc = false,
        main = liviConfig(true).main, cluster = null, entertainmentSampleRate = 48000, disableAudioOutput = true,
        oemLabel = "App", icons = emptyList(), rightHandDrive = true, appearanceSetting = 1,
        offerMainHighMedia = offerMainHighMedia, offerOpus = true,
    )

    private fun sample(night: Boolean = false, width: Long = 1280, height: Long = 720, physical: Long? = null, safe: Insets = Insets(), rate: Int = 48000) = Config(
        deviceName = "Unit", deviceId = "AA:BB:CC:DD:EE:FF", btMac = "11:22:33:44:55:66", sourceVersion = "950.7.1",
        model = "Unit", manufacturer = "Maker", hevc = false,
        main = Display(width, height, widthPhysicalMm = physical, viewArea = Insets(), safeArea = safe, mapNight = night),
        entertainmentSampleRate = rate, disableAudioOutput = false, oemLabel = "App", rightHandDrive = false,
        appearanceSetting = 1, offerMainHighMedia = false, offerOpus = false,
    )

    private fun sha(bytes: ByteArray) = CarPlayCrypto.sha256(bytes).toHex()

    @Test
    fun theLiviInfoVectorMatches() {
        val bytes = InfoPlist.encode(liviConfig(true))
        assertEquals(5378, bytes.size)
        assertEquals("896f5665c94782258b749a33662df7103160fe405c2b56a265c6a80be79a31db", sha(bytes))
    }

    @Test
    fun theLiviNoAudioVectorMatches() {
        val bytes = InfoPlist.encode(noAudio(true))
        assertEquals(2690, bytes.size)
        assertEquals("fdbdccb35bdd972e71ee9dd95516e7f2cd684edd42f4036b69b1397238d7fe0c", sha(bytes))
    }

    @Test
    fun withoutTheFlagTheOnlyDifferenceIsTheType102MediaEntry() {
        // The decoded vector plist is the reference: its bytes match the LIVI hash above.
        val vector = BinaryPlist.decode(InfoPlist.encode(liviConfig(true))) as PlistValue.Dict
        val ours = InfoPlist.build(liviConfig(false))
        assertEquals(vector.entries.map { it.first }, ours.entries.map { it.first })
        for ((key, value) in vector.entries) {
            if (key != "audioFormats") assertEquals(key, value, ours[key])
        }
        val expected = (vector["audioFormats"] as PlistValue.Items).items
        val actual = (ours["audioFormats"] as PlistValue.Items).items
        val dropped = expected.filterNot { it in actual }
        assertEquals(1, dropped.size)
        val entry = dropped[0] as PlistValue.Dict
        assertEquals(PlistValue.Integer(102), entry["type"])
        assertEquals(PlistValue.Text("media"), entry["audioType"])
        assertEquals(expected.size - 1, actual.size)
        assertEquals(expected.filter { it != dropped[0] }, actual)
    }

    @Test
    fun withoutOpusNoEntryOffersAnOpusBit() {
        for (rate in listOf(44100, 48000)) {
            val formats = (InfoPlist.build(sample(rate = rate))["audioFormats"] as PlistValue.Items).items.map { it as PlistValue.Dict }
            for (entry in formats) for (key in listOf("audioOutputFormats", "audioInputFormats")) {
                val bits = entry[key]?.asLong ?: continue
                assertEquals("$rate ${entry["audioType"]} $key", 0L, bits and 0x7000_0000L)
            }
        }
    }

    @Test
    fun noEntryOfTypeOneZeroTwoIsOfferedWithoutTheFlag() {
        val formats = (InfoPlist.build(sample())["audioFormats"] as PlistValue.Items).items
        assertEquals(emptyList<PlistValue>(), formats.filter { (it as PlistValue.Dict)["type"] == PlistValue.Integer(102) })
        assertEquals(8, formats.size)
    }

    @Test
    fun theFeatureBitsMatchLivi() {
        assertEquals(0x44540380L or (0x61L shl 32), InfoPlist.FEATURES)
        assertEquals(0x100_0454_0a00L, InfoPlist.AUDIO_FEATURES)
        val info = InfoPlist.build(noAudio(false))
        assertEquals(PlistValue.Integer(InfoPlist.FEATURES and InfoPlist.AUDIO_FEATURES.inv()), info["features"])
        assertNull(info["audioFormats"])
        assertNull(info["audioLatencies"])
    }

    @Test
    fun aDisplayOfUnknownSizeGrowsWithItsPixels() {
        fun size(config: Config): Pair<PlistValue?, PlistValue?> {
            val main = (InfoPlist.build(config)["displays"] as PlistValue.Items).items[0] as PlistValue.Dict
            assertEquals(PlistValue.Integer(110), main["type"])
            return main["widthPhysical"] to main["heightPhysical"]
        }
        assertEquals(PlistValue.Integer(163) to PlistValue.Integer(92), size(sample()))
        assertEquals(PlistValue.Integer(244) to PlistValue.Integer(137), size(sample(width = 1920, height = 1080)))
        assertEquals(1L, InfoPlist.mmAtDpi(0, 160.0))
        assertEquals(305L, InfoPlist.mmAtDpi(1920, 160.0))
    }

    @Test
    fun theMainMapIsTheNightMode() {
        assertEquals(PlistValue.Bool(true), InfoPlist.build(sample(night = true))["nightMode"])
        assertEquals(PlistValue.Bool(false), InfoPlist.build(sample(night = false))["nightMode"])
    }

    @Test
    fun speechModeMinusOneGoesOutAsAReal() {
        val modes = InfoPlist.build(sample())["modes"] as PlistValue.Dict
        val states = (modes["appStates"] as PlistValue.Items).items
        assertEquals(PlistValue.Real(-1.0), (states[1] as PlistValue.Dict)["speechMode"])
    }

    @Test
    fun theSafeAreaStaysInsideTheViewArea() {
        fun safe(config: Config): List<PlistValue?> {
            val main = (InfoPlist.build(config)["displays"] as PlistValue.Items).items[0] as PlistValue.Dict
            val area = (main["viewAreas"] as PlistValue.Items).items[0] as PlistValue.Dict
            val safe = area["safeArea"] as PlistValue.Dict
            return listOf("widthPixels", "heightPixels", "originXPixels", "originYPixels").map { safe[it] }
        }
        val view = Insets(bottom = 24)
        fun withView(safeArea: Insets) = sample(safe = safeArea).let {
            Config(
                it.deviceName, it.deviceId, it.btMac, it.sourceVersion, it.model, it.manufacturer, false,
                Display(1280, 720, viewArea = view, safeArea = safeArea), null, 48000, false, "App", emptyList(), false, 1, false, false,
            )
        }
        assertEquals(listOf<Long>(1280, 696, 0, 0).map { PlistValue.Integer(it) }, safe(withView(Insets())))
        assertEquals(listOf<Long>(1280, 680, 0, 0).map { PlistValue.Integer(it) }, safe(withView(Insets(bottom = 40))))
    }

    @Test
    fun theHidDevicesNameThemselvesAfterTheModel() {
        val devices = (InfoPlist.build(sample())["hidDevices"] as PlistValue.Items).items.map { it as PlistValue.Dict }
        assertEquals(
            listOf("Unit Touchscreen", "Unit Knob", "Unit Media", "Unit Telephony"),
            devices.map { (it["name"] as PlistValue.Text).value },
        )
        assertEquals(listOf("2a2a2a2a", "2a2a2a2b", "2a2a2a2c", "2a2a2a2d"), devices.map { (it["uuid"] as PlistValue.Text).value })
    }

    @Test
    fun theLiviTouchDescriptorMatches() {
        assertEquals(
            "050d0904a101050d0922a1020938750895018102150025010933750195018102950781030501268007093075109501810226380409318102c0050d0922a1020938750895018102150025010933750195018102950781030501268007093075109501810226380409318102c0c0",
            HidDevices.touchDescriptor(1920, 1080).toHex(),
        )
        val desc = HidDevices.touchDescriptor(1280, 720)
        assertEquals(6 + 2 * 51 + 1, desc.size)
        assertArrayEquals(hex("260005"), desc.copyOfRange(6 + 32, 6 + 35))
        assertArrayEquals(hex("26d002"), desc.copyOfRange(6 + 43, 6 + 46))
    }

    @Test
    fun noIconsMeansNoOemKeysAndHevcAddsItsKey() {
        val plain = InfoPlist.build(sample())
        assertFalse(plain.entries.any { it.first.startsWith("oem") })
        assertNull(plain["hevcInfo"])
        val full = InfoPlist.build(liviConfig(true))
        assertEquals(PlistValue.Dict(emptyList()), full["hevcInfo"])
        assertEquals(PlistValue.Bool(true), full["oemIconVisible"])
    }
}
