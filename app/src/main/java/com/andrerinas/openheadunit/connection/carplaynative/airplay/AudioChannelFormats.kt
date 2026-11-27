package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxAudioFormat

/** The format each audio channel plays now. A stream that plays on a channel with another format reconfigures it. */
class AudioChannelFormats {
    private val current = HashMap<Int, CarPlayBoxAudioFormat.Format>()

    /** True when the sink must be configured before this stream writes. */
    @Synchronized
    fun needsConfigure(channel: Int, format: CarPlayBoxAudioFormat.Format): Boolean {
        if (current[channel] == format) return false
        current[channel] = format
        return true
    }
}
