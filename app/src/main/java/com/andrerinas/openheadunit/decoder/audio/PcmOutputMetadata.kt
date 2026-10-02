package com.andrerinas.openheadunit.decoder.audio

/** Last valid properties remain available while no live device exists during recovery. */
internal class PcmOutputMetadata(output: PcmOutput?) {
    val name = output?.name ?: "Awaiting output"
    val capacityFrames = output?.capacityFrames ?: 19200
    val bufferFrames = output?.bufferFrames ?: 960
    val burstFrames = output?.burstFrames ?: 480
    val minimumBufferFrames = output?.minimumBufferFrames ?: 960
    val stagingBufferFrames = output?.stagingBufferFrames ?: 0
    val underruns = output?.underruns ?: 0
    val underrunsSupported = output?.underrunsSupported ?: false
    val producerUnderruns = output?.producerUnderruns ?: 0
}
