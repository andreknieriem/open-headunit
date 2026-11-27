package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxMessages
import com.andrerinas.openheadunit.connection.carplaynative.airplay.InfoPlist

/** What this unit tells the phone about itself. */
class CarPlayNativeProduct(
    val name: String,
    val modelIdentifier: String,
    val manufacturer: String,
    val firmwareVersion: String,
    val hardwareVersion: String,
)

/** The /info input of a session. Stage 2 plays media in LPCM only, so it offers no HEVC, no cluster, no AAC on 102 and no Opus. */
object CarPlayNativeInfo {
    const val SOURCE_VERSION = "950.7.1"
    private const val FPS = 60L
    private const val ENTERTAINMENT_SAMPLE_RATE = 48_000

    fun config(product: CarPlayNativeProduct, deviceId: String, displayWidth: Int, displayHeight: Int, densityDpi: Int): InfoPlist.Config {
        val (width, height) = CarPlayBoxMessages.openSize(displayWidth, displayHeight)
        return InfoPlist.Config(
            deviceName = product.name,
            deviceId = deviceId,
            btMac = deviceId,
            sourceVersion = SOURCE_VERSION,
            model = product.modelIdentifier,
            manufacturer = product.manufacturer,
            hevc = false,
            main = InfoPlist.Display(
                widthPixels = width.toLong(), heightPixels = height.toLong(),
                widthPhysicalMm = InfoPlist.mmAtDpi(width.toLong(), densityDpi.coerceAtLeast(1).toDouble()),
                fps = FPS, viewArea = InfoPlist.Insets(),
            ),
            cluster = null,
            entertainmentSampleRate = ENTERTAINMENT_SAMPLE_RATE,
            disableAudioOutput = false,
            oemLabel = product.name,
            rightHandDrive = false,
            appearanceSetting = 1,
            offerMainHighMedia = false,
            offerOpus = false,
        )
    }
}
