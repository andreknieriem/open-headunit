package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayAudioRouting.Codec
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayAudioRoutingTest {

    private fun audioStream(audioType: String) = SetupNegotiation.parse(
        PlistValue.dict(
            "streams" to PlistValue.Items(
                listOf(
                    PlistValue.dict(
                        "type" to PlistValue.Integer(100),
                        "audioType" to PlistValue.Text(audioType),
                        "audioFormat" to PlistValue.Integer(0x800),
                        "streamConnectionID" to PlistValue.Integer(5),
                    ),
                ),
            ),
        ),
    ).let { (it as SetupNegotiation.Request.Streams).streams.single() as SetupNegotiation.Stream.Audio }

    @Test
    fun everyAudioTypeOfStream100GoesToTheMediaChannel() {
        for (audioType in listOf("media", "alert", "telephony", "speechRecognition", "default", "compatibility")) {
            assertEquals(audioType, Channel.ID_AUD, AirPlayAudioRouting.channelFor(audioStream(audioType).type))
        }
    }

    @Test
    fun typeOneZeroOneGoesToTheNavigationChannelAndOtherTypesAreNotPlayed() {
        assertEquals(Channel.ID_AU1, AirPlayAudioRouting.channelFor(101))
        for (type in listOf(102, 110, 111, 130, 0)) assertNull("type $type", AirPlayAudioRouting.channelFor(type))
    }

    @Test
    fun everyFormatBitThatInfoOffersWithoutOpusHasASinkFormat() {
        for (rate in listOf(44100, 48000)) {
            val config = InfoPlist.Config(
                deviceName = "Unit", deviceId = "AA:BB:CC:DD:EE:FF", btMac = "11:22:33:44:55:66", sourceVersion = "950.7.1",
                model = "Unit", manufacturer = "Maker", hevc = false, main = InfoPlist.Display(1280, 720),
                entertainmentSampleRate = rate, disableAudioOutput = false, oemLabel = "App", rightHandDrive = false,
                appearanceSetting = 1, offerMainHighMedia = false, offerOpus = false,
            )
            val formats = (InfoPlist.build(config)["audioFormats"] as PlistValue.Items).items.map { it as PlistValue.Dict }
            for (entry in formats) for (key in listOf("audioOutputFormats", "audioInputFormats")) {
                val bits = entry[key]?.asLong ?: continue
                for (bit in 0 until 63) {
                    if (bits and (1L shl bit) == 0L) continue
                    assertNotNull("$rate ${entry["audioType"]} $key bit $bit", AirPlayAudioRouting.formatFor(1L shl bit).sinkFormat)
                }
            }
        }
    }

    @Test
    fun pcmBitsGiveRateAndChannels() {
        val cases = mapOf(
            0x4L to (8_000 to 1), 0x8L to (8_000 to 2), 0x10L to (16_000 to 1), 0x20L to (16_000 to 2),
            0x40L to (24_000 to 1), 0x80L to (24_000 to 2), 0x100L to (32_000 to 1), 0x200L to (32_000 to 2),
            0x400L to (44_100 to 1), 0x800L to (44_100 to 2), 0x4000L to (48_000 to 1), 0x8000L to (48_000 to 2),
        )
        for ((bits, expected) in cases) {
            val format = AirPlayAudioRouting.formatFor(bits)
            assertEquals(Codec.LPCM, format.codec)
            assertEquals("bits 0x${bits.toString(16)}", expected, format.sampleRate to format.channels)
            assertEquals(expected.first, format.sinkFormat!!.sampleRate)
            assertEquals(expected.second, format.sinkFormat!!.channels)
        }
    }

    @Test
    fun aacOpusAndUnknownBitsAreNamed() {
        val aac44 = AirPlayAudioRouting.formatFor(0x40_0000)
        val aac48 = AirPlayAudioRouting.formatFor(0x80_0000)
        assertEquals(Codec.AAC_LC to 44_100, aac44.codec to aac44.sampleRate)
        assertEquals(Codec.AAC_LC to 48_000, aac48.codec to aac48.sampleRate)
        for (bits in listOf(0x1000_0000L, 0x2000_0000L, 0x4000_0000L)) {
            val opus = AirPlayAudioRouting.formatFor(bits)
            assertEquals(Codec.OPUS, opus.codec)
            assertNull(opus.sinkFormat)
        }
        assertNull(aac44.sinkFormat)
        val unknown = AirPlayAudioRouting.formatFor(0x1)
        assertEquals(Triple(Codec.LPCM, 44_100, 2), Triple(unknown.codec, unknown.sampleRate, unknown.channels))
    }
}
