package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.BinaryPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue.Companion.dict

/**
 * The reply to GET /info, built key by key as LIVI builds it. Two flags give LIVI's bytes when set:
 * [Config.offerMainHighMedia] adds the type 102 `media` AAC entry, [Config.offerOpus] adds the Opus bits.
 * Follows LIVI (https://github.com/f-io/LIVI), crate livi-cp, `info.rs` `build`, with xcertplay airplay/AirPlayInfoPlist.kt.
 */
object InfoPlist {
    data class Insets(val top: Long = 0, val bottom: Long = 0, val left: Long = 0, val right: Long = 0) {
        fun within(outer: Insets) = Insets(
            maxOf(top, outer.top), maxOf(bottom, outer.bottom), maxOf(left, outer.left), maxOf(right, outer.right),
        )
    }

    class Display(
        val widthPixels: Long,
        val heightPixels: Long,
        val widthPhysicalMm: Long? = null,
        val fps: Long? = null,
        val viewArea: Insets? = null,
        val safeArea: Insets? = null,
        val safeAreaDrawOutside: Boolean? = null,
        val initialUrl: String? = null,
        val uiNight: Boolean = false,
        val mapNight: Boolean = false,
    )

    class Icon(val widthPixels: Long, val heightPixels: Long, val png: ByteArray)

    /** [model] and [manufacturer] also name the HID devices. Production passes the app name, the tests pass LIVI's. */
    class Config(
        val deviceName: String,
        val deviceId: String,
        val btMac: String,
        val sourceVersion: String,
        val model: String,
        val manufacturer: String,
        val hevc: Boolean,
        val main: Display,
        val cluster: Display? = null,
        val entertainmentSampleRate: Int,
        val disableAudioOutput: Boolean,
        val oemLabel: String,
        val icons: List<Icon> = emptyList(),
        val rightHandDrive: Boolean,
        val appearanceSetting: Long,
        val offerMainHighMedia: Boolean,
        /** Stage 2 plays PCM only, so production passes false. */
        val offerOpus: Boolean,
    )

    const val MAIN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"
    const val ALT_UUID = "b7e6c5a0-2222-4000-8000-000000000002"
    const val STREAM_TYPE_MAIN_SCREEN = 110L
    const val STREAM_TYPE_ALT_SCREEN = 111L

    private const val DISPLAY_FEATURE_KNOBS = 0x02L
    private const val DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH = 0x08L
    private const val PRIMARY_INPUT_KNOBS = 3L
    private const val RESOURCE_SCREEN = 1L
    private const val RESOURCE_AUDIO = 2L
    private const val TRANSFER_TAKE = 1L
    private const val PRIORITY_NICE_TO_HAVE = 100L
    private const val CONSTRAINT_ANYTIME = 100L
    private const val FALLBACK_DPI = 200.0

    private const val SCREEN = 1L shl 7
    private const val SCREEN_ROTATE = 1L shl 8
    private const val AUDIO = 1L shl 9
    private const val AUDIO_REDUNDANT = 1L shl 11
    private const val AUDIO_FORMAT_1 = 1L shl 18
    private const val AUDIO_FORMAT_3 = 1L shl 20
    private const val AUDIO_UNENCRYPTED = 1L shl 22
    private const val AUDIO_AES_MFI = 1L shl 26
    private const val RAOP = 1L shl 30
    private const val CARPLAY = 1L shl 32
    private const val CARPLAY_CONTROL = 1L shl 37
    private const val COREUTILS_PAIRING = 1L shl 38
    private const val BUFFERED_AUDIO = 1L shl 40

    const val FEATURES = SCREEN or SCREEN_ROTATE or AUDIO or AUDIO_FORMAT_1 or AUDIO_FORMAT_3 or
        AUDIO_UNENCRYPTED or AUDIO_AES_MFI or RAOP or CARPLAY or CARPLAY_CONTROL or COREUTILS_PAIRING
    const val AUDIO_FEATURES = AUDIO or AUDIO_REDUNDANT or AUDIO_FORMAT_1 or AUDIO_FORMAT_3 or
        AUDIO_UNENCRYPTED or AUDIO_AES_MFI or BUFFERED_AUDIO

    fun encode(config: Config): ByteArray = BinaryPlist.encode(build(config))

