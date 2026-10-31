package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.app.AutoStartCheckPolicy.ListFile
import com.andrerinas.openheadunit.app.AutoStartCheckPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartCheckPolicyTest {

    private val pkg = "com.andrerinas.headunitrevived"

    private fun findings(
        isHomeApp: Boolean = false,
        batteryExempt: Boolean? = true,
        backgroundRestricted: Boolean? = false,
        forceStops: List<String> = emptyList(),
        stoppedBy: List<String> = emptyList(),
        listFiles: List<ListFile> = emptyList(),
        recentExits: List<String> = emptyList(),
    ) = AutoStartCheckPolicy.Findings(
        appVersion = "3.6.0", route = "root", device = "alps CMZX65-U1", android = "8.1.0 (API 27)",
        platform = "mt6765", firmware = "CMZX65-U1_V1.2", isHomeApp = isHomeApp,
        batteryExempt = batteryExempt, backgroundRestricted = backgroundRestricted, duraSpeed = true,
        recentExits = recentExits, forceStops = forceStops, stoppedBy = stoppedBy, listFiles = listFiles,
    )

    @Test
    fun `only file names that look like an app list are kept`() {
        val ls = """
            /system/etc/qb_skip_kill_list.txt
            /system/etc/hosts
            /system/etc/white_list.xml
            /vendor/etc/audio_policy.conf
            /oem/AutoRun.cfg
            /system/etc/permissions
        """.trimIndent()
        assertEquals(
            listOf("/system/etc/qb_skip_kill_list.txt", "/system/etc/white_list.xml", "/oem/AutoRun.cfg"),
            AutoStartCheckPolicy.listCandidates(ls),
        )
    }

    @Test
    fun `the listing is piped so an interactive shell does not print columns`() {
        assertTrue(AutoStartCheckPolicy.listCommand("/system/etc").endsWith("| cat"))
    }

    @Test
    fun `a directory name alone does not make a candidate`() {
        assertEquals(emptyList<String>(), AutoStartCheckPolicy.listCandidates("/system/etc/keep/hosts\nls: /odm/etc: No such file"))
    }

    @Test
    fun `the candidate list is capped`() {
        val many = (1..50).joinToString("\n") { "/system/etc/white_$it.txt" }
        assertEquals(AutoStartCheckPolicy.MAX_LIST_FILES, AutoStartCheckPolicy.listCandidates(many).size)
    }

    @Test
    fun `force stops of this app are found with the pid that asked`() {
        val log = """
            10-22 21:05:01.100  812  830 I ActivityManager: Force stopping com.other.app appid=10090 user=0: from pid 1501
            10-22 21:05:01.200  812  830 I ActivityManager: Force stopping $pkg appid=10101 user=0: from pid 1501
            10-22 21:05:01.210  812  830 I am_force_stop: [0,$pkg,0,from pid 1501]
            10-22 21:06:00.000 2000 2000 I OPENHU  : started $pkg
        """.trimIndent()
        val lines = AutoStartCheckPolicy.forceStopLines(log, pkg)
        assertEquals(2, lines.size)
        assertEquals(listOf(1501), AutoStartCheckPolicy.callerPids(lines))
    }

    @Test
    fun `the stop of an app update is not counted`() {
        val log = "10-28 15:22:24.749  1626  1654 I ActivityManager: Force stopping $pkg appid=10060 user=0: pkg removed"
        assertEquals(emptyList<String>(), AutoStartCheckPolicy.forceStopLines(log, pkg))
    }

    @Test
    fun `only the newest force stop lines are kept`() {
        val log = (1..9).joinToString("\n") { "I ActivityManager: Force stopping $pkg appid=1 user=0: from pid $it" }
        val lines = AutoStartCheckPolicy.forceStopLines(log, pkg)
        assertEquals(AutoStartCheckPolicy.MAX_LOG_LINES, lines.size)
        assertTrue(lines.last().endsWith("from pid 9"))
    }

    @Test
    fun `output is taken from between the markers when an adb shell mixes in its prompt`() {
        val command = AutoStartCheckPolicy.bracket("cat /proc/4006/cmdline 2>/dev/null")
        val shellOutput = "emulator64_x86_64_arm64:/ $ $command\n" +
            "OHU_CHECK_BEGIN\n" +
            "com.android.shell\u0000--start\u0000\n" +
            "OHU_CHECK_END\n" +
            "emulator64_x86_64_arm64:/ $ exit"
        val body = AutoStartCheckPolicy.unbracket(shellOutput)
        assertEquals("com.android.shell", AutoStartCheckPolicy.processName(body))
    }

    @Test
    fun `clean output and output without markers pass through`() {
        assertEquals("mt6765", AutoStartCheckPolicy.unbracket("OHU_CHECK_BEGIN\nmt6765\n\nOHU_CHECK_END"))
        assertEquals("mt6765", AutoStartCheckPolicy.unbracket("mt6765"))
    }

    @Test
    fun `the process name is the first argument of cmdline`() {
        assertEquals("com.reglink.services", AutoStartCheckPolicy.processName("com.reglink.services\u0000--start\u0000"))
        assertNull(AutoStartCheckPolicy.processName(""))
    }

    @Test
    fun `verdicts follow the strongest finding`() {
        assertEquals(Verdict.FORCE_STOPPED, AutoStartCheckPolicy.verdict(findings(isHomeApp = true, forceStops = listOf("x"))))
        assertEquals(Verdict.UNKNOWN, AutoStartCheckPolicy.verdict(findings(isHomeApp = true)))
        assertEquals(Verdict.FORCE_STOPPED, AutoStartCheckPolicy.verdict(findings(forceStops = listOf("x"), batteryExempt = false)))
        assertEquals(Verdict.FORCE_STOPPED, AutoStartCheckPolicy.verdict(findings(stoppedBy = listOf("com.reglink.services"))))
        assertEquals(Verdict.FORCE_STOPPED, AutoStartCheckPolicy.verdict(findings(recentExits = listOf("10-22 21:05:01 force-stop"))))
        assertEquals(Verdict.BATTERY, AutoStartCheckPolicy.verdict(findings(batteryExempt = false)))
        assertEquals(Verdict.BATTERY, AutoStartCheckPolicy.verdict(findings(backgroundRestricted = true)))
        assertEquals(Verdict.UNKNOWN, AutoStartCheckPolicy.verdict(findings()))
        assertEquals(Verdict.UNKNOWN, AutoStartCheckPolicy.verdict(findings(batteryExempt = null, backgroundRestricted = null)))
    }

    @Test
    fun `the report carries every finding`() {
        val report = AutoStartCheckPolicy.report(findings(
            batteryExempt = null,
            stoppedBy = listOf("com.reglink.services (pid 1501)"),
            listFiles = listOf(ListFile("/system/etc/white_list.xml", false)),
        ))
        assertTrue(report.contains("Platform: mt6765"))
        assertTrue(report.contains("Battery exempt: unknown"))
        assertTrue(report.contains("Verdict: force stopped"))
        assertTrue(report.contains("  com.reglink.services (pid 1501)"))
        assertTrue(report.contains("  [no app] /system/etc/white_list.xml"))
        assertTrue(report.contains("Recent exits:\n  none"))
    }
}
