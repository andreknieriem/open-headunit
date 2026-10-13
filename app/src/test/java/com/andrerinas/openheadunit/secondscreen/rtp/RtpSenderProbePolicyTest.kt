package com.andrerinas.openheadunit.secondscreen.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpSenderProbePolicyTest {

    private val destination = byteArrayOf(192.toByte(), 170.toByte(), 8, 101)
    private val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops"

    private fun row(n: Int, local: String, remote: String, uid: Int) =
        "  $n: $local $remote 01 00000000:00000000 00:00000000 00000000 $uid        0 $n 2 0000000000000000 0"

    private fun summary(vararg tables: String, own: Int = 10071) =
        RtpSenderProbePolicy.socketSummary(tables.toList(), destination, 25000, own) { "pkg$it" }

    @Test
    fun `a v4 row to the destination is listed under to`() {
        val line = summary(header + "\n" + row(1, "0100007F:1388", "6508AAC0:61A8", 10099))
        assertTrue(line, line.startsWith("to 192.170.8.101:25000 uid 10099 (pkg10099);"))
    }

    @Test
    fun `a v4-mapped udp6 row to the destination is listed`() {
        val v6 = row(1, "0000000000000000FFFF00000100007F:1388", "0000000000000000FFFF00006508AAC0:61A8", 10042)
        assertTrue(summary(header + "\n" + v6).startsWith("to 192.170.8.101:25000 uid 10042 (pkg10042);"))
    }

    @Test
    fun `a row on the destination port as local port is listed`() {
        val line = summary(header + "\n" + row(1, "00000000:61A8", "00000000:0000", 10050))
        assertTrue(line, line.startsWith("to 192.170.8.101:25000 uid 10050 (pkg10050);"))
    }

    @Test
    fun `app rows group by uid with a count and our uid reads this app`() {
        val t = header + "\n" + row(1, "00000000:1111", "00000000:0000", 10071) +
            "\n" + row(2, "00000000:2222", "00000000:0000", 10071) +
            "\n" + row(3, "00000000:3333", "00000000:0000", 10200)
        assertEquals("to 192.170.8.101:25000 none; app uids uid 10071 (this app) x2, uid 10200 (pkg10200) x1", summary(t))
    }

    @Test
    fun `system uids are left out`() {
        val t = header + "\n" + row(1, "00000000:0035", "00000000:0000", 1000) +
            "\n" + row(2, "00000000:0044", "00000000:0000", 0)
        assertEquals("to 192.170.8.101:25000 none; app uids none", summary(t))
    }

    @Test
    fun `the header line and a short line are skipped`() {
        assertEquals("to 192.170.8.101:25000 none; app uids none", summary(header + "\n  1: 0000\n\n"))
    }

    @Test
    fun `no app rows gives none`() {
        assertEquals("to 192.170.8.101:25000 none; app uids none", summary(""))
    }

    @Test
    fun `at most 8 uids are listed`() {
        val t = (1..12).joinToString("\n") { row(it, "00000000:${1000 + it}", "00000000:0000", 10000 + it) }
        assertEquals(8, Regex("uid 100").findAll(summary(t).substringAfter("app uids")).count())
    }

    @Test
    fun `displayLine matches the DisplayTargets format`() {
        assertEquals("display added 14:PresentationScreen 800x600@320 presentation",
            RtpSenderProbePolicy.displayLine("added", 14, "PresentationScreen", 800, 600, 320, true))
        assertEquals("display changed 3:HDMI 1920x1080@160",
            RtpSenderProbePolicy.displayLine("changed", 3, "HDMI", 1920, 1080, 160, false))
        assertEquals("display removed 14", RtpSenderProbePolicy.displayRemovedLine(14))
    }
}
