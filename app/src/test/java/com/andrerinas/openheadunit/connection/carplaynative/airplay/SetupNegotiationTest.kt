package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.airplay.SetupNegotiation.Request
import com.andrerinas.openheadunit.connection.carplaynative.airplay.SetupNegotiation.Stream
import com.andrerinas.openheadunit.connection.carplaynative.codec.BinaryPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupNegotiationTest {

    private fun text(s: String) = PlistValue.Text(s)
    private fun int(n: Long) = PlistValue.Integer(n)

    private fun session(vararg extra: Pair<String, PlistValue>) = SetupNegotiation.parse(
        PlistValue.dict(
            "name" to text("iPhone"),
            "deviceID" to text("AA:BB:CC:DD:EE:01"),
            "model" to text("iPhone15,2"),
            "macAddress" to text("AA:BB:CC:DD:EE:02"),
            "timingPort" to int(49152),
            *extra,
        ),
    ) as Request.SessionSetup

    private fun streams(vararg entries: PlistValue.Dict) =
        (SetupNegotiation.parse(PlistValue.dict("streams" to PlistValue.Items(entries.toList()))) as Request.Streams).streams

    @Test
    fun aSessionSetupWithKeepAliveLowPowerGetsAKeepAlivePort() {
        val request = session("keepAliveLowPower" to PlistValue.Bool(true)).session
        assertTrue(request.wantsKeepAlive)
        val response = SetupNegotiation.sessionResponse(7010, 7020, 7030, emptyList())
        assertEquals(listOf("timingPort", "eventPort", "keepAlivePort", "enabledFeatures"), response.entries.map { it.first })
        assertEquals(int(7030), response["keepAlivePort"])
    }

    @Test
    fun aSessionSetupWithoutItGetsNone() {
        val request = session().session
        assertEquals(false, request.wantsKeepAlive)
        val response = SetupNegotiation.sessionResponse(7010, 7020, null, emptyList())
        assertEquals(listOf("timingPort", "eventPort", "enabledFeatures"), response.entries.map { it.first })
    }

    @Test
    fun keepAliveIsTruthyForOneAndFalseForZero() {
        assertTrue(session("keepAliveLowPower" to int(1)).session.wantsKeepAlive)
        assertEquals(false, session("keepAliveLowPower" to int(0)).session.wantsKeepAlive)
        assertEquals(false, session("keepAliveLowPower" to PlistValue.Bool(false)).session.wantsKeepAlive)
    }

    @Test
    fun theSessionFieldsAreRead() {
        val s = session("features" to PlistValue.Items(listOf(text("viewAreas"), text("hevc")))).session
        assertEquals("iPhone", s.name)
        assertEquals("AA:BB:CC:DD:EE:01", s.deviceId)
        assertEquals("iPhone15,2", s.model)
        assertEquals("aa:bb:cc:dd:ee:02", s.macAddress)
        assertEquals(49152, s.peerTimingPort)
        assertEquals(listOf("viewAreas", "hevc"), s.offeredFeatures)
    }

    @Test
    fun aTimingPortOutsideTheRangeIsNone() {
        for (port in listOf(0L, -1L, 65536L)) {
            val request = SetupNegotiation.parse(PlistValue.dict("timingPort" to int(port))) as Request.SessionSetup
            assertEquals(0, request.session.peerTimingPort)
        }
        assertEquals(0, (SetupNegotiation.parse(PlistValue.dict()) as Request.SessionSetup).session.peerTimingPort)
    }

    @Test
    fun aBodyThatIsNoDictionaryIsRefused() {
        assertNull(SetupNegotiation.parse(PlistValue.Items(emptyList())))
        assertNull(SetupNegotiation.parse(text("x")))
    }

    @Test
    fun aBodyWithStreamsIsAStreamsSetupEvenWithSessionKeys() {
        val body = PlistValue.dict("timingPort" to int(1), "streams" to PlistValue.Items(emptyList()))
        assertTrue(SetupNegotiation.parse(body) is Request.Streams)
    }

    @Test
    fun onlyViewAreasPassAndOnlyWhenInfoDescribesOne() {
        val offered = listOf("viewAreas", "iAPChannel", "altScreen", "hevc", "uiContext", "viewAreas")
        assertEquals(listOf("viewAreas"), SetupNegotiation.enabledFeatures(offered, true))
        assertEquals(emptyList<String>(), SetupNegotiation.enabledFeatures(offered, false))
        assertEquals(emptyList<String>(), SetupNegotiation.enabledFeatures(listOf("iAPChannel", "hevc"), true))
        assertEquals(emptyList<String>(), SetupNegotiation.enabledFeatures(emptyList(), true))
    }

    @Test
    fun screenAndAudioStreamsGetTheirEntries() {
        val list = streams(
            PlistValue.dict("type" to int(110), "streamConnectionID" to int(12345)),
            PlistValue.dict(
                "type" to int(100), "audioType" to text("alert"), "audioFormat" to int(0x800),
                "streamConnectionID" to int(77), "audioLatencyMs" to int(120),
            ),
        )
        val screen = list[0] as Stream.Screen
        val audio = list[1] as Stream.Audio
        assertEquals("12345", screen.connectionId)
        assertEquals("alert", audio.audioType)
        assertEquals(0x800L, audio.formatBits)
        assertEquals(120, audio.latencyMs)
        val response = SetupNegotiation.streamsResponse(
            listOf(SetupNegotiation.screenEntry(110, 7100), SetupNegotiation.audioEntry(audio, 7200, 7201)),
        )
        val entries = (response["streams"] as PlistValue.Items).items.map { it as PlistValue.Dict }
        assertEquals(listOf("type", "dataPort"), entries[0].entries.map { it.first })
        assertEquals(listOf("type", "dataPort", "controlPort", "streamConnectionID"), entries[1].entries.map { it.first })
        assertEquals(int(77), entries[1]["streamConnectionID"])
        assertEquals(int(100), entries[1]["type"])
        assertEquals(response, BinaryPlist.decode(BinaryPlist.encode(response)))
    }

    @Test
    fun aHighBitConnectionIdKeepsItsBitsInTheAnswerAndReadsUnsigned() {
        val audio = streams(PlistValue.dict("type" to int(100), "streamConnectionID" to int(-1L))).single() as Stream.Audio
        assertEquals("18446744073709551615", audio.connectionId)
        assertEquals(int(-1L), SetupNegotiation.audioEntry(audio, 1, 2)["streamConnectionID"])
    }

    @Test
    fun aMissingConnectionIdHasNoTextAndEchoesZero() {
        val audio = streams(PlistValue.dict("type" to int(100))).single() as Stream.Audio
        assertNull(audio.connectionId)
        assertEquals("media", audio.audioType)
        assertEquals(0L, audio.formatBits)
        assertEquals(int(0), SetupNegotiation.audioEntry(audio, 1, 2)["streamConnectionID"])
    }

    @Test
    fun otherStreamTypesAreUnsupportedAndStayOutOfTheAnswer() {
        val list = streams(
            PlistValue.dict("type" to int(111)),
            PlistValue.dict("type" to int(102)), PlistValue.dict("type" to int(130)),
            PlistValue.dict("noType" to int(1)),
        )
        assertTrue(list.all { it is Stream.Unsupported })
        assertEquals(listOf(111L, 102L, 130L, -1L), list.map { (it as Stream.Unsupported).type })
        assertEquals(emptyList<PlistValue>(), (SetupNegotiation.streamsResponse(emptyList())["streams"] as PlistValue.Items).items)
    }

    @Test
    fun aStreamEntryThatIsNoDictionaryIsUnsupported() {
        val body = PlistValue.dict("streams" to PlistValue.Items(listOf(text("x"), int(5))))
        val list = (SetupNegotiation.parse(body) as Request.Streams).streams
        assertTrue(list.all { it is Stream.Unsupported })
    }

    @Test
    fun aTypeOneZeroOneStreamIsAnsweredWithItsOwnType() {
        val list = streams(
            PlistValue.dict("type" to int(100), "streamConnectionID" to int(1)),
            PlistValue.dict("type" to int(101), "audioType" to text("default"), "streamConnectionID" to int(2)),
        )
        val alt = list[1] as Stream.Audio
        assertEquals(listOf(100, 101), list.map { (it as Stream.Audio).type })
        assertEquals("2", alt.connectionId)
        assertEquals(int(101), SetupNegotiation.audioEntry(alt, 7300, 7301)["type"])
    }

    @Test
    fun aWireConnectionIdOfTwoToTheSixtyFourMinusOneReadsUnsigned() {
        // {streams: [{type: 100, streamConnectionID: <16-byte integer 0xFFFFFFFFFFFFFFFF>}]}, as CoreFoundation writes it.
        val objects = listOf(
            byteArrayOf(0xd1.toByte(), 1, 2),
            byteArrayOf(0x57) + "streams".toByteArray(),
            byteArrayOf(0xa1.toByte(), 3),
            byteArrayOf(0xd2.toByte(), 4, 5, 6, 7),
            byteArrayOf(0x54) + "type".toByteArray(),
            byteArrayOf(0x5f, 0x10, 18) + "streamConnectionID".toByteArray(),
            byteArrayOf(0x10, 100),
            byteArrayOf(0x14) + ByteArray(8) + ByteArray(8) { 0xff.toByte() },
        )
        val head = "bplist00".toByteArray()
        var body = ByteArray(0)
        val offsets = objects.map { o -> (head.size + body.size).also { body += o } }
        val trailer = ByteArray(32).also {
            it[6] = 1; it[7] = 1; it[15] = objects.size.toByte(); it[31] = (head.size + body.size).toByte()
        }
        val wire = head + body + ByteArray(offsets.size) { offsets[it].toByte() } + trailer

        val request = SetupNegotiation.parse(BinaryPlist.decode(wire)) as Request.Streams
        val audio = request.streams.single() as Stream.Audio
        assertEquals("18446744073709551615", audio.connectionId)
        assertEquals("18446744073709551615", StreamKeys.connectionIdText(audio.rawConnectionId))
    }
}
