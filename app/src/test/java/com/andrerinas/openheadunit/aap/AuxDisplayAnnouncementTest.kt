package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.video.AuxDisplayProfilePolicy
import com.andrerinas.openheadunit.decoder.video.AuxDisplayProfilePolicy.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuxDisplayAnnouncementTest {

    private val profile = AuxDisplayProfilePolicy.profileFor(1280, 720, 160)

    /** The main sink as discovery sends it when a second display is announced. */
    private val mainSink = Control.Service.newBuilder().also { service ->
        service.id = Channel.ID_VID
        service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
            sink.availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            sink.audioType = Media.AudioStreamType.NONE
            sink.displayId = 0
            sink.displayType = Control.DisplayType.DISPLAY_TYPE_MAIN
            sink.addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                codecResolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
                frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._60
                setDensity(160)
                setMarginWidth(0)
                setMarginHeight(0)
            }.build())
        }.build()
    }.build()

    /** The main input as `main` sends it: a touchscreen, keys, and no display id. */
    private val mainInput = Control.Service.newBuilder().also { service ->
        service.id = Channel.ID_INP
        service.inputSourceService = Control.Service.InputSourceService.newBuilder().also {
            it.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().setWidth(800).setHeight(480).build()
            it.addAllKeycodesSupported(listOf(3, 4))
        }.build()
    }.build()

    private fun aux(role: Role = Role.AUXILIARY, keycode: Int? = AuxDisplayProfilePolicy.KEYCODE_NAVIGATION) =
        AuxDisplayAnnouncement.services(profile, role, keycode)

    private fun auxInput(services: List<Control.Service>) = services.single { it.hasInputSourceService() }

    private fun auxSink(services: List<Control.Service>) = services.single { it.hasMediaSinkService() }

    @Test
    fun `the second display has exactly one input, bound to its own display id`() {
        val inputs = aux().filter { it.hasInputSourceService() }
        assertEquals(1, inputs.size)
        assertTrue(inputs.single().inputSourceService.hasDisplayId())
        assertEquals(1, inputs.single().inputSourceService.displayId)
    }

    @Test
    fun `the input has its own channel`() {
        val services = listOf(mainSink, mainInput) + aux()
        assertEquals(Channel.ID_INP2, auxInput(aux()).id)
        assertEquals(services.size, services.map { it.id }.toSet().size)
    }

    @Test
    fun `the input carries no touch and no keys`() {
        val input = auxInput(aux()).inputSourceService
        assertFalse(input.hasTouchscreen())
        assertFalse(input.hasTouchpad())
        assertEquals(0, input.keycodesSupportedCount)
    }

    @Test
    fun `the sink and the input name the same display`() {
        val services = aux()
        assertEquals(AuxDisplayAnnouncement.AUX_DISPLAY_ID, auxSink(services).mediaSinkService.displayId)
        assertEquals(AuxDisplayAnnouncement.AUX_DISPLAY_ID, auxInput(services).inputSourceService.displayId)
        assertEquals(Channel.ID_VID2, auxSink(services).id)
    }

    @Test
    fun `a cluster role still gets its input, and no content keycode`() {
        val services = aux(Role.CLUSTER, keycode = null)
        assertEquals(Channel.ID_INP2, auxInput(services).id)
        assertEquals(Control.DisplayType.DISPLAY_TYPE_CLUSTER, auxSink(services).mediaSinkService.displayType)
        assertFalse(auxSink(services).mediaSinkService.hasInitialContentKeycode())
    }

    @Test
    fun `an auxiliary role keeps its content keycode`() {
        val sink = auxSink(aux(Role.AUXILIARY, AuxDisplayProfilePolicy.KEYCODE_TURN_CARD)).mediaSinkService
        assertEquals(Control.DisplayType.DISPLAY_TYPE_AUXILIARY, sink.displayType)
        assertEquals(AuxDisplayProfilePolicy.KEYCODE_TURN_CARD, sink.initialContentKeycode)
    }

    @Test
    fun `the phone's display rules pass for both roles`() {
        assertNull(GearheadDisplayRules.violation(listOf(mainSink, mainInput) + aux(Role.AUXILIARY)))
        assertNull(GearheadDisplayRules.violation(listOf(mainSink, mainInput) + aux(Role.CLUSTER, keycode = null)))
    }

    @Test
    fun `a second display without an input fails the way round 1 did`() {
        val auxSinkAlone = Control.Service.newBuilder().also { service ->
            service.id = Channel.ID_VID2
            service.mediaSinkService = mainSink.mediaSinkService.toBuilder().also {
                it.displayId = 1
                it.displayType = Control.DisplayType.DISPLAY_TYPE_AUXILIARY
            }.build()
        }.build()
        assertEquals("No input for display 1", GearheadDisplayRules.violation(listOf(mainSink, auxSinkAlone, mainInput)))
    }

    @Test
    fun `an input left on display 0 fails display 0`() {
        val services = aux().map { service ->
            if (!service.hasInputSourceService()) service
            else service.toBuilder().also { it.inputSourceServiceBuilder.clearDisplayId() }.build()
        }
        assertEquals("Multiple inputs found for display 0", GearheadDisplayRules.violation(listOf(mainSink, mainInput) + services))
    }

    @Test
    fun `the display id goes out on field 5`() {
        val bytes = auxInput(aux()).inputSourceService.toByteArray()
        val found = (0 until bytes.size - 1).any { bytes[it] == 0x28.toByte() && bytes[it + 1] == 0x01.toByte() }
        assertTrue(bytes.joinToString(" ") { "%02x".format(it) }, found)
    }
}
