package com.andrerinas.openheadunit.secondscreen.rtp

import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Properties
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Real-time end to end runs of the RTP push on this computer, skipped unless
 * `app/build/rtp-harness/harness.properties` exists (`tools/second-screen/rtp-harness/run.sh` writes it).
 * A live x264 encoder stands in for the phone and starts a new IDR when asked; a proxy loses packets.
 */
class RtpPushHarnessTest {

    private val config = File("build/rtp-harness/harness.properties")

    private class Arm(
        val name: String,
        val refreshS: Int,
        val proxyArgs: List<String>,
        val ffmpegReceiver: Boolean = false,
        val check: (Map<String, String>, Int) -> String?,
    )

    private val arms = listOf(
        Arm("A0-no-refresh-burst-loss", 0, listOf("--drop-at", "15")) { g, _ ->
            if (g["unrecovered"] == "1") null else "a lost burst with no refresh should never recover inside one GOP"
        },
        Arm("A1-refresh-burst-loss", 10, listOf("--drop-at", "15")) { g, r ->
            val max = g["broken_max_s"]?.toDouble() ?: 99.0
            if (g["unrecovered"] == "0" && max <= r + 2.5) null else "broken for ${max}s, limit ${r + 2.5}s"
        },
        Arm("A2-refresh-late-join", 10, listOf("--start-at", "17")) { g, r ->
            // The grader's clock starts at the first datagram it gets, which is the join.
            val first = g["first_picture_s"]?.toDoubleOrNull() ?: 99.0
            if (first <= r + 2.5) null else "first picture ${first}s after the join, limit ${r + 2.5}s"
        },
        // A small socket buffer drained slowly, as an embedded receiver may be; an unpaced keyframe overflows it.
        Arm("A3-refresh-slow-small-buffer", 10, emptyList()) { g, _ ->
            val first = g["first_picture_s"]?.toDoubleOrNull() ?: 99.0
            if (first <= 2.5) null else "first picture at ${first}s"
        },
        Arm("A4-ffmpeg-sdp-late-join", 10, listOf("--start-at", "5"), ffmpegReceiver = true) { g, _ ->
            val frames = g["frames"]?.toInt() ?: 0
            val distinct = g["distinct"]?.toInt() ?: 0
            if (frames > 0 && distinct > 1) null else "FFmpeg decoded $frames frames, $distinct distinct"
        },
    )

