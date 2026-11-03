package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test

class GalConnectionContractTest {
    @Test fun `high response does not select policy and rejected agreement cannot create contract`() {
        val high = AapVersionNegotiation.Result(6, 1, 0, null)
        val snapshot = GalSessionProfile(GalProfile.COMPATIBILITY, high)
        val contract = GalConnectionContract.resolve(snapshot, GalDisplaySettings(true, true, true))
        assertNull(contract.display.initial(5, 7))
        assertEquals(GalMediaPolicy.NO_OPTIONS, contract.media.optionsFor(Channel.ID_VID))
        for (invalid in listOf(high.copy(status = -1), high.copy(major = 1, minor = 7))) {
            assertThrows(IllegalArgumentException::class.java) {
                GalConnectionContract.resolve(snapshot.copy(profile = GalProfile.GAL_6_0, finalResponse = invalid),
                    GalDisplaySettings(false, false, false))
            }
        }
    }

    @Test fun `discovery format snapshot drives indices and Start without rereading settings`() {
        val builder = galTestDiscovery(videoFallback = true).toBuilder()
        val offer = GalMediaOffer.from(builder.build())
        builder.clearServices()
        val sessions = GalMediaSessions()
        sessions.advertise(galTestContract().media, offer)
        assertEquals(listOf(0, 1), sessions.configurationIndices(Channel.ID_VID))
        assertEquals(listOf(0), sessions.configurationIndices(Channel.ID_AUD))
        assertTrue(sessions.configurationIndices(Channel.ID_MIC).isEmpty())
        val start = Media.Start.newBuilder().setSessionId(77).setConfigurationIndex(1).build()
        val selected = sessions.start(Channel.ID_VID, start)!!
        assertEquals(1, selected.configurationIndex)
        assertEquals(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480,
            (selected.format as GalMediaFormat.Video).value.codecResolution)
        assertNull(sessions.start(Channel.ID_VID, start.toBuilder().setConfigurationIndex(-1).build()))
        assertNull(sessions.start(Channel.ID_VID, start.toBuilder().setConfigurationIndex(2).build()))
        assertSame(selected, sessions.current(Channel.ID_VID))
        assertNull(sessions.start(Channel.ID_MIC, start.toBuilder().setConfigurationIndex(0).build()))
        assertThrows(IllegalStateException::class.java) { sessions.advertise(galTestContract().media, offer) }
    }

    @Test fun `session IDs and ack mode are one atomic owner including reused IDs and Stop`() {
        val sessions = galTestSessions()
        val legacy = Media.Start.newBuilder().setSessionId(12).setConfigurationIndex(0).build()
        sessions.start(Channel.ID_AUD, legacy)
        val captured = sessions.ackSession(Channel.ID_AUD)
        assertEquals(12, captured)
        sessions.start(Channel.ID_AUD, legacy.toBuilder().setFlowControl(Media.Start.FlowControl.ACKLESS).build())
        assertNull(sessions.ackSession(Channel.ID_AUD))
        assertEquals(12, captured) // already queued legacy work still owns the original ACK
        sessions.stop(Channel.ID_AUD)
        assertNull(sessions.ackSession(Channel.ID_AUD)) // Stop does not revert ackless to legacy
        sessions.start(Channel.ID_AUD, legacy.toBuilder().setSessionId(19).build())
        assertEquals(19, sessions.ackSession(Channel.ID_AUD))
        assertEquals(0, sessions.ackSession(Channel.ID_AU1))
    }

    @Test fun `UI update payload follows connection policy for every profile`() {
        for (profile in GalProfile.entries) {
            val n = galTestContract(profile).negotiation
            val contract = GalConnectionContract.resolve(n, GalDisplaySettings(true, false, true))
            val packet = Media.UpdateUiConfigRequest.newBuilder().setUiConfig(contract.display.margins(1, 2, 3, 4)).build()
            val update = Media.UpdateUiConfigRequest.parseFrom(packet.toByteArray()).uiConfig
            assertEquals(1, update.margins.left)
            assertEquals(4, update.margins.bottom)
            assertFalse(update.hasUiTheme())
            val initial = contract.display.initial(5, 7)
            if (profile in listOf(GalProfile.COMPATIBILITY, GalProfile.GAL_1_6)) {
                assertNull(initial)
                assertEquals(0, update.uiElementsCount)
            } else {
                assertEquals(listOf(Media.UiConfig.UiElement.CLOCK, Media.UiConfig.UiElement.PHONE_SIGNAL), update.uiElementsList)
                assertEquals(initial!!.uiElementsList, update.uiElementsList)
            }
        }
    }

    @Test fun `legacy and modern discovery visibility use one frozen snapshot`() {
        for (profile in GalProfile.entries) for (bits in 0..7) {
            var settings = GalDisplaySettings(bits and 1 != 0, bits and 4 != 0, bits and 2 != 0)
            val contract = GalConnectionContract.resolve(galTestContract(profile).negotiation, settings)
            settings = GalDisplaySettings(!settings.hideClock, !settings.hideBattery, !settings.hideSignal)
            val builder = galTestDiscovery().toBuilder().setHideProjectedClock(settings.hideClock)
                .setSessionConfiguration(bits xor 7)
            contract.display.applyDiscoveryVisibility(builder)
            val wire = Control.ServiceDiscoveryResponse.parseFrom(builder.build().toByteArray())
            assertEquals(bits, wire.sessionConfiguration)
            assertEquals(bits and 1 != 0, wire.hideProjectedClock)
            val initial = contract.display.initial(0, 0)
            if (initial != null) {
                assertEquals(bits and 1 != 0, Media.UiConfig.UiElement.CLOCK in initial.uiElementsList)
                assertEquals(bits and 2 != 0, Media.UiConfig.UiElement.PHONE_SIGNAL in initial.uiElementsList)
                assertEquals(bits and 4 != 0, Media.UiConfig.UiElement.BATTERY_LEVEL in initial.uiElementsList)
                assertEquals(initial.uiElementsList, contract.display.margins(1, 2, 3, 4).uiElementsList)
            }
        }
    }

    @Test fun `unadvertised Start and options cannot create channel state`() {
        val sessions = GalMediaSessions()
        assertNull(sessions.start(Channel.ID_AUD, Media.Start.newBuilder().setSessionId(1).setConfigurationIndex(0).build()))
        assertFalse(sessions.update(Channel.ID_AUD, Media.MediaOptions.getDefaultInstance()))
        assertNull(sessions.current(Channel.ID_AUD))
    }
}
