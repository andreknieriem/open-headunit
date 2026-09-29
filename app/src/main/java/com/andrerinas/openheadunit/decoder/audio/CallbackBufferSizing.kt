package com.andrerinas.openheadunit.decoder.audio

/** Divide one output latency budget between the HAL and the JNI-to-callback queue.
 * The producer needs a 10ms mixer interval and a callback in reserve, even on
 * devices with 2-4ms bursts. A one-burst queue starves on ordinary JVM scheduling. */
internal object CallbackBufferSizing {
    private fun minimumQueueFrames(burst: Int, callbackFrames: Int): Int {
        require(burst > 0)
        val callback = maxOf(burst, callbackFrames)
        val needed = maxOf(480L + callback, callback * 2L)
        return ((needed + burst - 1) / burst * burst).toInt()
    }

    fun minimumFrames(burst: Int, callbackFrames: Int): Int =
        burst * 2 + queueFrames(0, 0, burst, callbackFrames)

    fun deviceFrames(totalFrames: Int, burst: Int, callbackFrames: Int = burst,
                     preferredDeviceFrames: Int = burst * 2): Int {
        require(burst > 0)
        val bursts = (totalFrames.toLong() + burst - 1) / burst
        val queueBursts = minimumQueueFrames(burst, callbackFrames).toLong() / burst
        val preferredBursts = (preferredDeviceFrames.toLong() + burst - 1) / burst
        return (maxOf(2L, minOf(preferredBursts, bursts - queueBursts)) * burst).toInt()
    }

    fun queueFrames(totalFrames: Int, grantedDeviceFrames: Int, burst: Int, callbackFrames: Int = burst): Int {
        require(burst > 0)
        val remainder = (totalFrames - grantedDeviceFrames).coerceAtLeast(minimumQueueFrames(burst, callbackFrames))
        return ((remainder.toLong() + burst - 1) / burst * burst).toInt()
    }
}

/** Extra budget goes to producer scheduling unless the HAL itself reports an xrun. */
internal class CallbackDeviceBufferPolicy(private val burst: Int) {
    private var preferredFrames = burst * 2
    private var previousXruns = -1

    fun request(totalFrames: Int, callbackFrames: Int, hardwareXruns: Int): Int {
        if (previousXruns >= 0 && hardwareXruns > previousXruns) preferredFrames += burst
        previousXruns = hardwareXruns
        preferredFrames = CallbackBufferSizing.deviceFrames(totalFrames, burst, callbackFrames, preferredFrames)
        return preferredFrames
    }
}
