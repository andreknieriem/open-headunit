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
 * Ported to Kotlin from bumble/drivers/rtk.py (Bumble 0.0.236); RTL8761B only.
 */
package com.andrerinas.openheadunit.connection.usb.hci

/** Reads a Realtek epatch file and picks the patch for one ROM version. Never throws. */
object RealtekFirmware {

    const val PROJECT_ID_8761B = 14

    /** Most fragments the download index can number before it wraps. */
    const val MAX_FRAGMENTS = 128

    private const val HEADER = 14
    private val SIGNATURE = "Realtech".toByteArray(Charsets.US_ASCII)
    private val EXTENSION = byteArrayOf(0x51, 0x04, 0xFD.toByte(), 0x77)

    enum class ChipState { ROM_8761B, NO_LOAD }

    sealed class Result
    class Patch(val chipId: Int, val payload: ByteArray, val version: Long, val fragments: Int) : Result()
    class Reject(val reason: String) : Result()

    /** The 8761BU ROM row: a chip that reads this needs a load, anything else does not. */
    fun stateOf(v: HciCodec.LocalVersion): ChipState =
        if (v.lmpSubversion == 0x8761 && v.hciRevision == 0x000B && v.hciVersion == 0x0A) ChipState.ROM_8761B
        else ChipState.NO_LOAD

    fun parse(fw: ByteArray, config: ByteArray?, romVersion: Int): Result {
        if (fw.size < HEADER) return Reject("file too short")
        if (!startsWith(fw, SIGNATURE)) return Reject("bad signature")
        if (!endsWith(fw, EXTENSION)) return Reject("bad end marker")
        val projectId = projectId(fw) ?: return Reject("project id not found")
        if (projectId != PROJECT_ID_8761B) return Reject("project id $projectId is not 14")

        val version = u32(fw, 8)
        val count = u16(fw, 12)
        if (HEADER + 8 * count > fw.size) return Reject("patch table too short")
        for (i in 0 until count) {
            val chipId = u16(fw, HEADER + 2 * i)
            if (chipId != romVersion + 1) continue
            val length = u16(fw, HEADER + 2 * count + 2 * i)
            val offset = u32(fw, HEADER + 4 * count + 4 * i)
            if (length < 4 || offset + length > fw.size) return Reject("patch runs off the file")
            val start = offset.toInt()
            // The last four bytes of the patch are replaced by the file version.
            var payload = fw.copyOfRange(start, start + length - 4) + le32(version)
            if (config != null) payload += config
            val fragments = (payload.size + HciCodec.RTK_FRAGMENT - 1) / HciCodec.RTK_FRAGMENT
            if (fragments > MAX_FRAGMENTS) return Reject("$fragments fragments is over $MAX_FRAGMENTS")
            return Patch(chipId, payload, version, fragments)
        }
        return Reject("no patch for rom version $romVersion")
    }

    /** Walks the trailer back from the end marker, as the reference loader does. */
    private fun projectId(fw: ByteArray): Int? {
        var offset = fw.size - EXTENSION.size
        while (offset >= HEADER) {
            val length = fw[offset - 2].toInt() and 0xFF
            val opcode = fw[offset - 1].toInt() and 0xFF
            offset -= 2
            if (opcode == 0xFF) return null
            if (length == 0) return null
            if (opcode == 0 && length == 1) return fw[offset - 1].toInt() and 0xFF
            offset -= length
        }
        return null
    }

    private fun startsWith(a: ByteArray, p: ByteArray) = a.size >= p.size && p.indices.all { a[it] == p[it] }
    private fun endsWith(a: ByteArray, p: ByteArray) =
        a.size >= p.size && p.indices.all { a[a.size - p.size + it] == p[it] }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)
    private fun le32(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }
}
