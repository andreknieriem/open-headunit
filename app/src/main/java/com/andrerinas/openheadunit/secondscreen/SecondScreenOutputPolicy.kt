package com.andrerinas.openheadunit.secondscreen

/**
 * Where the second Android Auto stream goes, and what size to ask the phone for.
 *
 * Nothing available means nothing is announced, so a missing adapter costs the main session nothing.
 */
object SecondScreenOutputPolicy {

    enum class Output {
        /** A display Android itself sees: HDMI out, USB-C DP, an overlay. */
        ANDROID_DISPLAY,

        /** Raw H.264 over TCP, for ffplay, VLC or a Pi on the same network. */
        NETWORK,

        /** A MacroSilicon MS912x USB-HDMI adapter, decoded and converted here. */
        MS912X,

        /** A device speaking the Open Headunit USB display protocol, which decodes for itself. */
        USB_DISPLAY;

        companion object {
            fun of(stored: String?): Output = values().firstOrNull { it.name == stored } ?: ANDROID_DISPLAY
        }
    }

    /** The picture size a second screen wants, in its own pixels. */
    data class Target(val widthPx: Int, val heightPx: Int, val densityDpi: Int)

    /** What each output could offer right now; null means it is not there. */
    data class Availability(
        val androidDisplay: Target? = null,
        val network: Target? = null,
        val ms912x: Target? = null,
        val usbDisplay: Target? = null,
    )

    fun target(output: Output, available: Availability): Target? = when (output) {
        Output.ANDROID_DISPLAY -> available.androidDisplay
        Output.NETWORK -> available.network
        Output.MS912X -> available.ms912x
        Output.USB_DISPLAY -> available.usbDisplay
    }

    /** Whether the head unit decodes this output's picture, or only forwards the stream. */
    fun decodesOnHeadUnit(output: Output): Boolean =
        output == Output.ANDROID_DISPLAY || output == Output.MS912X

    /** The sizes a network receiver can be asked for, all standard Android Auto resolutions. */
    val NETWORK_SIZES: List<Target> = listOf(
        Target(800, 480, 160),
        Target(1280, 720, 213),
        Target(1920, 1080, 320),
    )

    fun networkTarget(index: Int): Target = NETWORK_SIZES.getOrElse(index) { NETWORK_SIZES.first() }
}