    @Test
    fun `the push recovers from loss and a late receiver gets a picture`() {
        assumeTrue("no harness config", config.isFile)
        val p = Properties().apply { config.inputStream().use { load(it) } }
        val out = File(p.getProperty("out")).apply { mkdirs() }
        val only = p.getProperty("arms", "").split(',').filter { it.isNotBlank() }
        val failures = ArrayList<String>()
        for (arm in arms) {
            if (only.isNotEmpty() && only.none { arm.name.startsWith(it) }) continue
            val grade = runArm(arm, p, out)
            val verdict = arm.check(grade, arm.refreshS)
            val line = "${arm.name} ${if (verdict == null) "PASS" else "FAIL: $verdict"} $grade"
            File(out, "summary.txt").appendText(line + "\n")
            println(line)
            if (verdict != null) failures.add(line)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun runArm(arm: Arm, p: Properties, out: File): Map<String, String> {
        val python = p.getProperty("python")
        val tools = p.getProperty("tools")
        val ffmpeg = p.getProperty("ffmpeg")
        val seconds = p.getProperty("seconds", "50").toInt()
        val log = File(out, "${arm.name}.harness.log").apply { writeText("") }
        val previous = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = log.appendText("${System.nanoTime() / 1_000_000} $msg\n")
        }
        val proxyPort = freePort()
        val receiverPort = freePort()
        val sdp = File(out, "${arm.name}.sdp").apply { writeText(RtpDestinationPolicy.sdp("127.0.0.1", receiverPort) + "\n") }
        val receiver = if (arm.ffmpegReceiver) {
            // The command the settings screen shows, with the picture written as hashes instead of a window.
            process(File(out, "${arm.name}.receiver.txt"), ffmpeg, "-hide_banner", "-protocol_whitelist", "file,udp,rtp",
                "-i", sdp.path, "-f", "framemd5", "-")
        } else {
            process(File(out, "${arm.name}.receiver.txt"), python, "-I", "$tools/rtp_grade.py", "--port", "$receiverPort",
                "--bind", "127.0.0.1", "--label", arm.name, "--annexb", File(out, "${arm.name}.h264").path)
        }
        val proxy = process(File(out, "${arm.name}.proxy.txt"), python, "-I", "$tools/lossy_proxy.py", "--listen", "$proxyPort",
            "--forward", "127.0.0.1:$receiverPort", *arm.proxyArgs.toTypedArray(),
            *(if (arm.name.startsWith("A3")) p.getProperty("a3", "--rcvbuf 32768 --drain-pps 1500").split(' ').toTypedArray() else emptyArray()))
        Thread.sleep(800)
        val socket = DatagramSocket()
        val target = DatagramPacket(ByteArray(0), 0, InetAddress.getLoopbackAddress(), proxyPort)
        val rtpSocket = object : RtpSocket {
            override val target = "127.0.0.1:$proxyPort"
            override fun open() = "loopback"
            override fun send(buf: ByteArray, off: Int, len: Int) { target.setData(buf, off, len); socket.send(target) }
            override fun close() = socket.close()
        }
        val noPacing = p.getProperty("pacing") == "off"
        val output = if (noPacing) RtpStreamOutput(rtpSocket, System::nanoTime, Random(), arm.refreshS * 1000L, {})
            else RtpStreamOutput(rtpSocket, System::nanoTime, Random(), arm.refreshS * 1000L)
        val phone = FakePhone(ffmpeg, p.getProperty("size", "1280x720"), p.getProperty("fps", "30").toInt(),
            p.getProperty("gopSeconds", "60").toInt(), p.getProperty("phoneLatencyMs", "1000").toLong(), log)
        output.onKeyframeNeeded = phone::request
        output.start()
        try {
            phone.run(seconds) { b, o, l -> output.onAccessUnit(b, o, l) }
        } finally {
            output.stop()
            AppLog.LOGGER = previous
        }
        proxy.waitFor(15, TimeUnit.SECONDS)
        if (arm.ffmpegReceiver) {
            Thread.sleep(2000)
            receiver.destroy()
        }
        receiver.waitFor(15, TimeUnit.SECONDS)
        proxy.destroyForcibly(); receiver.destroyForcibly()
        val text = File(out, "${arm.name}.receiver.txt").readText()
        return if (arm.ffmpegReceiver) {
            val hashes = text.lines().filter { it.isNotBlank() && !it.startsWith("#") && it.count { c -> c == ',' } >= 5 }
                .map { it.substringAfterLast(',').trim() }
            mapOf("frames" to "${hashes.size}", "distinct" to "${hashes.toSet().size}")
        } else {
            text.lines().firstOrNull { it.startsWith("GRADE ") }.orEmpty().removePrefix("GRADE ").split(' ')
                .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        }
    }

    private fun process(log: File, vararg cmd: String): Process =
        ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(log).start()

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    /** An x264 encoder in place of the phone: a long GOP, and a fresh IDR about [latencyMs] after a request. */
    private class FakePhone(
        val ffmpeg: String, val size: String, val fps: Int, val gopSeconds: Int, val latencyMs: Long, val log: File,
    ) {
        @Volatile private var requestedAt = 0L

        fun request() {
            if (requestedAt == 0L) requestedAt = System.nanoTime()
            log.appendText("${System.nanoTime() / 1_000_000} PHONE keyframe requested\n")
        }

        fun run(seconds: Int, sink: (ByteArray, Int, Int) -> Unit) {
            val begin = System.nanoTime()
            val end = begin + seconds * 1_000_000_000L
            val frameNanos = 1_000_000_000L / fps
            var next = System.nanoTime()
            var encoder = encoder(0.0)
            var units = AccessUnits(encoder.inputStream)
            try {
                while (System.nanoTime() < end) {
                    val asked = requestedAt
                    if (asked != 0L) {
                        // The phone stops the sink, then starts a new stream with an IDR.
                        val resume = asked + latencyMs * 1_000_000
                        while (System.nanoTime() < resume) Thread.sleep(5)
                        encoder.destroyForcibly()
                        encoder = encoder((System.nanoTime() - begin) / 1e9)
                        units = AccessUnits(encoder.inputStream)
                        requestedAt = 0L
                        next = System.nanoTime()
                        log.appendText("${System.nanoTime() / 1_000_000} PHONE new stream with an IDR\n")
                    }
                    val au = units.next() ?: break
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    next += frameNanos
                    sink(au, 0, au.size)
                }
            } finally {
                encoder.destroyForcibly()
            }
        }

        // A new stream starts where the picture had got to, so a receiver sees it keep moving.
        private fun encoder(atSeconds: Double): Process = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", "%.3f".format(java.util.Locale.US, atSeconds),
            "-f", "lavfi", "-i", "testsrc2=size=$size:rate=$fps",
            "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-profile:v", "baseline",
            "-pix_fmt", "yuv420p", "-g", "${gopSeconds * fps}", "-keyint_min", "${gopSeconds * fps}", "-sc_threshold", "0",
            "-b:v", "4M", "-maxrate", "4M", "-bufsize", "2M", "-x264-params", "aud=1:sliced-threads=0:threads=1", "-f", "h264", "-",
        ).redirectError(File("/dev/null")).start()
    }

    /** Splits an Annex-B stream at its access unit delimiters and drops them, as the phone sends none. */
    private class AccessUnits(private val input: InputStream) {
        private val buf = ByteArrayOutputStream()
        private var data = ByteArray(0)
        private var start = -1
        private var eof = false

        fun next(): ByteArray? {
            while (true) {
                val second = if (start >= 0) audAfter(start + 4) else -1
                if (second >= 0) return take(second)
                if (eof) return if (start >= 0 && data.size > start + 6) take(data.size) else null
                val chunk = ByteArray(65536)
                val n = input.read(chunk)
                if (n < 0) { eof = true; continue }
                buf.write(chunk, 0, n)
                data = buf.toByteArray()
                if (start < 0) start = audAfter(0)
            }
        }

        private fun take(endExclusive: Int): ByteArray {
            // The AUD is a 4-byte start code and 2 bytes; the rest is the unit.
            val unit = data.copyOfRange(start + 6, endExclusive)
            val rest = data.copyOfRange(endExclusive, data.size)
            buf.reset(); buf.write(rest)
            data = rest
            start = 0
            return unit
        }

        private fun audAfter(from: Int): Int {
            var i = from
            while (i + 4 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 &&
                    data[i + 3].toInt() == 1 && (data[i + 4].toInt() and 0x1F) == 9) return i
                i++
            }
            return -1
        }
    }
}