    fun build(config: Config): PlistValue.Dict {
        val displays = mutableListOf(display(config.main, STREAM_TYPE_MAIN_SCREEN, MAIN_UUID, config.appearanceSetting))
        config.cluster?.let { displays += display(it, STREAM_TYPE_ALT_SCREEN, ALT_UUID, config.appearanceSetting) }
        val features = if (config.disableAudioOutput) FEATURES and AUDIO_FEATURES.inv() else FEATURES

        val info = mutableListOf<Pair<String, PlistValue>>(
            "sourceVersion" to text(config.sourceVersion),
            "features" to int(features),
            "statusFlags" to int(4),
            "model" to text(config.model),
            "manufacturer" to text(config.manufacturer),
            "deviceID" to text(config.deviceId),
            "bluetoothIDs" to PlistValue.Items(listOf(text(config.btMac))),
            "name" to text(config.deviceName),
            "nightMode" to PlistValue.Bool(config.main.mapNight),
            "rightHandDrive" to PlistValue.Bool(config.rightHandDrive),
            "keepAliveLowPower" to PlistValue.Bool(true),
            "keepAliveSendStatsAsBody" to PlistValue.Bool(false),
            "modes" to modes(),
        )
        if (!config.disableAudioOutput) {
            info += "audioLatencies" to audioLatencies()
            info += "audioFormats" to audioFormats(config.entertainmentSampleRate, config.offerMainHighMedia, config.offerOpus)
        }
        info += "extendedFeatures" to PlistValue.Items(listOf(text("vocoderInfo"), text("enhancedRequestCarUI")))
        info += "displays" to PlistValue.Items(displays)
        val x = config.main.widthPixels and 0xffff
        val y = config.main.heightPixels and 0xffff
        info += "hidDevices" to PlistValue.Items(
            listOf(
                HidDevices.touch(x.toInt(), y.toInt(), MAIN_UUID, "${config.model} Touchscreen"),
                HidDevices.knob(MAIN_UUID, "${config.model} Knob"),
                HidDevices.media(MAIN_UUID, "${config.model} Media"),
                HidDevices.telephony(MAIN_UUID, "${config.model} Telephony"),
            ),
        )
        if (config.icons.isNotEmpty()) {
            info += "oemIconVisible" to PlistValue.Bool(true)
            info += "oemIconLabel" to text(config.oemLabel)
            info += "oemIcons" to PlistValue.Items(
                config.icons.map {
                    dict(
                        "imageData" to PlistValue.Data(it.png),
                        "widthPixels" to int(it.widthPixels),
                        "heightPixels" to int(it.heightPixels),
                        "prerendered" to PlistValue.Bool(true),
                    )
                },
            )
        }
        if (config.hevc) info += "hevcInfo" to PlistValue.Dict(emptyList())
        return PlistValue.Dict(info)
    }

    private fun text(value: String) = PlistValue.Text(value)
    private fun int(value: Long) = PlistValue.Integer(value)

    /** A negative number goes out as a real, as LIVI does. */
    private fun num(value: Long): PlistValue = if (value >= 0) int(value) else PlistValue.Real(value.toDouble())

    private fun resource(id: Long) = dict(
        "resourceID" to int(id),
        "transferType" to int(TRANSFER_TAKE),
        "transferPriority" to int(PRIORITY_NICE_TO_HAVE),
        "takeConstraint" to int(CONSTRAINT_ANYTIME),
        "borrowConstraint" to int(CONSTRAINT_ANYTIME),
        "unborrowConstraint" to int(CONSTRAINT_ANYTIME),
    )

    private fun modes() = dict(
        "resources" to PlistValue.Items(listOf(resource(RESOURCE_SCREEN), resource(RESOURCE_AUDIO))),
        "appStates" to PlistValue.Items(
            listOf(
                dict("appStateID" to int(2), "state" to PlistValue.Bool(false)),
                dict("appStateID" to int(1), "speechMode" to num(-1)),
                dict("appStateID" to int(3), "state" to PlistValue.Bool(false)),
            ),
        ),
    )

    private fun audioLatencies(): PlistValue {
        fun entry(kind: Long, audioType: String?): PlistValue {
            val fields = mutableListOf<Pair<String, PlistValue>>(
                "type" to int(kind), "inputLatencyMicros" to int(0), "outputLatencyMicros" to int(0),
            )
            if (audioType != null) fields += "audioType" to text(audioType)
            return PlistValue.Dict(fields)
        }
        return PlistValue.Items(
            listOf(
                entry(100, null), entry(100, "default"), entry(100, "media"), entry(100, "telephony"),
                entry(100, "speechRecognition"), entry(100, "alert"), entry(101, null), entry(101, "default"),
                entry(102, "default"),
            ),
        )
    }

