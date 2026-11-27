package com.andrerinas.openheadunit.connection.carplaynative.iap2

/**
 * The 0x1D01 identification for the wired start: one USB host transport, the media remote, no location and no vehicle data.
 * The message lists are the ones in xcertplay's recorded iPhone session (https://github.com/shilapi/xcertplay), without the wireless ids.
 */
object Iap2Identification {
    class Config(
        val name: String,
        val modelIdentifier: String,
        val manufacturer: String,
        val serialNumber: String,
        val firmwareVersion: String,
        val hardwareVersion: String,
        /** The interface number of the CDC NCM control interface the phone carries CarPlay on. */
        val carPlayUsbInterface: Int,
    ) {
        init {
            for (text in listOf(name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion)) {
                require(text.isNotEmpty() && '\u0000' !in text) { "identification texts are not empty and hold no NUL" }
            }
            require(carPlayUsbInterface in 0..0xFF) { "interface number must fit in u8" }
        }
    }

    private const val LANGUAGE = "en"
    private const val EA_PROTOCOL = "com.andrerinas.openheadunit"
    private const val POWER_ADVANCED = 2
    private const val MAX_CURRENT_MA = 20

    val SENT: List<Int> = listOf(
        Iap2Messages.START_NOW_PLAYING, Iap2Messages.STOP_NOW_PLAYING, Iap2Messages.START_ROUTE_GUIDANCE, Iap2Messages.STOP_ROUTE_GUIDANCE,
        Iap2Messages.START_POWER, Iap2Messages.STOP_POWER, Iap2Messages.START_COMMUNICATIONS, Iap2Messages.STOP_COMMUNICATIONS,
        Iap2Messages.START_CALL_STATE, Iap2Messages.STOP_CALL_STATE, Iap2Messages.POWER_SOURCE_UPDATE, Iap2Messages.CARPLAY_START_SESSION,
        Iap2Messages.START_HID, Iap2Messages.ACCESSORY_HID_REPORT, Iap2Messages.STOP_HID,
    )

    val RECEIVED: List<Int> = listOf(
        Iap2Messages.START_EA_SESSION, Iap2Messages.STOP_EA_SESSION, Iap2Messages.NOW_PLAYING_UPDATE, Iap2Messages.ROUTE_GUIDANCE_UPDATE,
        Iap2Messages.ROUTE_GUIDANCE_MANEUVER_UPDATE, Iap2Messages.POWER_UPDATE, Iap2Messages.COMMUNICATIONS_UPDATE,
        Iap2Messages.CALL_STATE_UPDATE, Iap2Messages.CARPLAY_AVAILABILITY, Iap2Messages.DEVICE_HID_REPORT,
    )

    /** [omitted] holds message ids a first rejection named. They leave both lists. */
    fun build(config: Config, omitted: Set<Int> = emptySet()): Iap2Frame = Iap2Csm.build(Iap2Messages.IDENTIFICATION_INFORMATION) {
        string(0, config.name)
        string(1, config.modelIdentifier)
        string(2, config.manufacturer)
        string(3, config.serialNumber)
        string(4, config.firmwareVersion)
        string(5, config.hardwareVersion)
        u16List(6, SENT.filter { it !in omitted })
        u16List(7, RECEIVED.filter { it !in omitted })
        u8(8, POWER_ADVANCED)
        u16(9, MAX_CURRENT_MA)
        group(10) {
            u8(0, 1)
            string(1, EA_PROTOCOL)
            u8(2, 0)
        }
        string(12, LANGUAGE)
        string(13, LANGUAGE)
        group(16) {
            u16(0, 0)
            string(1, "USBHostTransport")
            void(2)
            u8(3, config.carPlayUsbInterface)
            void(4)
        }
        group(18) {
            u16(0, Iap2Messages.MEDIA_REMOTE_COMPONENT)
            string(1, "Media Playback Remote")
            u8(2, 1)
        }
    }
}
