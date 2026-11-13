package com.andrerinas.openheadunit.secondscreen

import android.view.Surface

/** One place the second Android Auto stream can be shown. */
interface SecondScreenOutput {
    val output: SecondScreenOutputPolicy.Output

    /** Called when the picture can only come back from a fresh keyframe. */
    var onKeyframeNeeded: (() -> Unit)?

    fun start()
    fun stop()
}

/** An output that takes the H.264 stream as it arrives and decodes it somewhere else. */
interface EncodedOutput : SecondScreenOutput {
    /** One Annex-B access unit. The buffer is reused after this returns, so copy what is kept. */
    fun onAccessUnit(buf: ByteArray, offset: Int, length: Int)
}

/** An output whose picture the head unit decodes, into the surface it hands out. */
interface SurfaceOutput : SecondScreenOutput {
    fun surface(): Surface?
}
