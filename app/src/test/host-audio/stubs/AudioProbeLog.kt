package com.andrerinas.openheadunit.decoder.audio
internal object AudioProbeLog {
    fun open(label: String): AudioProbe? = null
    fun poll(probe: AudioProbe?) {}
    fun cpu() = 0L
    fun event(message: String) {}
}
