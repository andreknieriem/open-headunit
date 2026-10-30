package com.andrerinas.openheadunit.connection.esp32

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE contract shared with the project 5 firmware.
 * Esp32Relay relays RFCOMM bytes. This display owns Android Auto framing.
 */
object Esp32RelayProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("8e6d1000-3f45-4f1e-a000-6e2b8d012345")
    val COMMAND_UUID: UUID = UUID.fromString("8e6d1001-3f45-4f1e-a000-6e2b8d012345")
    val EVENT_UUID: UUID = UUID.fromString("8e6d1002-3f45-4f1e-a000-6e2b8d012345")
    val DATA_RX_UUID: UUID = UUID.fromString("8e6d1003-3f45-4f1e-a000-6e2b8d012345")
    val DATA_TX_UUID: UUID = UUID.fromString("8e6d1004-3f45-4f1e-a000-6e2b8d012345")

    const val PROJECTION_PORT = 5288
    /** Classic Bluetooth name advertised to the phone. */
    const val CLASSIC_NAME = "OpenHU-BT-Relay"
    /** BLE advertising name for head-unit scan / setup. */
    const val BLE_NAME_PREFIX = "OpenHU-BT-Relay-Setup"
    const val VERSION: Int = 0x01

    /** True for BLE ads from this relay. */
    fun isEsp32RelayBleName(name: String?): Boolean {
        val label = name?.trim().orEmpty()
        if (label.isEmpty()) return false
        return label.startsWith(BLE_NAME_PREFIX, ignoreCase = true)
    }

    /** True for the ESP32 relay — never a driver phone. Setup BLE names share the Classic prefix. */
    fun isEsp32RelayDeviceName(name: String?): Boolean {
        val label = name?.trim().orEmpty()
        if (label.isEmpty()) return false
        return label.startsWith(CLASSIC_NAME, ignoreCase = true)
    }

    const val HEADER_LEN = 12
    const val MAX_PAYLOAD = 4096

    const val FLAG_START = 0x01
    const val FLAG_END = 0x02
    const val CH_CONTROL = 0
    const val CH_RFCOMM = 1

    const val CMD_HELLO = 0x01
    const val CMD_SET_NETWORK = 0x02
    const val CMD_AA_ENABLE = 0x03
    const val CMD_AA_DISABLE = 0x04
    const val CMD_GET_STATUS = 0x05
    const val CMD_SET_BOND_MODE = 0x06
    const val CMD_PROJECTION_UP = 0x07
    /** Explicit HFP SLC on. After this is used, AA_ENABLE must not start HFP. */
    const val CMD_HFP_ON = 0x08
    /** Explicit HFP SLC off. Projection-up uses this; PROJECTION_UP stays status-only. */
    const val CMD_HFP_OFF = 0x09

    /** Close the Classic pairing window. */
    const val BOND_MODE_OFF = 0
    /** Open discoverable without clearing existing phone bonds. */
    const val BOND_MODE_ON = 1
    /** Drop phone bonds and open pairing for a new smartphone. */
    const val BOND_MODE_NEW_PHONE = 2

    const val EVT_HELLO_ACK = 0x20
    const val EVT_BT_READY = 0x21
    const val EVT_PHONE_LINK = 0x22
    const val EVT_HFP_STATE = 0x23
    const val EVT_RFCOMM_OPEN = 0x24
    const val EVT_RFCOMM_CLOSE = 0x25
    const val EVT_ERROR = 0x26
    const val EVT_STATUS = 0x27
    const val DATA_RFCOMM_TX = 0x40
    const val DATA_RFCOMM_RX = 0x41
    const val EVT_CREDIT = 0x42

    data class DisplayInfo(
        val ipAddress: String,
        val port: Int,
        val ssid: String,
        val key: String,
        val bssid: String,
        val securityMode: String = "WPA2_PERSONAL",
        val generation: Long = 1,
    ) {
        val isComplete: Boolean
            get() = ipAddress.isNotBlank() && ssid.isNotBlank() && key.isNotBlank() && bssid.isNotBlank()
    }

    /** Empty Wi-Fi snapshot used for BLE keep-alive / early HFP wake before the group exists. */
    fun emptyDisplayInfo(): DisplayInfo = DisplayInfo(
        ipAddress = "",
        port = PROJECTION_PORT,
        ssid = "",
        key = "",
        bssid = "",
    )

    data class Frame(
        val type: Int,
        val flags: Int,
        val channel: Int,
        val seq: Int,
        val payload: ByteArray,
    )

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun encode(type: Int, channel: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD)
        val out = ByteArray(HEADER_LEN + payload.size)
        out[0] = 0xA5.toByte()
        out[1] = 0x5A.toByte()
        out[2] = VERSION.toByte()
        out[3] = type.toByte()
        out[4] = (FLAG_START or FLAG_END).toByte()
        out[5] = channel.toByte()
        out[6] = (seq and 0xFF).toByte()
        out[7] = (seq shr 8 and 0xFF).toByte()
        out[8] = (payload.size and 0xFF).toByte()
        out[9] = (payload.size shr 8 and 0xFF).toByte()
        if (payload.isNotEmpty()) payload.copyInto(out, HEADER_LEN)
        val crc = crc16(out, 0, 10).let { head ->
            if (payload.isEmpty()) head else continueCrc(head, payload)
        }
        out[10] = (crc and 0xFF).toByte()
        out[11] = (crc shr 8 and 0xFF).toByte()
        return out
    }

    fun decode(frame: ByteArray): Frame {
        require(frame.size >= HEADER_LEN)
        require(frame[0] == 0xA5.toByte() && frame[1] == 0x5A.toByte())
        require(frame[2] == VERSION.toByte())
        val len = (frame[8].toInt() and 0xFF) or (frame[9].toInt() and 0xFF shl 8)
        require(frame.size == HEADER_LEN + len)
        var crc = crc16(frame, 0, 10)
        if (len > 0) crc = continueCrc(crc, frame.copyOfRange(HEADER_LEN, frame.size))
        val got = (frame[10].toInt() and 0xFF) or (frame[11].toInt() and 0xFF shl 8)
        require(crc == got)
        return Frame(
            type = frame[3].toInt() and 0xFF,
            flags = frame[4].toInt() and 0xFF,
            channel = frame[5].toInt() and 0xFF,
            seq = (frame[6].toInt() and 0xFF) or (frame[7].toInt() and 0xFF shl 8),
            payload = frame.copyOfRange(HEADER_LEN, frame.size),
        )
    }

    /** Schema 0x01 network snapshot. The passphrase stays inside this byte array. */
    fun networkPayload(info: DisplayInfo): ByteArray {
        val ssid = info.ssid.toByteArray(Charsets.UTF_8)
        val pass = info.key.toByteArray(Charsets.UTF_8)
        val security = info.securityMode.toByteArray(Charsets.UTF_8)
        val ip = info.ipAddress.toByteArray(Charsets.UTF_8)
        val bssid = parseBssid(info.bssid)
        require(ssid.size <= 255 && pass.size <= 255 && security.size <= 255 && ip.size <= 255)
        val size = 1 + 4 + 2 + 1 + ssid.size + 1 + pass.size + 6 + 1 + security.size + 1 + ip.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(1)
        buf.putInt(info.generation.toInt())
        buf.putShort(info.port.toShort())
        buf.put(ssid.size.toByte())
        buf.put(ssid)
        buf.put(pass.size.toByte())
        buf.put(pass)
        buf.put(bssid)
        buf.put(security.size.toByte())
        buf.put(security)
        buf.put(ip.size.toByte())
        buf.put(ip)
        return buf.array()
    }

    fun bondModePayload(mode: Int, seconds: Int = 120): ByteArray {
        return byteArrayOf(
            mode.coerceIn(BOND_MODE_OFF, BOND_MODE_NEW_PHONE).toByte(),
            (seconds and 0xFF).toByte(),
            (seconds shr 8 and 0xFF).toByte(),
        )
    }

    fun creditPayload(extra: Int): ByteArray = byteArrayOf(extra.coerceIn(0, 8).toByte())

    /**
     * Split one logical frame into BLE writes.
     * Each piece is transport_seq, frag_index, frag_count, then a slice.
     */
    fun fragment(frame: ByteArray, attPayload: Int, transportSeq: Int): List<ByteArray> {
        val room = (attPayload - 4).coerceAtLeast(8)
        val count = (frame.size + room - 1) / room
        return (0 until count).map { index ->
            val off = index * room
            val n = minOf(room, frame.size - off)
            ByteArray(4 + n).also { piece ->
                piece[0] = (transportSeq and 0xFF).toByte()
                piece[1] = (transportSeq shr 8 and 0xFF).toByte()
                piece[2] = index.toByte()
                piece[3] = count.toByte()
                frame.copyInto(piece, 4, off, off + n)
            }
        }
    }

    private fun continueCrc(crc: Int, data: ByteArray): Int {
        var running = crc
        for (value in data) {
            running = running xor (value.toInt() and 0xFF shl 8)
            repeat(8) {
                running = if (running and 0x8000 != 0) (running shl 1) xor 0x1021 else running shl 1
                running = running and 0xFFFF
            }
        }
        return running
    }

    private fun parseBssid(text: String): ByteArray {
        val parts = text.split(":")
        if (parts.size != 6) return ByteArray(6)
        return ByteArray(6) { index -> parts[index].toInt(16).toByte() }
    }
}
