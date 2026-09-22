package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import org.junit.Assert.assertEquals
import org.junit.Test

private typealias Resolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType

class AuxDisplayProfilePolicyTest {

    @Test
    fun `a panel that matches a standard size exactly takes it with no margin`() {
        val profile = AuxDisplayProfilePolicy.profileFor(800, 480, 160)
        assertEquals(Resolution._800x480, profile.resolution)
        assertEquals(0, profile.widthMargin)
        assertEquals(0, profile.heightMargin)
    }

    @Test
    fun `an odd cluster panel takes the smallest size that contains it, and the rest is margin`() {
        val profile = AuxDisplayProfilePolicy.profileFor(1024, 600, 160)
        assertEquals(Resolution._1280x720, profile.resolution)
        assertEquals(1280 - 1024, profile.widthMargin)
        assertEquals(720 - 600, profile.heightMargin)
    }

    @Test
    fun `a tall panel takes a portrait size rather than being rotated into a landscape one`() {
        val profile = AuxDisplayProfilePolicy.profileFor(600, 1024, 160)
        assertEquals(Resolution._720x1280, profile.resolution)
        assertEquals(720 - 600, profile.widthMargin)
        assertEquals(1280 - 1024, profile.heightMargin)
    }

    @Test
    fun `a panel larger than anything on offer takes the largest and no margin`() {
        val profile = AuxDisplayProfilePolicy.profileFor(3840, 2160, 320)
        assertEquals(0, profile.widthMargin)
        assertEquals(0, profile.heightMargin)
    }

    @Test
    fun `an unreadable density is replaced rather than announced as zero`() {
        assertEquals(160, AuxDisplayProfilePolicy.profileFor(800, 480, 0).density)
        assertEquals(160, AuxDisplayProfilePolicy.profileFor(800, 480, -1).density)
        assertEquals(213, AuxDisplayProfilePolicy.profileFor(800, 480, 213).density)
    }

    @Test
    fun `a zero-sized panel is treated as one pixel rather than dividing by nothing`() {
        val profile = AuxDisplayProfilePolicy.profileFor(0, 0, 160)
        assertEquals(Resolution._800x480, profile.resolution)
    }

    @Test
    fun `the auxiliary stream is announced at 30fps`() {
        val thirty = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
        assertEquals(thirty, AuxDisplayProfilePolicy.profileFor(1024, 600, 160).frameRate)
    }

    @Test
    fun `only the two keycodes the protocol allows can be stored`() {
        assertEquals(
            AuxDisplayProfilePolicy.KEYCODE_TURN_CARD,
            AuxDisplayProfilePolicy.contentKeycodeOrDefault(AuxDisplayProfilePolicy.KEYCODE_TURN_CARD),
        )
        assertEquals(
            AuxDisplayProfilePolicy.KEYCODE_NAVIGATION,
            AuxDisplayProfilePolicy.contentKeycodeOrDefault(AuxDisplayProfilePolicy.KEYCODE_NAVIGATION),
        )
        assertEquals(AuxDisplayProfilePolicy.KEYCODE_NAVIGATION, AuxDisplayProfilePolicy.contentKeycodeOrDefault(0))
        assertEquals(AuxDisplayProfilePolicy.KEYCODE_NAVIGATION, AuxDisplayProfilePolicy.contentKeycodeOrDefault(65537))
    }

    @Test
    fun `a missing or unknown stored role reads as auxiliary`() {
        assertEquals(AuxDisplayProfilePolicy.Role.AUXILIARY, AuxDisplayProfilePolicy.roleOrDefault(null))
        assertEquals(AuxDisplayProfilePolicy.Role.AUXILIARY, AuxDisplayProfilePolicy.roleOrDefault("PASSENGER"))
        assertEquals(AuxDisplayProfilePolicy.Role.CLUSTER, AuxDisplayProfilePolicy.roleOrDefault("CLUSTER"))
    }

    @Test
    fun `each role goes out as its own display type and only auxiliary names its content`() {
        assertEquals(Control.DisplayType.DISPLAY_TYPE_AUXILIARY, AuxDisplayProfilePolicy.displayType(AuxDisplayProfilePolicy.Role.AUXILIARY))
        assertEquals(Control.DisplayType.DISPLAY_TYPE_CLUSTER, AuxDisplayProfilePolicy.displayType(AuxDisplayProfilePolicy.Role.CLUSTER))
        assertEquals(true, AuxDisplayProfilePolicy.announcesContent(AuxDisplayProfilePolicy.Role.AUXILIARY))
        assertEquals(false, AuxDisplayProfilePolicy.announcesContent(AuxDisplayProfilePolicy.Role.CLUSTER))
    }
}
