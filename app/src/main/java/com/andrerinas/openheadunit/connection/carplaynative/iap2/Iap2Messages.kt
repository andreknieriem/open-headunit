package com.andrerinas.openheadunit.connection.carplaynative.iap2

/**
 * The control messages the wired start sends and reads, and their encodings.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `iap2/message/Iap2Messages.kt`; the byte sizes match its recorded session.
 */
object Iap2Messages {
    const val START_IDENTIFICATION = 0x1D00
    const val IDENTIFICATION_INFORMATION = 0x1D01
    const val IDENTIFICATION_ACCEPTED = 0x1D02
    const val IDENTIFICATION_REJECTED = 0x1D03

    const val REQUEST_CERTIFICATE = 0xAA00
    const val CERTIFICATE = 0xAA01
    const val REQUEST_CHALLENGE = 0xAA02
    const val RESPONSE = 0xAA03
    const val AUTHENTICATION_FAILED = 0xAA04
    const val AUTHENTICATION_SUCCEEDED = 0xAA05

    const val START_NOW_PLAYING = 0x5000
    const val STOP_NOW_PLAYING = 0x5002
    const val START_ROUTE_GUIDANCE = 0x5200
    const val STOP_ROUTE_GUIDANCE = 0x5203
    const val START_POWER = 0xAE00
    const val STOP_POWER = 0xAE02
    const val POWER_SOURCE_UPDATE = 0xAE03
    const val START_COMMUNICATIONS = 0x4157
    const val STOP_COMMUNICATIONS = 0x4159
    const val START_CALL_STATE = 0x4154
    const val STOP_CALL_STATE = 0x4156
    const val START_HID = 0x6800
    const val ACCESSORY_HID_REPORT = 0x6802
    const val STOP_HID = 0x6803
    const val DEVICE_HID_REPORT = 0x6801

    const val CARPLAY_AVAILABILITY = 0x4300
    const val CARPLAY_START_SESSION = 0x4301

    const val START_EA_SESSION = 0xEA00
    const val STOP_EA_SESSION = 0xEA01
    const val NOW_PLAYING_UPDATE = 0x5001
    const val ROUTE_GUIDANCE_UPDATE = 0x5201
    const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
    const val POWER_UPDATE = 0xAE01
    const val COMMUNICATIONS_UPDATE = 0x4158
    const val CALL_STATE_UPDATE = 0x4155

    /** The HID component id of the media playback remote. */
    const val MEDIA_REMOTE_COMPONENT = 0

    private const val HID_VENDOR = 2
    private const val HID_PRODUCT = 1

    /** The usage page the phone reads: play, pause, next and previous as four bits. */
    private val MEDIA_REMOTE_DESCRIPTOR = intArrayOf(
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x04,
        0x09, 0xB0, 0x09, 0xB1, 0x09, 0xB5, 0x09, 0xB6, 0x81, 0x02, 0x75, 0x04, 0x95, 0x01,
        0x81, 0x03, 0xC0,
    ).let { values -> ByteArray(values.size) { values[it].toByte() } }

    fun certificate(certificate: ByteArray): Iap2Frame = Iap2Csm.build(CERTIFICATE) { bytes(0, certificate) }

    fun response(signature: ByteArray): Iap2Frame = Iap2Csm.build(RESPONSE) { bytes(0, signature) }

    /** The challenge parameter of 0xAA02, or null when it is missing. */
    fun challenge(frame: Iap2Frame): ByteArray? = frame.first(0)

    /** What the phone may draw over the cable, and that it may charge. */
    fun powerSourceUpdate(availableMilliAmps: Int): Iap2Frame =
        Iap2Csm.build(POWER_SOURCE_UPDATE) { u16(0, availableMilliAmps); u8(1, 1) }

    fun subscriptions(): List<Iap2Frame> = listOf(
        Iap2Csm.build(START_NOW_PLAYING) {
            group(0) { for (id in intArrayOf(1, 4, 6, 12, 26)) void(id) }
            group(1) { for (id in intArrayOf(0, 1, 2, 3, 7)) void(id) }
        },
        Iap2Csm.build(START_ROUTE_GUIDANCE),
        Iap2Csm.build(START_POWER) { void(4); void(5); void(6) },
        Iap2Csm.build(START_COMMUNICATIONS) { void(0); void(4); void(5) },
        Iap2Csm.build(START_CALL_STATE) { for (id in intArrayOf(0, 1, 2, 3, 4, 11)) void(id) },
    )

    fun startMediaRemote(): Iap2Frame = Iap2Csm.build(START_HID) {
        u16(0, MEDIA_REMOTE_COMPONENT)
        u16(1, HID_VENDOR)
        u16(2, HID_PRODUCT)
        bytes(4, MEDIA_REMOTE_DESCRIPTOR)
    }

    fun stopMediaRemote(): Iap2Frame = Iap2Csm.build(STOP_HID) { u16(0, MEDIA_REMOTE_COMPONENT) }

    /** The wired answer to 0x4300: where the AirPlay server listens and the key the phone checks. */
    class StartSession(
        val address: String,
        val port: Int,
        val deviceIdentifier: String,
        val publicKey: String,
        val sourceVersion: String,
    ) {
        init {
            require(address.contains(':') && !address.contains('%')) { "the address is a scope free IPv6 literal" }
            require(port in 1..0xFFFF) { "port out of range" }
            for (text in listOf(address, deviceIdentifier, publicKey, sourceVersion)) {
                require(text.isNotEmpty() && '\u0000' !in text) { "a start session text is empty or holds a NUL" }
            }
        }

        fun frame(): Iap2Frame = Iap2Csm.build(CARPLAY_START_SESSION) {
            group(0) { string(0, address) }
            u32(2, port.toLong())
            string(3, deviceIdentifier)
            string(4, publicKey)
            string(5, sourceVersion)
        }
    }

    /** Whether the wired side of a 0x4300 says the iPhone offers CarPlay, or null when it does not say. */
    fun wiredAvailable(frame: Iap2Frame): Boolean? {
        val wired = frame.first(0) ?: return null
        val flag = Iap2Csm.parseParams(wired).firstOrNull { it.id == 0 } ?: return null
        return Iap2Csm.readU8(flag.payload) != 0
    }

    /** The message ids a 0x1D03 lists in parameters 6 and 7, and the other parameter ids it flags. */
    class Rejection(val messageIds: Set<Int>, val otherParams: Set<Int>)

    fun rejection(frame: Iap2Frame): Rejection {
        val ids = LinkedHashSet<Int>()
        val other = LinkedHashSet<Int>()
        for (p in frame.params()) {
            if (p.id == 6 || p.id == 7) ids += Iap2Csm.readU16List(p.payload) else other += p.id
        }
        return Rejection(ids, other)
    }

    fun hexId(id: Int): String = "0x" + Integer.toHexString(id).padStart(4, '0')
}
