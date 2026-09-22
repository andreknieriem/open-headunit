package com.andrerinas.openheadunit.aap

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One video channel's assembler, worker, buffer pool and backlog. One lane per display, because two
 * displays' fragment runs interleave on one connection. The transport joins the worker.
 */
internal class VideoLane(
    val channel: Int,
    internal val video: AapVideo,
    private val threadName: String,
    private val sessionOf: (channel: Int) -> GalMediaSessions.Session?,
    private val sendMediaAck: (channel: Int, sessionId: Int) -> Unit,
    private val startWorker: (name: String) -> Worker = ::looperWorker,
) {
    internal interface Worker {
        val thread: Thread
        fun post(job: Runnable): Boolean
        fun quit()
    }

    @Volatile private var worker: Worker? = null

    /** The worker's thread, for the transport to join. Null before [start] and after [release]. */
    val thread: Thread? get() = worker?.thread

    // The SSL layer reuses one read buffer, so a queued message needs its own bytes; pooling them
    // keeps a 50 fps stream from allocating per frame.
    private val bufferPool = LinkedBlockingQueue<ByteArray>()
    private val backlog = AtomicInteger(0)
    private val shedTotal = AtomicLong(0)
    // Poll-thread only. With no video work in between, the assembler still owns the same run:
    // one loss notice is enough, unless a repair-only notice must become a discard.
    private var lastHoleDiscard: Boolean? = null
    private var lastHoleGeneration: Long? = null

    /** Whether [start] ran and [release] has not. */
    val isStarted: Boolean get() = worker != null

    fun start() {
        worker = startWorker(threadName)
    }

    fun quit() {
        worker?.quit()
    }

    /** After the join: resetting run state under a live worker hands the next session a half-run. */
    fun release() {
        try {
            video.release()
        } finally {
            bufferPool.clear()
            backlog.set(0)
            shedTotal.set(0)
            lastHoleDiscard = null
            lastHoleGeneration = null
            worker = null
        }
    }

    /**
     * Hands a message to the worker and answers whether it was picture; false means control traffic.
     * The worker acks after the decode, so the phone cannot run ahead of it.
     */
    fun dispatch(message: AapMessage): Boolean {
        val isPayload = video.isPayload(message)
        val arrivalNs = System.nanoTime()
        // Decode ownership uses the local generation; the ack keeps the wire ID and mode read at receipt.
        val session = sessionOf(message.channel)
        val ackSession = if (AapMessageFraming.completesMediaData(message.type, message.flags.toInt()) &&
            session?.ackless != true) session?.id ?: 0 else null
        val target = worker
        // Drop pre-discovery packets at receipt, not later after the worker observes a codec.
        if (target == null || video.announcedCodec == null) {
            if (ackSession != null) sendMediaAck(message.channel, ackSession)
            return isPayload
        }
        val messageChannel = message.channel
        if (backlog.get() >= VIDEO_BACKLOG_LIMIT) {
            // Shed rather than allocate, mark the run holed so a partial unit is discarded, and
            // still ack, or the phone's window closes for good.
            if (shedTotal.getAndIncrement() == 0L) AppLog.w(shedWarning())
            if (isPayload) runHoled(true)
            if (ackSession != null) sendMediaAck(messageChannel, ackSession)
            return isPayload
        }
        lastHoleDiscard = null
        val size = message.size
        val copy = obtainBuffer(size)
        System.arraycopy(message.data, 0, copy, 0, size)
        val queued = AapMessage(messageChannel, message.flags, message.type, message.dataOffset, size, copy)
        backlog.incrementAndGet()
        val posted = target.post(Runnable {
            try {
                // Fresh pre-Start CSD and its Setup boundary stay ahead of the next Start.
                // DATA and active-owner CSD never cross that boundary.
                val current = sessionOf(messageChannel)
                val preparation = queued.type == Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE ||
                    queued.type == 1 && session?.active != true
                if (current?.generation == session?.generation ||
                    preparation && current?.previousGeneration == session?.generation) {
                    video.process(queued, session, arrivalNs)
                }
            } catch (e: Exception) {
                AppLog.e("Error processing video message", e)
            } finally {
                backlog.decrementAndGet()
                recycleBuffer(copy)
                if (ackSession != null) sendMediaAck(messageChannel, ackSession)
            }
        })
        if (!posted) {
            backlog.decrementAndGet()
            recycleBuffer(copy)
            if (ackSession != null) sendMediaAck(messageChannel, ackSession)
        }
        return isPayload
    }

    /** Runs [job] on this lane's worker, in order with its queued messages. */
    fun post(job: () -> Unit): Boolean = worker?.post(Runnable(job)) ?: false

    /**
     * The reader's audit found a run short of its declared bytes. Posted so it reaches the worker
     * ahead of the run's last fragment. See [AapVideo.onFragmentRunHoled].
     */
    fun runHoled(discardAssembledUnit: Boolean) {
        val target = worker ?: return
        val session = sessionOf(channel)
        val saturated = backlog.get() >= VIDEO_BACKLOG_LIMIT
        if (saturated && session?.generation == lastHoleGeneration &&
            (lastHoleDiscard == true || lastHoleDiscard == discardAssembledUnit)) return
        if (target.post(Runnable { video.onFragmentRunHoled(discardAssembledUnit, session) })) {
            lastHoleGeneration = session?.generation
            lastHoleDiscard = if (saturated) discardAssembledUnit else null
        }
    }

    /** Messages handed over and not yet processed. See [TransportDispatchMonitor]. */
    fun queueDepth(): Int = backlog.get()

    /** Messages shed for the life of this lane, because the backlog was at its ceiling. */
    fun shedCount(): Long = shedTotal.get()

    // The pointer to videoShed= is for the main lane only: that field reads no other lane.
    private fun shedWarning(): String =
        if (channel == Channel.ID_VID) {
            "AapTransport: the video thread is $VIDEO_BACKLOG_LIMIT messages behind, " +
                "shedding - see videoShed= on the transport dispatch line for how many"
        } else {
            "AapTransport: the ${Channel.name(channel)} thread is $VIDEO_BACKLOG_LIMIT messages behind, shedding"
        }

    private fun obtainBuffer(size: Int): ByteArray {
        while (true) {
            val pooled = bufferPool.poll() ?: return ByteArray(maxOf(size, MIN_VIDEO_BUFFER_BYTES))
            if (pooled.size >= size) return pooled
        }
    }

    private fun recycleBuffer(buffer: ByteArray) {
        if (bufferPool.size < VIDEO_BUFFER_POOL_LIMIT) bufferPool.offer(buffer)
    }

    companion object {
        /**
         * Messages the worker may be behind before the lane sheds. A slow decoder reaches it, so it
         * is the ceiling on the copies the lane holds, not a backstop.
         */
        internal const val VIDEO_BACKLOG_LIMIT = 256

        private const val VIDEO_BUFFER_POOL_LIMIT = 8
        private const val MIN_VIDEO_BUFFER_BYTES = 64 * 1024

        private fun looperWorker(name: String): Worker {
            val handlerThread = HandlerThread(name, Process.THREAD_PRIORITY_DISPLAY)
            handlerThread.start()
            // Handler(looper) blocks until the Looper is ready, so no sleep is needed.
            val handler = Handler(handlerThread.looper)
            return object : Worker {
                override val thread: Thread get() = handlerThread
                override fun post(job: Runnable): Boolean = handler.post(job)
                override fun quit() { handlerThread.quit() }
            }
        }
    }
}
