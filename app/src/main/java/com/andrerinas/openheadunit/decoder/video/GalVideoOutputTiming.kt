package com.andrerinas.openheadunit.decoder.video

import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.utils.AppLog

/** A codec incarnation owns this bridge. Retiring it cancels waits and fences late callbacks. */
internal class GalVideoOutputTiming(
    @Volatile private var controller: GalPresentationController?,
    @Volatile private var generation: Long,
    private val surfaceKind: VideoSurfaceKind = VideoSurfaceKind.UNKNOWN,
    private val nanoTime: Clock = Clock { System.nanoTime() },
    private val feedbackAllowed: Boolean = surfaceKind == VideoSurfaceKind.DIRECT_DISPLAY,
    private val sleepNs: Sleeper = Sleeper { Thread.sleep(it / 1_000_000L, (it % 1_000_000L).toInt()) },
) {
    fun interface Clock { fun read(): Long }
    fun interface Sleeper { fun sleep(nanos: Long) }
    @Volatile private var active = true
    private var outputEpoch = controller?.beginVideoOutput(generation) ?: 0L
    private class Metadata(feedbackAllowed: Boolean) {
        val inputs = VideoTimingMetadata()
        val rendered = if (feedbackAllowed) VideoTimingMetadata() else null
        val output = VideoTimingMetadata.Sample()
        val feedback = if (feedbackAllowed) VideoTimingMetadata.Sample() else null
    }
    @Volatile private var metadata: Metadata? = null
    private var matchedOutput = false
    private var requestedCount = 0L
    private var droppedCount = 0L
    private var timingDroppedCount = 0L
    private var scheduledCount = 0L
    private var observedCount = 0L
    private var callbackCount = 0L
    private var rejectedClockCount = 0L
    private var lastRawCallbackNs = 0L
    private var lastCallbackReceiptNs = 0L
    private var lastRequestedNs = 0L
    private var lastActualNs = 0L
    private var lastObservedSourceUs = Long.MIN_VALUE
    private var lastObservedArrivalNs = 0L
    private var observedRequestNs = 0L
    private var lastErrorNs = 0L
    private var lastLogNs = 0L
    @Volatile private var mappingEnabled = false
    private var waitSlices = 0L
    private var waitedNs = 0L
    private var rescuedRepairToken = 0L
    private var repairGranted = false
    private var repairRescueCount = 0L

    /** One decoded repair candidate may establish a picture even if codec warm-up exceeded a
     * narrow normal window. This is not steady-state lateness tolerance or presentation proof.
     * The caller still requires its normal keyframe/input-acceptance and successful-release proof.
     * Missing metadata, stale sessions and frames older than one second never receive this grant.
     */
    @Synchronized fun repairDeadline(token: Long): Long {
        if (token == 0L || token == rescuedRepairToken || !active ||
            controller?.isVideoSessionActive(generation) != true || !timingEnabled() || !matchedOutput) return Long.MIN_VALUE
        val output = metadata?.output ?: return Long.MIN_VALUE
        val now = nanoTime.read()
        if (output.generation != generation || now < output.arrivalNs ||
            now - output.arrivalNs !in 0..1_000_000_000L) return Long.MIN_VALUE
        rescuedRepairToken = token
        repairGranted = true
        repairRescueCount++
        return 0L
    }

    fun controlsPresentation(): Boolean =
        active && controller?.isVideoTimingEnabled(generation) == true

    /** Steady disabled sessions take no monitor and allocate no metadata storage. */
    private fun timingEnabled(): Boolean {
        val enabled = active && controller?.isVideoTimingEnabled(generation) == true
        if (!enabled) {
            if (mappingEnabled) synchronized(this) {
                if (mappingEnabled) {
                    metadata?.inputs?.clear()
                    metadata?.rendered?.clear()
                    mappingEnabled = false
                }
            }
            return false
        }
        if (mappingEnabled) return true
        return synchronized(this) {
            if (!active || controller?.isVideoTimingEnabled(generation) != true) false
            else {
                if (metadata == null) metadata = Metadata(feedbackAllowed && surfaceKind == VideoSurfaceKind.DIRECT_DISPLAY)
                mappingEnabled = true
                true
            }
        }
    }

    /** Initial CSD may already have created the codec and its captured callbacks. Promote that
     * same bridge once rather than retiring it or losing the codec's parameter-set state. */
    @Synchronized fun beginSession(controller: GalPresentationController?, generation: Long): Boolean {
        if (!active || this.generation != 0L) return false
        matchedOutput = false
        repairGranted = false
        metadata?.inputs?.clear()
        metadata?.rendered?.clear()
        mappingEnabled = false
        // Publish the generation before the controller: pre-Start uses a null controller.
        this.generation = generation
        outputEpoch = controller?.beginVideoOutput(generation) ?: 0L
        this.controller = controller
        return true
    }

    @Synchronized fun retire() {
        active = false
        controller?.retireVideoOutput(generation, outputEpoch)
        repairGranted = false
        metadata?.inputs?.clear()
        metadata?.rendered?.clear()
        mappingEnabled = false
    }
    fun register(pts: Long, sourceUs: Long, arrivalNs: Long) {
        if (timingEnabled()) metadata?.inputs?.put(pts, generation, sourceUs, arrivalNs)
    }
    fun discardInput(pts: Long) { metadata?.inputs?.remove(pts); metadata?.rendered?.remove(pts) }
    // Called only by the output worker (or the bundled decoder's worker callback).
    fun deadline(pts: Long): Long {
        repairGranted = false
        matchedOutput = if (timingEnabled()) {
            val state = metadata
            state != null && state.inputs.take(pts, state.output) && state.output.generation == generation
        } else false
        return refreshDeadline()
    }

    private fun refreshDeadline(): Long {
        if (!active || controller?.isVideoSessionActive(generation) == false) return Long.MIN_VALUE
        if (!timingEnabled() || !matchedOutput) return 0L
        val output = metadata?.output ?: return 0L
        return controller?.admitVideo(
            generation, outputEpoch, output.sourceTimeUs.takeUnless { it == Long.MIN_VALUE },
            output.arrivalNs, nanoTime.read()
        ) ?: 0L
    }

    /** Re-evaluate every slice so runtime disable, stop and audio clock recovery take effect. */
    fun await(initialDeadline: Long): Long {
        var deadline = initialDeadline
        while (active && deadline != Long.MIN_VALUE) {
            val slice = VideoPresentationWait.sliceNs(deadline, nanoTime.read())
            if (slice == 0L) return deadline
            synchronized(this) { waitSlices++; waitedNs += slice }
            try { sleepNs.sleep(slice) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return Long.MIN_VALUE }
            deadline = refreshDeadline()
        }
        return Long.MIN_VALUE
    }
    /**
     * Final reservation, ordered against controller updates and this output's retirement.
     * Only the short claim holds timing -> controller monitors; pacing and native/GL calls
     * are outside both. Already admitted native work may finish after a control boundary.
     * A custom consumer retries a newly future deadline instead of attaching an inert stamp.
     */
    fun admit(platformTiming: Boolean): Long {
        while (true) {
            val deadline = synchronized(this) {
                if (!active || Thread.currentThread().isInterrupted) return Long.MIN_VALUE
                val owner = controller ?: return 0L
                val output = metadata?.output
                val matched = matchedOutput && output?.generation == generation
                val now = nanoTime.read()
                owner.admitVideo(
                    generation, outputEpoch,
                    if (matched) output!!.sourceTimeUs.takeUnless { it == Long.MIN_VALUE } else null,
                    if (matched) output!!.arrivalNs else Long.MIN_VALUE,
                    now,
                    allowLateRepair = repairGranted && matched && now >= output!!.arrivalNs &&
                        now - output.arrivalNs <= 1_000_000_000L,
                )
            }
            if (platformTiming || deadline <= 0L) return deadline
            await(deadline)
        }
    }

    fun releasing(pts: Long, deadline: Long, render: Boolean) {
        if (!timingEnabled()) return
        synchronized(this) {
            if (!active || !mappingEnabled) return
            val state = metadata ?: return
            if (render) {
                requestedCount++
                if (deadline != 0L) scheduledCount++
                lastRequestedNs = if (deadline == 0L) nanoTime.read() else deadline
                if (matchedOutput && state.output.generation == generation)
                    state.rendered?.put(pts, generation, state.output.sourceTimeUs, state.output.arrivalNs, lastRequestedNs)
            } else {
                droppedCount++
                if (deadline == Long.MIN_VALUE) timingDroppedCount++
            }
            report(nanoTime.read())
        }
    }

    fun onFrameRendered(pts: Long, actualNs: Long) {
        // A custom consumer can echo the assigned buffer timestamp even when it is locally
        // plausible. Clock admission alone cannot turn that stamp into display evidence.
        if (!feedbackAllowed || surfaceKind != VideoSurfaceKind.DIRECT_DISPLAY || !timingEnabled()) return
        synchronized(this) {
            if (!active || !mappingEnabled) return
            val state = metadata ?: return
            val feedback = state.feedback ?: return
            if (state.rendered?.take(pts, feedback) != true || feedback.generation != generation) return
            val receiptNs = nanoTime.read()
            callbackCount++
            lastRawCallbackNs = actualNs
            lastCallbackReceiptNs = receiptNs
            if (!VideoFrameClockAdmission.accepts(feedback.arrivalNs, feedback.requestedNs, actualNs, receiptNs)) {
                rejectedClockCount++
                report(receiptNs)
                return
            }
            observedCount++
            lastActualNs = actualNs
            lastObservedSourceUs = feedback.sourceTimeUs
            lastObservedArrivalNs = feedback.arrivalNs
            observedRequestNs = feedback.requestedNs
            lastErrorNs = actualNs - feedback.requestedNs
            if (feedback.sourceTimeUs != Long.MIN_VALUE)
                controller?.observeVideoOutput(generation, outputEpoch, feedback.sourceTimeUs, actualNs)
            report(receiptNs)
        }
    }

    private fun report(now: Long) {
        if (controller == null) return
        if (lastLogNs != 0L && now - lastLogNs < 5_000_000_000L) return
        lastLogNs = now
        // Only admitted paired callbacks are observations. Raw timestamps remain separately
        // labelled even when a surface returns a foreign clock or implausible time.
        AppLog.i("GAL video timing: surface=%s generation=%d requested=%d scheduled=%d dropped=%d timingDropped=%d repairRescues=%d observed=%d callbacks=%d clockRejected=%d waitSlices=%d requestedWaitNs=%d requestedNs=%d rawCallbackNs=%s callbackReceiptNs=%s observedRequestNs=%s actualNs=%s errorNs=%s",
            surfaceKind.name, generation, requestedCount, scheduledCount, droppedCount, timingDroppedCount, repairRescueCount, observedCount,
            callbackCount, rejectedClockCount, waitSlices, waitedNs, lastRequestedNs,
            if (callbackCount > 0) lastRawCallbackNs.toString() else "unavailable",
            if (callbackCount > 0) lastCallbackReceiptNs.toString() else "unavailable",
            if (observedCount > 0) observedRequestNs.toString() else "unavailable",
            if (observedCount > 0) lastActualNs.toString() else "unavailable",
            if (observedCount > 0) lastErrorNs.toString() else "unavailable")
    }
}

/** New platform types are isolated from the API16 class verifier. */
@RequiresApi(21)
internal object VideoTimedReleaseApi21 {
    fun release(codec: MediaCodec, index: Int, deadlineNs: Long) = codec.releaseOutputBuffer(index, deadlineNs)
}

@RequiresApi(23)
internal object VideoRenderedFeedbackApi23 {
    fun install(codec: MediaCodec, timing: GalVideoOutputTiming) {
        codec.setOnFrameRenderedListener({ _, pts, actualNs -> timing.onFrameRendered(pts, actualNs) }, Handler(Looper.getMainLooper()))
    }
}
