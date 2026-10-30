package com.andrerinas.openheadunit.aap

/** Receiver observations, not phone timestamps or proof of audible/on-screen presentation.
 * Audio queue duration covers the PCM bank only; video frames count processed codec output.
 * [owner] invalidates a measurement baseline when its underlying pipeline is replaced. */
internal data class GalPlaybackSample(
    val owner: Any,
    val audioQueueUs: Long? = null,
    val audioOverflows: Long? = null,
    val audioUnderruns: Long? = null,
    val videoFrames: Long? = null
)
