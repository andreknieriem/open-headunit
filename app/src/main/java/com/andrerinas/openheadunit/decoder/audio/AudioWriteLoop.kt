package com.andrerinas.openheadunit.decoder.audio

/** Retain the unwritten tail across partial writes and temporary backpressure. Units are the
 * caller's (bytes for PCM wrappers, shorts for the mixer). No allocation on the audio thread. */
internal object AudioWriteLoop {
    inline fun writeFully(
        size: Int,
        isRunning: () -> Boolean,
        write: (offset: Int, remaining: Int) -> Int,
        onProgress: (written: Int) -> Unit,
        awaitWritable: () -> Unit
    ): Int {
        var offset = 0
        while (offset < size && isRunning()) {
            val written = write(offset, size - offset)
            if (written < 0) return written
            if (written == 0) {
                awaitWritable()
            } else {
                offset += written
                onProgress(written)
            }
        }
        return offset
    }
}
