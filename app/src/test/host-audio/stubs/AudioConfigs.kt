package com.andrerinas.openheadunit.aap.protocol
object AudioConfigs {
    data class Config(val sampleRate: Int=48000, val numberOfBits: Int=16, val numberOfChannels: Int=2)
    var formats = emptyMap<Int, Config>()
    fun get(channel: Int, index: Int = 0): Config {
        val base = formats[channel] ?: Config()
        return if (index == 0) base else base.copy(sampleRate = 48000)
    }
    fun stream(channel: Int, separate: Boolean, media: Int, guidance: Int, system: Int) = media
}
