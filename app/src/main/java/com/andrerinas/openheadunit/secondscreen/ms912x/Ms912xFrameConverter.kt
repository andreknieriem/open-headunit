package com.andrerinas.openheadunit.secondscreen.ms912x

/**
 * Turns a decoded YUV 4:2:0 picture into the adapter's wire format, scaled into its mode.
 *
 * Works on plain arrays so it is testable off-device; the column and row maps are cached per
 * geometry, since they are the same for every frame of a session. Not thread-safe.
 */
class Ms912xFrameConverter {

    /** One plane of the decoder's output, copied out of its buffer. */
    class Plane(val data: ByteArray, val rowStride: Int, val pixelStride: Int)

    /** Where the picture lands in the destination: size and top-left offset. */
    data class Fit(val width: Int, val height: Int, val offsetX: Int, val offsetY: Int)

    private var mapKey: List<Int> = emptyList()
    private var xMap = IntArray(0)
    private var yMap = IntArray(0)

    /**
     * Converts the top-left [srcWidth]x[srcHeight] of the source (Android Auto draws there and
     * leaves the margins blank) into [out], whose rows are [dstStride] pixels wide.
     */
    fun convert(
        y: Plane, u: Plane, v: Plane,
        srcWidth: Int, srcHeight: Int,
        format: Ms912xWireFormat,
        out: ByteArray, dstStride: Int, dstWidth: Int, dstHeight: Int,
        stretch: Boolean,
    ) {
        val fit = fit(srcWidth, srcHeight, dstWidth, dstHeight, stretch)
        if (fit.width != dstWidth || fit.height != dstHeight) fillBlack(out, format)
        ensureMaps(srcWidth, srcHeight, fit)
        when (format) {
            Ms912xWireFormat.YUV422 -> toUyvy(y, u, v, out, dstStride, fit)
            Ms912xWireFormat.RGB888 -> toBgr(y, u, v, out, dstStride, fit)
        }
    }

    private fun ensureMaps(srcWidth: Int, srcHeight: Int, fit: Fit) {
        val key = listOf(srcWidth, srcHeight, fit.width, fit.height)
        if (key == mapKey) return
        xMap = IntArray(fit.width) { (it.toLong() * srcWidth / fit.width).toInt().coerceAtMost(srcWidth - 1) }
        yMap = IntArray(fit.height) { (it.toLong() * srcHeight / fit.height).toInt().coerceAtMost(srcHeight - 1) }
        mapKey = key
    }

    private fun toUyvy(y: Plane, u: Plane, v: Plane, out: ByteArray, dstStride: Int, fit: Fit) {
        for (row in 0 until fit.height) {
            val sy = yMap[row]
            val yRow = sy * y.rowStride
            val uRow = (sy shr 1) * u.rowStride
            val vRow = (sy shr 1) * v.rowStride
            var o = ((fit.offsetY + row) * dstStride + fit.offsetX) * 2
            var col = 0
            while (col + 1 < fit.width) {
                val sxa = xMap[col]
                out[o] = u.data[uRow + (sxa shr 1) * u.pixelStride]
                out[o + 1] = y.data[yRow + sxa * y.pixelStride]
                out[o + 2] = v.data[vRow + (sxa shr 1) * v.pixelStride]
                out[o + 3] = y.data[yRow + xMap[col + 1] * y.pixelStride]
                o += 4
                col += 2
            }
        }
    }

    private fun toBgr(y: Plane, u: Plane, v: Plane, out: ByteArray, dstStride: Int, fit: Fit) {
        for (row in 0 until fit.height) {
            val sy = yMap[row]
            val yRow = sy * y.rowStride
            val uRow = (sy shr 1) * u.rowStride
            val vRow = (sy shr 1) * v.rowStride
            var o = ((fit.offsetY + row) * dstStride + fit.offsetX) * 3
            for (col in 0 until fit.width) {
                val sx = xMap[col]
                val yc = (y.data[yRow + sx * y.pixelStride].toInt() and 0xFF) - 16
                val uc = (u.data[uRow + (sx shr 1) * u.pixelStride].toInt() and 0xFF) - 128
                val vc = (v.data[vRow + (sx shr 1) * v.pixelStride].toInt() and 0xFF) - 128
                // BT.601 limited range in 12-bit fixed point.
                out[o] = clamp((4771 * yc + 8264 * uc + 2048) shr 12)
                out[o + 1] = clamp((4771 * yc - 1605 * uc - 3332 * vc + 2048) shr 12)
                out[o + 2] = clamp((4771 * yc + 6538 * vc + 2048) shr 12)
                o += 3
            }
        }
    }

    private fun clamp(value: Int): Byte = value.coerceIn(0, 255).toByte()

    private fun fillBlack(out: ByteArray, format: Ms912xWireFormat) {
        if (format == Ms912xWireFormat.YUV422) {
            var i = 0
            while (i + 3 < out.size) {
                out[i] = 128.toByte(); out[i + 1] = 16; out[i + 2] = 128.toByte(); out[i + 3] = 16
                i += 4
            }
        } else {
            out.fill(0)
        }
    }

    companion object {
        /**
         * Scale-to-fit (or fill when [stretch]), with an even width and horizontal offset: UYVY
         * carries two pixels per chroma pair, so an odd start would split a pair.
         */
        fun fit(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int, stretch: Boolean): Fit {
            var w: Long
            var h: Long
            if (stretch || srcWidth <= 0 || srcHeight <= 0) {
                w = dstWidth.toLong(); h = dstHeight.toLong()
            } else if (dstWidth.toLong() * srcHeight <= dstHeight.toLong() * srcWidth) {
                w = dstWidth.toLong(); h = dstWidth.toLong() * srcHeight / srcWidth
            } else {
                h = dstHeight.toLong(); w = dstHeight.toLong() * srcWidth / srcHeight
            }
            w = (w and 1L.inv()).coerceIn(2L, dstWidth.toLong().coerceAtLeast(2L))
            h = h.coerceIn(1L, dstHeight.toLong().coerceAtLeast(1L))
            val offX = ((dstWidth - w.toInt()) / 2) and 1.inv()
            val offY = (dstHeight - h.toInt()) / 2
            return Fit(w.toInt(), h.toInt(), offX, offY)
        }
    }
}