    private fun audioFormats(entertainmentRate: Int, offerMainHighMedia: Boolean, offerOpus: Boolean): PlistValue {
        fun entry(kind: Long, audioType: String, output: Long, input: Long?): PlistValue {
            val fields = mutableListOf<Pair<String, PlistValue>>(
                "type" to int(kind), "audioType" to text(audioType), "audioOutputFormats" to int(output),
            )
            if (input != null) fields += "audioInputFormats" to int(input)
            return PlistValue.Dict(fields)
        }
        val is48 = entertainmentRate == 48000
        val pcmVoice = 0x3fcL
        val pcm = pcmVoice or (if (is48) 0xc000L else 0xc00L)
        val pcmMono = 0x154L or (if (is48) 0x4000L else 0x400L)
        val pcmMedia = if (is48) 0x8000L else 0x800L
        val opus = if (offerOpus) 0x7000_0000L else 0L
        val aacLc = if (is48) 0x80_0000L else 0x40_0000L
        val formats = mutableListOf(
            entry(100, "compatibility", pcm, pcmMono),
            entry(101, "compatibility", pcm, null),
            entry(100, "default", pcm or opus, pcmMono or opus),
            entry(100, "alert", pcm or opus, null),
            entry(100, "media", pcmMedia, null),
            entry(100, "telephony", pcmMono or opus, pcmMono or opus),
            entry(100, "speechRecognition", pcmMono or opus, pcmMono or opus),
            entry(101, "default", pcm or opus, null),
        )
        if (offerMainHighMedia) formats += entry(102, "media", aacLc, null)
        return PlistValue.Items(formats)
    }

    private fun area(d: Display): PlistValue? {
        val va = d.viewArea ?: return null
        val w = d.widthPixels
        val h = d.heightPixels
        val view = mutableListOf<Pair<String, PlistValue>>(
            "widthPixels" to num(w - va.left - va.right),
            "heightPixels" to num(h - va.top - va.bottom),
            "originXPixels" to num(va.left),
            "originYPixels" to num(va.top),
        )
        d.safeArea?.within(va)?.let { sa ->
            val safe = mutableListOf<Pair<String, PlistValue>>(
                "widthPixels" to num(w - sa.left - sa.right),
                "heightPixels" to num(h - sa.top - sa.bottom),
                "originXPixels" to num(sa.left),
                "originYPixels" to num(sa.top),
            )
            d.safeAreaDrawOutside?.let { safe += "drawUIOutsideSafeArea" to PlistValue.Bool(it) }
            view += "safeArea" to PlistValue.Dict(safe)
        }
        view += "viewAreaTransitionControl" to PlistValue.Bool(false)
        view += "viewAreaSupportsFocusTransfer" to PlistValue.Bool(false)
        return PlistValue.Dict(view)
    }

    /** Millimetres at the fallback dpi, never below 1. */
    internal fun mmAtDpi(pixels: Long, dpi: Double): Long = maxOf(roundHalfUp(pixels * 25.4 / dpi).toLong(), 1)

    private fun roundHalfUp(x: Double): Double {
        val floor = Math.floor(x)
        return if (x - floor >= 0.5) floor + 1.0 else floor
    }

    private fun display(d: Display, kind: Long, uuid: String, setting: Long): PlistValue {
        val widthPhysical = d.widthPhysicalMm ?: mmAtDpi(d.widthPixels, FALLBACK_DPI)
        val heightPhysical = maxOf(
            roundHalfUp(widthPhysical.toDouble() * d.heightPixels.toDouble() / maxOf(d.widthPixels, 1L).toDouble()).toLong(),
            1,
        )
        val entry = mutableListOf<Pair<String, PlistValue>>(
            "uuid" to text(uuid),
            "type" to int(kind),
            "maxFPS" to num(d.fps ?: 60),
            "widthPixels" to num(d.widthPixels),
            "heightPixels" to num(d.heightPixels),
            "widthPhysical" to num(widthPhysical),
            "heightPhysical" to num(heightPhysical),
            "features" to int(DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH or DISPLAY_FEATURE_KNOBS),
            "primaryInputDevice" to num(PRIMARY_INPUT_KNOBS),
            "cornerMasks" to PlistValue.Bool(false),
            "adjacentViewAreas" to PlistValue.Items(emptyList()),
            "uiAppearanceMode" to int(if (d.uiNight) 1 else 0),
            "uiAppearanceSetting" to int(setting),
            "mapAppearanceMode" to int(if (d.mapNight) 1 else 0),
            "mapAppearanceSetting" to int(setting),
        )
        area(d)?.let {
            entry += "viewAreas" to PlistValue.Items(listOf(it))
            entry += "initialViewArea" to int(0)
        }
        d.initialUrl?.takeIf { it.isNotEmpty() }?.let { entry += "initialURL" to text(it) }
        return PlistValue.Dict(entry)
    }
}
