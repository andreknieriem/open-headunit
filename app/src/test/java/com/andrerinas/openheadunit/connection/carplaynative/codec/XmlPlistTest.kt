package com.andrerinas.openheadunit.connection.carplaynative.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Base64 cases are RFC 4648 section 10. The plist text is the form a lockdown daemon sends. */
class XmlPlistTest {

    private fun parse(s: String) = XmlPlist.parse(s.toByteArray())

    private val header =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">\n"

    @Test
    fun parsesAPrettyPrintedLockdownReply() {
        val dict = parse(
            header +
                "<dict>\n\t<key>Request</key>\n\t<string>StartService</string>\n" +
                "\t<key>Port</key>\n\t<integer>49152</integer>\n" +
                "\t<key>EnableServiceSSL</key>\n\t<true/>\n" +
                "\t<key>Flag</key>\n\t<false/>\n" +
                "\t<key>PublicKey</key>\n\t<data>\n\tYWJj\n\tZGVm\n\t</data>\n" +
                "\t<key>List</key>\n\t<array>\n\t\t<string>a</string>\n\t\t<integer>-3</integer>\n\t</array>\n" +
                "\t<key>Scale</key>\n\t<real>1.5</real>\n" +
                "</dict>\n</plist>\n",
        )
        assertEquals("StartService", dict["Request"]!!.asText)
        assertEquals(49152L, dict["Port"]!!.asLong)
        assertEquals(true, dict["EnableServiceSSL"]!!.asBool)
        assertEquals(false, dict["Flag"]!!.asBool)
        assertArrayEquals("abcdef".toByteArray(), dict["PublicKey"]!!.asBytes)
        assertEquals(listOf(PlistValue.Text("a"), PlistValue.Integer(-3)), dict["List"]!!.asItems)
        assertEquals(PlistValue.Real(1.5), dict["Scale"])
    }

    @Test
    fun encodeThenParseKeepsEveryTypeAndTheKeyOrder() {
        val value = PlistValue.dict(
            "Z" to PlistValue.Text("last <&> \"q\" 'a'"),
            "A" to PlistValue.Integer(7),
            "Data" to PlistValue.Data(ByteArray(200) { it.toByte() }),
            "Empty" to PlistValue.Text(""),
            "Spaces" to PlistValue.Text("  "),
            "Nested" to PlistValue.dict("k" to PlistValue.Items(listOf(PlistValue.Bool(true), PlistValue.Bool(false)))),
        )
        val parsed = XmlPlist.parse(XmlPlist.encode(value))
        assertEquals(value, parsed)
        assertEquals(listOf("Z", "A", "Data", "Empty", "Spaces", "Nested"), parsed.entries.map { it.first })
    }

    @Test
    fun theWriterEmitsTheAppleHeaderAndEscapes() {
        val xml = String(XmlPlist.encode(PlistValue.dict("a&b" to PlistValue.Text("<x>"))))
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">" +
                "<plist version=\"1.0\"><dict><key>a&amp;b</key><string>&lt;x&gt;</string></dict></plist>",
            xml,
        )
    }

    @Test
    fun entitiesCdataAndEmptyElementsRead() {
        val dict = parse(
            header + "<dict><key>a</key><string>x&amp;y&#65;&#x42;<![CDATA[<z>]]></string>" +
                "<key>b</key><string/><!-- note --></dict></plist>",
        )
        assertEquals("x&yAB<z>", dict["a"]!!.asText)
        assertEquals("", dict["b"]!!.asText)
    }

    @Test
    fun anUnsignedSixtyFourBitIntegerKeepsItsBits() {
        val dict = parse(header + "<dict><key>id</key><integer>18446744073709551615</integer></dict></plist>")
        assertEquals(-1L, dict["id"]!!.asLong)
        assertEquals(
            "<integer>18446744073709551615</integer>",
            String(XmlPlist.encode(PlistValue.dict("id" to PlistValue.Integer(-1)))).substringAfter("</key>").substringBefore("</dict>"),
        )
    }

    @Test
    fun malformedInputIsRefused() {
        val bad = listOf(
            header + "<array></array></plist>",
            header + "<dict><key>a</key><string>1</string><key>a</key><string>2</string></dict></plist>",
            header + "<dict><key>a</key></dict></plist>",
            header + "<dict><key>a</key><integer>x</integer></dict></plist>",
            header + "<dict><key>a</key><data>@@@@</data></dict></plist>",
            header + "<dict><key>a</key><date>2020</date></dict></plist>",
            header + "<dict></dict></plist> trailing",
            header + "<dict></dict>",
            "<?xml version=\"1.0\"?><!DOCTYPE plist [<!ENTITY x \"y\">]><plist><dict/></plist>",
            header + "<dict><key>a</key><string>&nope;</string></dict></plist>",
        )
        for (xml in bad) assertThrows(xml, PlistException::class.java) { parse(xml) }
    }

    @Test
    fun nestingPastTheLimitIsRefused() {
        val deep = "<array>".repeat(70) + "</array>".repeat(70)
        assertThrows(PlistException::class.java) { parse(header + "<dict><key>a</key>$deep</dict></plist>") }
    }

    @Test
    fun base64MatchesRfc4648() {
        val cases = listOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        for ((plain, encoded) in cases) {
            assertEquals(encoded, PlainBase64.encode(plain.toByteArray()))
            assertArrayEquals(plain.toByteArray(), PlainBase64.decode(encoded))
        }
        assertThrows(PlistException::class.java) { PlainBase64.decode("Zg=") }
        assertThrows(PlistException::class.java) { PlainBase64.decode("Z===") }
        assertThrows(PlistException::class.java) { PlainBase64.decode("Zm9!") }
    }
}
