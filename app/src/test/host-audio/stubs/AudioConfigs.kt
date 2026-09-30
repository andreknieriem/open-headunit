package com.andrerinas.openheadunit.aap.protocol
object AudioConfigs {
    data class Config(val sampleRate: Int=48000, val numberOfBits: Int=16, val numberOfChannels: Int=2)
    fun get(channel: Int) = Config()
    fun stream(channel: Int, separate: Boolean, media: Int, guidance: Int, system: Int) = media
}
