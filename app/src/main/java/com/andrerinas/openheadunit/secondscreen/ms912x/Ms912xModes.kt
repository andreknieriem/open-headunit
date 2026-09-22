package com.andrerinas.openheadunit.secondscreen.ms912x

/** The pixel layout sent over the adapter's bulk endpoint. [code] is the chip's colorspace id. */
enum class Ms912xWireFormat(val code: Int, val bytesPerPixel: Int) {
    /** UYVY, two bytes a pixel, what the vendor's own app sends. */
    YUV422(2, 2),

    /** Three bytes a pixel, which the chip reads in BGR order. */
    RGB888(1, 3),
}

/**
 * The output modes the chip's timing table knows, with their HDMI VIC. The chip does not scale,
 * so the frame sent is exactly this size.
 */
enum class Ms912xMode(val width: Int, val height: Int, val vic: Int) {
    MODE_640x480(640, 480, 64),
    MODE_720x480(720, 480, 2),
    MODE_720x576(720, 576, 17),
    MODE_800x600(800, 600, 66),
    MODE_1024x768(1024, 768, 71),
    MODE_1280x720(1280, 720, 79),
    MODE_1280x768(1280, 768, 84),
    MODE_1366x768(1366, 768, 102),
    MODE_1680x1050(1680, 1050, 120),
    MODE_1920x1080(1920, 1080, 129),
}

object Ms912xModes {

    val DEFAULT_MODE = Ms912xMode.MODE_1280x720
    val DEFAULT_FORMAT = Ms912xWireFormat.YUV422

    fun modeOf(stored: String?): Ms912xMode = Ms912xMode.values().firstOrNull { it.name == stored } ?: DEFAULT_MODE

    fun formatOf(stored: String?): Ms912xWireFormat =
        Ms912xWireFormat.values().firstOrNull { it.name == stored } ?: DEFAULT_FORMAT

    /**
     * The width every row is written at. The chip needs 4-byte aligned rows, so 1366 in RGB888 goes
     * out as 1368; both video-in and video-out must report this width or the picture shears.
     */
    fun transferWidth(mode: Ms912xMode, format: Ms912xWireFormat): Int {
        val w = mode.width
        return if (w * format.bytesPerPixel % 4 != 0) (w + 3) and 3.inv() else w
    }

    fun frameBytes(mode: Ms912xMode, format: Ms912xWireFormat): Int =
        transferWidth(mode, format) * mode.height * format.bytesPerPixel

    /** 1080p in RGB888 does not fit the chip's frame buffer. */
    fun supports(mode: Ms912xMode, format: Ms912xWireFormat): Boolean =
        !(mode == Ms912xMode.MODE_1920x1080 && format == Ms912xWireFormat.RGB888)

    /** The format actually used: the chosen one, or YUV422 where the chosen one does not fit. */
    fun effectiveFormat(mode: Ms912xMode, chosen: Ms912xWireFormat): Ms912xWireFormat =
        if (supports(mode, chosen)) chosen else Ms912xWireFormat.YUV422

    /** Packed (memory colorspace << 4) | wire colorspace, the same value in both nibbles. */
    fun colorIn(format: Ms912xWireFormat): Int = (format.code shl 4) or format.code

    /** A density for Android Auto to lay its UI out at on a panel this tall. */
    fun densityFor(mode: Ms912xMode): Int = when {
        mode.height <= 600 -> 160
        mode.height <= 800 -> 213
        else -> 320
    }
}
