/*
 * Copyright 2021-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The load sequence is ported to Kotlin from bumble/drivers/rtk.py (Bumble 0.0.236); RTL8761B only.
 */
package com.andrerinas.openheadunit.connection.usb.hci

import java.util.Locale

/**
 * The fixed rig sequence: reset, version, optional drop, patch load, address, one page. It writes one
 * `HCI:` line per step and stops at the first failure with the step's name.
 */
class HciDiagnostic(
    private val link: HciLink,
    private val firmware: () -> FirmwareFiles?,
    private val log: (String) -> Unit,
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
) {

    class FirmwareFiles(val dir: String, val fw: ByteArray, val config: ByteArray?)

    /** A validated start request. [address] is null when no page was asked for. */
    class Request(val address: ByteArray?, val drop: Boolean, val noload: Boolean, val notice: String?)

    /** True when the sequence ran to its end. A page miss is an answer, so it counts. */
    fun run(address: ByteArray?, drop: Boolean, noload: Boolean): Boolean {
        while (link.read(100) != null) { /* stale events from an earlier session */ }

        if (!reset("reset", 5000)) return false
        var v = version("version") ?: return false
        if (drop) {
            val ret = link.send(HciCodec.command(HciCodec.OP_RTK_DROP))
            log("HCI: drop sent ret=$ret")
            sleep(200)
            while (link.read(100) != null) { /* the drop restarts the chip */ }
            if (!reset("reset", 5000)) return false
            v = version("version") ?: return false
        }

        if (RealtekFirmware.stateOf(v) == RealtekFirmware.ChipState.ROM_8761B) {
            if (noload) log("HCI: load skipped") else if (!load()) return false
        }

        val own = complete(HciCodec.OP_READ_BD_ADDR, HciCodec.command(HciCodec.OP_READ_BD_ADDR), 1000)
            ?.let { HciCodec.bdAddr(it) } ?: return fail("address", "no answer")
        log("HCI: address $own")

        if (address == null) {
            log("HCI: page skipped")
            return true
        }
        return page(address)
    }

    private fun load(): Boolean {
        val rom = complete(HciCodec.OP_RTK_READ_ROM_VERSION, HciCodec.command(HciCodec.OP_RTK_READ_ROM_VERSION), 1000)
        if (rom == null || rom.size < 2 || rom[0].toInt() != 0) return fail("rom-version", "no answer")
        val romVersion = rom[1].toInt() and 0xFF
        log(String.format(Locale.US, "HCI: rom version=0x%02x", romVersion))

        val files = firmware() ?: return fail("firmware-file", "files not found")
        log("HCI: firmware dir=${files.dir} fw=${files.fw.size} config=${files.config?.size ?: 0}")
        val patch = when (val r = RealtekFirmware.parse(files.fw, files.config, romVersion)) {
            is RealtekFirmware.Reject -> return fail("firmware-parse", r.reason)
            is RealtekFirmware.Patch -> r
        }
        log(String.format(Locale.US, "HCI: firmware patch chip=%d fragments=%d version=0x%08x",
            patch.chipId, patch.fragments, patch.version))

        val started = clock()
        for (i in 0 until patch.fragments) {
            var index = i and 0x7F
            if (i == patch.fragments - 1) index = index or 0x80
            val from = i * HciCodec.RTK_FRAGMENT
            val fragment = patch.payload.copyOfRange(from, minOf(from + HciCodec.RTK_FRAGMENT, patch.payload.size))
            val ret = complete(HciCodec.OP_RTK_DOWNLOAD, HciCodec.rtkDownload(index, fragment), 1000)
                ?: return fail("firmware-download", "no answer at fragment $i")
            val status = if (ret.isEmpty()) -1 else ret[0].toInt() and 0xFF
            if (status != 0) {
                return fail("firmware-download", String.format(Locale.US, "status 0x%02x at fragment %d", status, i))
            }
        }
        log("HCI: firmware loaded fragments=${patch.fragments} ms=${clock() - started}")

        if (!reset("firmware-reset", 5000)) return false
        val after = version("firmware-verify") ?: return false
        val expected = (patch.version and 0xFFFF).toInt()
        if (after.lmpSubversion != expected) {
            return fail("firmware-verify", String.format(Locale.US, "subver=0x%04x expected 0x%04x", after.lmpSubversion, expected))
        }
        return true
    }

    private fun page(address: ByteArray): Boolean {
        val cmd = HciCodec.createConnection(address)
        if (link.send(cmd) != cmd.size) return fail("page", "send failed")
        log("HCI: page sent")
        val started = clock()
        val deadline = started + 20_000
        while (clock() < deadline) {
            val e = link.read(1000) ?: continue
            HciCodec.commandStatus(e)?.let { (op, st) ->
                if (op == HciCodec.OP_CREATE_CONNECTION && st != 0) return answer(st, started)
            }
            HciCodec.connectionComplete(e)?.let { (st, handle) ->
                if (st == 0) {
                    link.send(HciCodec.disconnect(handle))
                    link.read(3000)
                }
                return answer(st, started)
            }
        }
        return fail("page", "timeout")
    }

    private fun answer(status: Int, started: Long): Boolean {
        val ms = clock() - started
        log(if (status == 0) "HCI: page ok ms=$ms" else String.format(Locale.US, "HCI: page 0x%02x ms=%d", status, ms))
        return true
    }

    private fun reset(step: String, timeoutMs: Int): Boolean {
        val ret = complete(HciCodec.OP_RESET, HciCodec.command(HciCodec.OP_RESET), timeoutMs)
        if (ret == null) return fail(step, "no answer")
        if (ret.isEmpty() || ret[0].toInt() != 0) return fail(step, "bad status")
        log("HCI: reset ok")
        return true
    }

    /** Reads the version, prints the three lines for it, and returns it. */
    private fun version(step: String): HciCodec.LocalVersion? {
        val v = complete(HciCodec.OP_READ_LOCAL_VERSION, HciCodec.command(HciCodec.OP_READ_LOCAL_VERSION), 1000)
            ?.let { HciCodec.localVersion(it) }
        if (v == null || v.status != 0) {
            fail(step, "no answer")
            return null
        }
        log(String.format(Locale.US, "HCI: version %d/%d manufacturer=0x%04x", v.hciVersion, v.lmpVersion, v.manufacturer))
        val need = if (RealtekFirmware.stateOf(v) == RealtekFirmware.ChipState.ROM_8761B) "needed" else "none"
        log(String.format(Locale.US, "HCI: state subver=0x%04x hcirev=0x%04x load=%s", v.lmpSubversion, v.hciRevision, need))
        return v
    }

    /** Return parameters of the Command Complete for [opcode], or null on a send failure or timeout. */
    private fun complete(opcode: Int, cmd: ByteArray, timeoutMs: Int): ByteArray? {
        if (link.send(cmd) != cmd.size) return null
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            val e = link.read((deadline - clock()).toInt().coerceAtLeast(1)) ?: continue
            HciCodec.commandComplete(e)?.let { (op, ret) -> if (op == opcode) return ret }
        }
        return null
    }

    private fun fail(step: String, reason: String): Boolean {
        log("HCI: failed step=$step reason=$reason")
        return false
    }

    companion object {
        /**
         * Prints the start line first, then checks the extras. A bad value ends the run here.
         * [page] and [notice] come straight from the intent.
         */
        fun begin(
            page: String?, drop: Boolean, noload: Boolean, notice: String?, sdk: Int, log: (String) -> Unit,
        ): Request? {
            val pageText = page?.trim()?.takeIf { it.isNotEmpty() }
            val noticeShown = if (notice == "raise" || notice == "clear") notice else "none"
            log("HCI: start page=${if (pageText == null) "none" else "set"} drop=$drop noload=$noload " +
                "notice=$noticeShown sdk=$sdk")
            val address = pageText?.let { HciCodec.parseAddress(it) }
            val problem = when {
                pageText != null && address == null -> "page is not a Bluetooth address"
                notice != null && noticeShown == "none" -> "notice must be raise or clear"
                else -> null
            }
            if (problem != null) {
                log("HCI: failed step=input reason=$problem")
                log("HCI: end result=failed claim=none")
                return null
            }
            return Request(address, drop, noload, notice)
        }
    }
}
