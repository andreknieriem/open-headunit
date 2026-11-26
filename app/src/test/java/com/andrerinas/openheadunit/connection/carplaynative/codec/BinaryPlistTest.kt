package com.andrerinas.openheadunit.connection.carplaynative.codec

import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.hex
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Vectors come from LIVI `ts_vectors.json` and `bplist.rs`, and from iOS request bodies that
 * the xcertplay tests carry. They hold no device identifiers.
 */
class BinaryPlistTest {

    private fun text(s: String) = PlistValue.Text(s)
    private fun int(n: Long) = PlistValue.Integer(n)

    private val liviValue = PlistValue.dict(
        "type" to text("hidSendReport"),
        "uuid" to text("2a2a2a2a"),
        "hidReport" to PlistValue.Data(byteArrayOf(0, 1, 2, 3)),
        "n0" to int(0),
        "n255" to int(255),
        "n256" to int(256),
        "n65536" to int(65536),
        "big" to int(1L shl 40),
        "neg" to PlistValue.Real(-1.0),
        "frac" to PlistValue.Real(0.5),
        "t" to PlistValue.Bool(true),
        "f" to PlistValue.Bool(false),
        "uni" to text("Bülli"),
        "list" to PlistValue.Items(listOf(int(1), text("two"), PlistValue.Items(listOf(int(3))))),
        "empty" to PlistValue.Dict(emptyList()),
        "long" to text("x".repeat(20)),
        "many" to PlistValue.Items((0L until 20L).map { int(it) }),
    )

    @Test
    fun theLiviBplistVectorMatches() {
        val bytes = BinaryPlist.encode(liviValue)
        assertEquals(458, bytes.size)
        assertEquals("b4e3e93557bb97d6207978c92e3a52f533a60cd3f0e9034a4253c080b83e872a", CarPlayCrypto.sha256(bytes).toHex())
        assertEquals(liviValue, BinaryPlist.decode(bytes))
    }

    @Test
    fun aRoundTripKeepsTypesAndKeyOrder() {
        val value = PlistValue.dict(
            "type" to text("hidSendReport"),
            "hidReport" to PlistValue.Data(byteArrayOf(0, 1, 2)),
            "big" to int(0x1_0000_0000L),
            "ratio" to PlistValue.Real(-1.5),
            "on" to PlistValue.Bool(true),
            "list" to PlistValue.Items(listOf(int(1), int(300), int(70000))),
        )
        assertEquals(value, BinaryPlist.decode(BinaryPlist.encode(value)))
    }

    @Test
    fun aHighBitIdentifierSurvivesAsUnsigned() {
        val seed = int(-8958586947476470172L)
        val bytes = BinaryPlist.encode(seed)
        assertEquals(0x13, bytes[8].toInt())
        assertEquals(seed, BinaryPlist.decode(bytes))
    }

    @Test
    fun aDateReadsAsItsSeconds() {
        val bytes = BinaryPlist.encode(PlistValue.Real(813_000_000.5))
        assertEquals(0x23, bytes[8].toInt())
        bytes[8] = 0x33
        assertEquals(PlistValue.Real(813_000_000.5), BinaryPlist.decode(bytes))
        bytes[8] = 0x32
        assertThrows(PlistException::class.java) { BinaryPlist.decode(bytes) }
    }

    @Test
    fun longContainersUseACountObjectAndManyObjectsUseWideReferences() {
        val long = PlistValue.Items((0L until 300L).map { int(it) })
        val bytes = BinaryPlist.encode(long)
        assertEquals(long, BinaryPlist.decode(bytes))
        assertEquals(0xaf, bytes[8].toInt() and 0xff)
        val strings = PlistValue.Items((0 until 400).map { text("item$it") })
        assertEquals(strings, BinaryPlist.decode(BinaryPlist.encode(strings)))
    }

    @Test
    fun aBlobOver255BytesAndNonAsciiTextRoundTrip() {
        val value = PlistValue.dict(
            "data" to PlistValue.Data(ByteArray(300) { it.toByte() }),
            "utf16" to text("车机·CarPlay"),
        )
        assertEquals(value, BinaryPlist.decode(BinaryPlist.encode(value)))
    }

