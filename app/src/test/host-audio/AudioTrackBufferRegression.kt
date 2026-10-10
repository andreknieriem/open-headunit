package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioTrack
import android.os.Build

/** Exercises the actual adapter: a fake PcmOutput would miss an allocation-only minimum. */
internal fun audioTrackBufferRegression() {
    val sdk = Build.VERSION.SDK_INT
    fun reset() {
        AudioTrack.minimumBytes = 3840
        AudioTrack.initialGrantFrames = 0
        AudioTrack.failMinimum = false
        AudioTrack.minimumQueries = 0
    }
    try {
        for (api in listOf(16, 19, 21, 23, 24, 26, 27, 28, 31, 34, 35, 36, 37)) {
            Build.VERSION.SDK_INT = api
            for (bytes in listOf(768, 3840, 7688, 13372, 23712, 76804, 120000)) {
                reset()
                AudioTrack.minimumBytes = bytes
                val output = AudioTrackPcmOutput(3, false)
                val track = AudioTrack.created.last()
                try {
                    val minimum = maxOf(960, (bytes + 3) / 4)
                    check(output.minimumBufferFrames == minimum) { "api=$api bytes=$bytes" }
                    val policy = OutputBufferPolicy(48000, output.burstFrames, output.minimumBufferFrames)
                    if (api >= 24) check(output.capacityFrames >= policy.maximumFrames)
                    val tuner = OutputBufferTuner()
                    var xruns = 0
                    for (time in 0L..40_000L step 100) {
                        if (time == 1000L || time == 2000L) xruns++
                        tuner.update(output, policy.update(time, xruns))
                        check(output.bufferFrames >= minimum) { "unsafe floor api=$api bytes=$bytes" }
                    }
                    // Direct requests from fallback/reopen must obey the same minimum.
                    check(output.setBufferFrames(192) >= minimum)
                    if (api < 24) check(track.bufferRequests.isEmpty())
                    else check(track.bufferRequests.all { it >= minimum })
                    output.start()
                    if (api >= 31) check(track.startThreshold == output.bufferFrames)
                    else check(track.startThreshold == 0)
                } finally { output.close() }
            }
        }
        println("PASS AudioTrack platform floors across API16..37, device sizes, tuning and direct requests")

        Build.VERSION.SDK_INT = 33
        reset()
        AudioTrack.minimumBytes = 23713 // ceil an incomplete byte estimate to a stereo PCM frame
        var output = AudioTrackPcmOutput(3, false)
        check(output.minimumBufferFrames == 5929)
        output.close()

        reset()
        output = AudioTrackPcmOutput(3, false)
        val track = AudioTrack.created.last()
        try {
            output.setBufferFrames(960)
            val queries = AudioTrack.minimumQueries
            check(output.minimumBufferFrames == 960) // initial 400ms allocation is not the floor
            output.setBufferFrames(1440)
            check(output.minimumBufferFrames == 960) // own upward probe may still shrink later
            output.setBufferFrames(960)
            repeat(100) { check(output.minimumBufferFrames == 960) }
            check(AudioTrack.minimumQueries == queries) // no binder/device-list polling
            val tuner = OutputBufferTuner()
            tuner.update(output, 960)
            // A restored native proxy starts with the entire allocation as its effective
            // size. It does not report a new mixer minimum or disable later tuning.
            track.bufferCapacityInFrames = 24000
            track.bufferSizeInFrames = track.bufferCapacityInFrames
            check(output.minimumBufferFrames == 960)
            check(tuner.update(output, 960))
            check(output.bufferFrames == 960)
            output.pause()
            check(output.minimumBufferFrames == 960)
        } finally { output.close() }
        println("PASS framework restore reapplies the safe target without adopting allocation as the floor")

        reset()
        AudioTrack.initialGrantFrames = 24000 // allocation raised beyond our 400ms request
        output = AudioTrackPcmOutput(3, false)
        check(output.capacityFrames == 24000)
        check(output.minimumBufferFrames == 960)
        check(output.setBufferFrames(960) == 960)
        output.close()
        reset()
        AudioTrack.minimumBytes = 23712
        output = AudioTrackPcmOutput(3, false)
        try {
            val grant = AudioTrack.created.last()
            // A vendor may return less capacity than requested. Keep actual grants visible;
            // never pretend the requested target was accepted or retry every unchanged tick.
            grant.bufferCapacityInFrames = 6000
            val tuner = OutputBufferTuner()
            check(tuner.update(output, 6240))
            check(output.bufferFrames == 6000)
            repeat(20) { check(!tuner.update(output, 6240)) }
        } finally { output.close() }
        reset()
        output = AudioTrackPcmOutput(3, false)
        check(output.minimumBufferFrames == 960) // a new output learns its own floor
        output.close()
        println("PASS allocation enlargements, clamped grants and new-output floor reset")

        reset()
        val initial = AudioTrackPcmOutput(3, false)
        val retired = AudioTrack.created.last()
        val recovery = RecoveringPcmOutput(initial, { AudioTrackPcmOutput(3, false) }, { 0L }, {})
        try {
            recovery.setBufferFrames(960)
            recovery.start()
            AudioTrack.minimumBytes = 23712
            retired.replies.add(-6)
            val data = shortArrayOf(11, 12, 13, 14)
            check(recovery.write(data, 0, data.size) == 0)
            val replacement = AudioTrack.created.last()
            check(retired.closed && replacement !== retired)
            check(recovery.minimumBufferFrames == 5928)
            check(replacement.bufferRequests.first() == 5928)
            check(replacement.startThreshold == 5928)
            check(recovery.write(data, 0, data.size) == data.size)
            check(replacement.samples.toList() == data.toList())
        } finally { recovery.close() }

        reset()
        AudioTrack.minimumBytes = 23712
        val source = object : PcmOutput {
            override val name = "AAudio stub"
            override val capacityFrames = 1920
            override val bufferFrames = 576
            override val minimumBufferFrames = 576
            override val burstFrames = 192
            override val underruns = 0
            override fun setBufferFrames(frames: Int) = frames
            override fun start() { }
            override fun pause() { }
            override fun close() { }
            override fun write(data: ShortArray, offset: Int, count: Int) = -6
            override fun closeForRecovery() = shortArrayOf(1, 2)
        }
        val fallback = FallbackPcmOutput(source, { AudioTrackPcmOutput(3, false) }, {})
        try {
            fallback.setBufferFrames(576)
            fallback.start()
            val data = shortArrayOf(3, 4)
            check(fallback.write(data, 0, data.size) == 0)
            val replacement = AudioTrack.created.last()
            check(fallback.minimumBufferFrames == 5928)
            check(replacement.bufferRequests.first() == 5928)
            check(replacement.startThreshold == 5928)
            check(fallback.write(data, 0, data.size) == 0) // pending software prefix first
            check(fallback.write(data, 0, data.size) == data.size)
            check(replacement.samples.toList() == listOf<Short>(1, 2, 3, 4))
        } finally { fallback.close() }
        println("PASS actual AudioTrack floors survive reopen and fallback without losing software PCM")

        reset()
        AudioTrack.minimumBytes = 23712
        val clockOffset = android.os.SystemClock.offsetMs
        val mixer = AudioMixer(preferAAudio = false, keepOutputActive = true)
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!condition()) {
                check(System.nanoTime() < deadline) { message }
                Thread.sleep(2)
            }
        }
        try {
            val before = AudioTrack.created.size
            val channel = mixer.registerChannel(5, 48000, 2)
            val pcm = ByteArray(960) { if (it % 2 == 0) 64 else 0 }
            mixer.feed(channel, pcm, 0, pcm.size)
            mixer.start()
            await("mixer opens real AudioTrack adapter") { AudioTrack.created.size > before }
            val device = AudioTrack.created.last()
            // The initial recovery metadata says 960. Seeing 6240 proves the mixer rebuilt
            // its policy using the live 5928-frame minimum, beyond the adapter's own clamp.
            await("mixer adopts rounded device floor") { device.bufferRequests.lastOrNull() == 6240 }
            device.underrunCount++
            await("mixer can grow beyond the device floor") { device.bufferRequests.lastOrNull() == 6720 }
            android.os.SystemClock.offsetMs += 11_000
            await("stable mixer shrinks to its device floor") { device.bufferRequests.lastOrNull() == 6240 }
            check(device.bufferRequests.all { it >= 5928 })
        } finally {
            mixer.stop()
            android.os.SystemClock.offsetMs = clockOffset
        }
        println("PASS real mixer replaces startup metadata floor and grows then shrinks above the device minimum")
    } finally {
        reset()
        Build.VERSION.SDK_INT = sdk
    }
}
