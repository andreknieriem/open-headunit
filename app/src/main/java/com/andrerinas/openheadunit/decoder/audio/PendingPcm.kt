package com.andrerinas.openheadunit.decoder.audio

/** Cold recovery storage. A replacement receives this prefix before the caller's unwritten tail. */
internal class PendingPcm {
    private var data = ShortArray(0)
    private var offset = 0
    val isEmpty: Boolean get() = offset == data.size

    fun prepend(prefix: ShortArray) {
        if (prefix.isEmpty()) return
        data = prefix + data.copyOfRange(offset, data.size)
        offset = 0
    }

    fun writeTo(output: PcmOutput): Int {
        if (isEmpty) return 0
        val written = output.write(data, offset, minOf(960, data.size - offset))
        if (written > 0) {
            offset += written
            if (isEmpty) { data = ShortArray(0); offset = 0 }
        }
        return written
    }

    fun take(): ShortArray = data.copyOfRange(offset, data.size).also {
        data = ShortArray(0)
        offset = 0
    }
}