    @Test
    fun garbageAndLoopsAreRefused() {
        assertThrows(PlistException::class.java) { BinaryPlist.decode("nope".toByteArray()) }
        assertThrows(PlistException::class.java) { BinaryPlist.decode(ByteArray(39)) }
        assertThrows(PlistException::class.java) { BinaryPlist.decode("bplist00".toByteArray() + ByteArray(32)) }
        val looped = "bplist00".toByteArray() + byteArrayOf(0xa1.toByte(), 0x00)
        val trailer = ByteArray(32)
        trailer[6] = 1
        trailer[7] = 1
        trailer[15] = 1
        trailer[31] = looped.size.toByte()
        val bytes = looped + byteArrayOf(8) + trailer
        assertEquals(
            "bplist: nested too deep",
            assertThrows(PlistException::class.java) { BinaryPlist.decode(bytes) }.message,
        )
    }

    @Test
    fun aCountPastTheBufferIsRefusedWithoutAllocation() {
        // A data object that claims 2^31 bytes in a 60 byte buffer.
        val head = "bplist00".toByteArray() + byteArrayOf(0x4f, 0x12, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        val trailer = ByteArray(32)
        trailer[6] = 1
        trailer[7] = 1
        trailer[15] = 1
        trailer[31] = head.size.toByte()
        assertThrows(PlistException::class.java) { BinaryPlist.decode(head + byteArrayOf(8) + trailer) }
    }

    @Test
    fun decodesTheIosInfoRequest() {
        val request = BinaryPlist.decode(hex(INFO_REQUEST)) as PlistValue.Dict
        assertEquals(
            listOf("maps:/car/instrumentcluster/map", "maps:/car/instrumentcluster/instructioncard", "maps:/car/instrumentcluster"),
            request["altScreenURLs"]!!.asItems!!.map { it.asText },
        )
        assertEquals(
            listOf("maps:", "mobilephone:", "nowplaying:", "messages:"),
            request["uiContextURLs"]!!.asItems!!.map { it.asText },
        )
    }

    @Test
    fun decodesTheTunnelStreamSetupWithTwoByteOffsetsAndAHighBitSeed() {
        val stream = streams(IAP_TUNNEL_SETUP).single()
        assertEquals(130L, stream["type"]!!.asLong)
        assertEquals(2L, stream["controlType"]!!.asLong)
        assertEquals(-8958586947476470172L, stream["seed"]!!.asLong)
        assertEquals(true, stream["wantsDedicatedSocket"]!!.asBool)
        assertEquals("E9459FD0-BCAD-4C45-820F-1E72447EF2F2", stream["clientTypeUUID"]!!.asText)
        assertEquals("02:00:00:00:00:02-RCS-422", stream["channelID"]!!.asText)
    }

    @Test
    fun decodesTheAltScreenStreamSetup() {
        val stream = streams(ALT_SCREEN_SETUP).single()
        assertEquals(111L, stream["type"]!!.asLong)
        assertEquals(75L, stream["latencyMs"]!!.asLong)
        assertEquals(5961060656565797976L, stream["streamConnectionID"]!!.asLong)
        assertEquals("b7e6c5a0-2222-4000-8000-000000000002", stream["uuid"]!!.asText)
    }

    @Test
    fun reEncodingARequestKeepsItsContent() {
        for (body in listOf(INFO_REQUEST, IAP_TUNNEL_SETUP, ALT_SCREEN_SETUP)) {
            val decoded = BinaryPlist.decode(hex(body))
            assertEquals(decoded, BinaryPlist.decode(BinaryPlist.encode(decoded)))
        }
    }

    /** bplist00, then [objects], then a one-byte offset table and the trailer. */
    private fun bplist(objects: List<ByteArray>, offsetTable: ByteArray? = null, offsetSize: Int = 1): ByteArray {
        val head = "bplist00".toByteArray()
        var body = ByteArray(0)
        val offsets = ArrayList<Int>()
        for (o in objects) { offsets += head.size + body.size; body += o }
        val table = offsetTable ?: ByteArray(offsets.size) { offsets[it].toByte() }
        val count = if (offsetTable == null) objects.size else offsetTable.size / offsetSize
        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = 1
        trailer[15] = count.toByte()
        trailer[31] = (head.size + body.size).toByte()
        return head + body + table + trailer
    }

    @Test
    fun anOffsetOrCountThatWrapsIsAPlistError() {
        val farOffset = bplist(emptyList(), byteArrayOf(0x7f) + ByteArray(7) { 0xff.toByte() }, offsetSize = 8)
        assertThrows(PlistException::class.java) { BinaryPlist.decode(farOffset) }
        val hugeData = byteArrayOf(0x4f, 0x13, 0x7f) + ByteArray(6) { 0xff.toByte() } + byteArrayOf(0xf0.toByte())
        assertThrows(PlistException::class.java) { BinaryPlist.decode(bplist(listOf(hugeData))) }
    }

    @Test
    fun aSixteenByteIntegerKeepsItsLowBitsWhenTheTopHalfIsZero() {
        val max = byteArrayOf(0x14) + ByteArray(8) + ByteArray(8) { 0xff.toByte() }
        assertEquals(PlistValue.Integer(-1), BinaryPlist.decode(bplist(listOf(max))))
        val tooBig = byteArrayOf(0x14) + ByteArray(7) + byteArrayOf(1) + ByteArray(8)
        assertThrows(PlistException::class.java) { BinaryPlist.decode(bplist(listOf(tooBig))) }
    }

    @Test(timeout = 5_000)
    fun sharedReferencesCannotMakeTheWalkExponential() {
        val arrays = List(63) { byteArrayOf(0xa2.toByte(), (it + 1).toByte(), (it + 1).toByte()) }
        val objects = arrays + listOf(byteArrayOf(0xa1.toByte(), 64), byteArrayOf(0x10, 5))
        assertThrows(PlistException::class.java) { BinaryPlist.decode(bplist(objects)) }
    }

    private fun streams(body: String): List<PlistValue.Dict> =
        (BinaryPlist.decode(hex(body)) as PlistValue.Dict)["streams"]!!.asItems!!.map { it as PlistValue.Dict }

    private companion object {
        const val INFO_REQUEST =
            "62706c6973743030d2010203075d616c7453637265656e55524c735d7569436f6e7465787455524c73a30405065f101f" +
            "6d6170733a2f6361722f696e737472756d656e74636c75737465722f6d61705f102b6d6170733a2f6361722f696e7374" +
            "72756d656e74636c75737465722f696e737472756374696f6e636172645f101b6d6170733a2f6361722f696e73747275" +
            "6d656e74636c7573746572a408090a0b556d6170733a5c6d6f62696c6570686f6e653a5b6e6f77706c6179696e673a59" +
            "6d657373616765733a080d1b292d4f7d9ba0a6b3bf0000000000000101000000000000000c0000000000000000000000" +
            "00000000c9"
        const val IAP_TUNNEL_SETUP =
            "62706c6973743030d101025773747265616d73a103d80405060708090a0b0c0d0e0f101111135b636f6e74726f6c5479" +
            "7065596368616e6e656c494454736565645a636c69656e745555494454747970655f101477616e747344656469636174" +
            "6564536f636b65745f100f73656e644d657373616765417349735e636c69656e74547970655555494410025f10193032" +
            "3a30303a30303a30303a30303a30322d5243532d3432321383acb4a2ca36c6645f102431344631433243362d43413442" +
            "2d343438312d424339412d433443414246423135384646108209095f102445393435394644302d424341442d34433435" +
            "2d383230462d3145373234343745463246320008000b0013001500260032003c0041004c00510068007a0089008b00a7" +
            "00b000d700d900da00db0000000000000201000000000000001400000000000000000000000000000102"
        const val ALT_SCREEN_SETUP =
            "62706c6973743030d101025773747265616d73a103d40405060708090a0b5f101273747265616d436f6e6e656374696f" +
            "6e4944596c6174656e63794d73547479706554757569641352b9f11587294458104b106f5f102462376536633561302d" +
            "323232322d343030302d383030302d303030303030303030303032080b13151e333d4247505254000000000000010100" +
            "0000000000000c0000000000000000000000000000007b"
    }
}
