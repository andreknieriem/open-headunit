package com.andrerinas.openheadunit.connection.usb.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HciDiagnosticTest {

    private class Chip(
        var rom: Boolean,
        val pageStatus: Int? = 0,
        val failDownloadAt: Int? = null,
        val sticks: Boolean = true,
        val silent: (opcode: Int, nth: Int) -> Boolean = { _, _ -> false },
    ) {
        var loaded = false
        val counts = HashMap<Int, Int>()
    }

    private class FakeLink(val chip: Chip) : HciLink {
        val sent = mutableListOf<ByteArray>()
        val queue = ArrayDeque<HciCodec.Event>()
        var now = 0L

        fun opcodes() = sent.map { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
        fun downloads() = opcodes().count { it == HciCodec.OP_RTK_DOWNLOAD }

        private fun cc(op: Int, vararg ret: Int) = HciCodec.Event(
            0x0E, byteArrayOf(1, (op and 0xFF).toByte(), (op shr 8).toByte()) + ByteArray(ret.size) { ret[it].toByte() }
        )

        override fun send(cmd: ByteArray): Int {
            sent += cmd
            val op = opcodes().last()
            val nth = (chip.counts[op] ?: 0) + 1
            chip.counts[op] = nth
            if (chip.silent(op, nth)) return cmd.size
            when (op) {
                HciCodec.OP_RESET -> {
                    if (chip.loaded && chip.sticks) { chip.rom = false; chip.loaded = false }
                    queue += cc(op, 0)
                }
                HciCodec.OP_READ_LOCAL_VERSION ->
                    if (chip.rom) queue += cc(op, 0, 0x0A, 0x0B, 0x00, 0x0A, 0x5D, 0x00, 0x61, 0x87)
                    else queue += cc(op, 0, 0x0A, 0xC6, 0xDF, 0x0A, 0x5D, 0x00, 0x22, 0xD9)
                HciCodec.OP_RTK_READ_ROM_VERSION -> queue += cc(op, 0, 1)
                HciCodec.OP_RTK_DOWNLOAD -> {
                    val index = cmd[3].toInt() and 0xFF
                    val n = downloads() - 1
                    queue += cc(op, if (chip.failDownloadAt == n) 1 else 0, index)
                    if (index and 0x80 != 0) chip.loaded = true
                }
                HciCodec.OP_RTK_DROP -> { chip.rom = true; chip.loaded = false }
                HciCodec.OP_READ_BD_ADDR -> queue += cc(op, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
                HciCodec.OP_CREATE_CONNECTION -> {
                    queue += HciCodec.Event(0x0F, byteArrayOf(0, 1, 0x05, 0x04))
                    chip.pageStatus?.let {
                        queue += HciCodec.Event(0x03, byteArrayOf(it.toByte(), 0x2A, 0x00, 1, 2, 3, 4, 5, 6, 1, 0))
                    }
                }
                HciCodec.OP_DISCONNECT -> queue += HciCodec.Event(0x0F, byteArrayOf(0, 1, 0x06, 0x04))
            }
            return cmd.size
        }

        override fun read(timeoutMs: Int): HciCodec.Event? =
            queue.removeFirstOrNull() ?: run { now += timeoutMs; null }
    }

    private val files = FirmwareFixtures.build(
        listOf(1 to FirmwareFixtures.patch(100, 1), 2 to FirmwareFixtures.patch(2000, 2))
    ).let { HciDiagnostic.FirmwareFiles("external", it, ByteArray(6)) }

    private val addr = HciCodec.parseAddress("AA:BB:CC:DD:EE:FF")

    private class Run(val lines: List<String>, val ok: Boolean, val link: FakeLink)

    private fun run(
        chip: Chip, address: ByteArray? = addr, drop: Boolean = false, noload: Boolean = false,
        firmware: HciDiagnostic.FirmwareFiles? = files,
    ): Run {
        val link = FakeLink(chip)
        val lines = mutableListOf<String>()
        val ok = HciDiagnostic(link, { firmware }, { lines += it }, { link.now }, { link.now += it })
            .run(address, drop, noload)
        return Run(lines, ok, link)
    }

    private val resetOk = "HCI: reset ok"
    private val ver = "HCI: version 10/10 manufacturer=0x005d"
    private val romState = "HCI: state subver=0x8761 hcirev=0x000b load=needed"
    private val patchedState = "HCI: state subver=0xd922 hcirev=0xdfc6 load=none"
    private val load = listOf(
        "HCI: rom version=0x01",
        "HCI: firmware dir=external fw=${files.fw.size} config=6",
        "HCI: firmware patch chip=2 fragments=8 version=0xdfc6d922",
        "HCI: firmware loaded fragments=8 ms=0",
        resetOk, ver, patchedState,
    )
    private val addressLine = "HCI: address 66:55:44:33:22:11"

    @Test
    fun aRomChipLoadsVerifiesReadsTheAddressAndPages() {
        val r = run(Chip(rom = true))
        assertEquals(
            listOf(resetOk, ver, romState) + load + listOf(addressLine, "HCI: page sent", "HCI: page ok ms=0"),
            r.lines,
        )
        assertTrue(r.ok)
        assertEquals(8, r.link.downloads())
    }

    @Test
    fun aPatchedChipSendsNoFragment() {
        val r = run(Chip(rom = false))
        assertEquals(
            listOf(resetOk, ver, patchedState, addressLine, "HCI: page sent", "HCI: page ok ms=0"), r.lines,
        )
        assertEquals(0, r.link.downloads())
    }

    @Test
    fun dropSendsTheDropThenLoadsAgain() {
        val r = run(Chip(rom = false), drop = true)
        assertEquals(
            listOf(resetOk, ver, patchedState, "HCI: drop sent ret=3", resetOk, ver, romState) + load +
                listOf(addressLine, "HCI: page sent", "HCI: page ok ms=0"),
            r.lines,
        )
        assertEquals(HciCodec.OP_RTK_DROP, r.link.opcodes()[2])
    }

    @Test
    fun dropWithNoloadLeavesTheRomChipAndStillPages() {
        val r = run(Chip(rom = false, pageStatus = 4), drop = true, noload = true)
        assertEquals(
            listOf(
                resetOk, ver, patchedState, "HCI: drop sent ret=3", resetOk, ver, romState,
                "HCI: load skipped", addressLine, "HCI: page sent", "HCI: page 0x04 ms=0",
            ),
            r.lines,
        )
        assertTrue(r.ok)
        assertEquals(0, r.link.downloads())
    }

    @Test
    fun noAddressSkipsThePage() {
        val r = run(Chip(rom = false), address = null)
        assertEquals(listOf(resetOk, ver, patchedState, addressLine, "HCI: page skipped"), r.lines)
        assertTrue(r.ok)
    }

    @Test
    fun aPageMissIsAnAnswerNotAFailure() {
        val r = run(Chip(rom = false, pageStatus = 4))
        assertEquals("HCI: page 0x04 ms=0", r.lines.last())
        assertTrue(r.ok)
    }

    private fun failed(step: String, reason: String) = "HCI: failed step=$step reason=$reason"

    @Test
    fun aTimeoutAtEachStepNamesThatStepAndStopsThere() {
        val cases = listOf(
            Triple("reset", HciCodec.OP_RESET, 1),
            Triple("version", HciCodec.OP_READ_LOCAL_VERSION, 1),
            Triple("rom-version", HciCodec.OP_RTK_READ_ROM_VERSION, 1),
            Triple("firmware-download", HciCodec.OP_RTK_DOWNLOAD, 3),
            Triple("firmware-reset", HciCodec.OP_RESET, 2),
            Triple("firmware-verify", HciCodec.OP_READ_LOCAL_VERSION, 2),
            Triple("address", HciCodec.OP_READ_BD_ADDR, 1),
        )
        for ((step, op, nth) in cases) {
            val r = run(Chip(rom = true, silent = { o, n -> o == op && n == nth }))
            assertFalse(step, r.ok)
            assertTrue(step, r.lines.last().startsWith("HCI: failed step=$step reason="))
            assertEquals(step, op, r.link.opcodes().last())
        }
    }

    @Test
    fun aPageThatNeverAnswersFailsAtPage() {
        val r = run(Chip(rom = false, pageStatus = null))
        assertEquals(failed("page", "timeout"), r.lines.last())
        assertFalse(r.ok)
    }

    @Test
    fun aDownloadStatusOfOneAtFragmentFiveStopsTheLoad() {
        val r = run(Chip(rom = true, failDownloadAt = 5))
        assertEquals(failed("firmware-download", "status 0x01 at fragment 5"), r.lines.last())
        assertEquals(6, r.link.downloads())
        assertFalse(r.ok)
    }

    @Test
    fun aChipThatKeepsTheRomVersionFailsVerify() {
        val r = run(Chip(rom = true, sticks = false))
        assertEquals(failed("firmware-verify", "subver=0x8761 expected 0xd922"), r.lines.last())
        assertFalse(r.ok)
    }

    @Test
    fun missingFirmwareOnARomChipSendsNoFragment() {
        val r = run(Chip(rom = true), firmware = null)
        assertEquals(failed("firmware-file", "files not found"), r.lines.last())
        assertEquals(0, r.link.downloads())
        assertFalse(r.ok)
    }

    @Test
    fun aFirmwareFileWithNoPatchForTheChipFailsAtParse() {
        val bad = HciDiagnostic.FirmwareFiles("internal", ByteArray(40), null)
        val r = run(Chip(rom = true), firmware = bad)
        assertTrue(r.lines.last().startsWith("HCI: failed step=firmware-parse reason="))
        assertEquals(0, r.link.downloads())
    }

    @Test
    fun aGoodRequestPrintsOnlyTheStartLine() {
        val lines = mutableListOf<String>()
        val request = HciDiagnostic.begin("AA:BB:CC:DD:EE:FF", false, false, null, 35) { lines += it }
        assertNotNull(request)
        assertEquals(listOf("HCI: start page=set drop=false noload=false notice=none sdk=35"), lines)
    }

    @Test
    fun aBadPageValueStillPrintsStartFirstThenFailsAtInput() {
        val lines = mutableListOf<String>()
        assertNull(HciDiagnostic.begin("nonsense", true, false, null, 35) { lines += it })
        assertEquals(
            listOf(
                "HCI: start page=set drop=true noload=false notice=none sdk=35",
                failed("input", "page is not a Bluetooth address"),
                "HCI: end result=failed claim=none",
            ),
            lines,
        )
    }

    @Test
    fun aBadNoticeValueDoesTheSame() {
        val lines = mutableListOf<String>()
        assertNull(HciDiagnostic.begin(null, false, false, "maybe", 35) { lines += it })
        assertEquals("HCI: start page=none drop=false noload=false notice=none sdk=35", lines[0])
        assertEquals(failed("input", "notice must be raise or clear"), lines[1])
        assertEquals("HCI: end result=failed claim=none", lines[2])
    }
}
