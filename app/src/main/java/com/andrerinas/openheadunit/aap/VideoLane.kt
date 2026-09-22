package com.andrerinas.openheadunit.aap

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One projected display's video: its assembler, its thread, and the backlog that bounds it.
 *
 * A lane per display rather than a shared one, because [AapVideo]'s reassembly state is per stream
 * and two displays' fragment runs interleave on the same connection.
 */
internal class VideoLane(
    val channel: Int,
    val video: AapVideo,
    private val threadName: String,
    private val sendMediaAck: (Int) -> Unit,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // Reused payload copies for the video thread. The SSL layer hands back one buffer it overwrites
    // on the next read, so a message that outlives the read has to carry its own bytes; pooling
    // them keeps a 50 fps stream from allocating per frame.
    private val bufferPool = LinkedBlockingQueue<ByteArray>()

    // Messages handed to the video thread and not yet processed. The park that used to hold the
    // read thread lives here now, so this is where it can still be seen.
    private val backlog = AtomicInteger(0)

    // Messages refused because the backlog was already at its ceiling. See VIDEO_BACKLOG_LIMIT.
    private val shedTotal = AtomicLong(0)

    fun start() {
        val started = HandlerThread(threadName, Process.THREAD_PRIORITY_DISPLAY)
        started.start()
        thread = started
        // Handler(thread.looper, ...) blocks internally until the Looper is ready, so no sleep here.
        handler = Handler(started.looper)
    }

    fun quit() {
        thread?.quit()
    }

    /** Joining from the lane's own thread would block for the full timeout, so it is refused. */
    fun join(timeoutMs: Long) {
        val running = thread ?: return
        if (Thread.currentThread() != running) running.join(timeoutMs)
    }

    /** After the join, not before: resetting run state under a thread still assembling hands the
     * next session a half-run. */
    fun release() {
        video.release()
        bufferPool.clear()
        backlog.set(0)
        shedTotal.set(0)
        handler = null
        thread = null
    }

    /**
     * Hands a video-channel message to the video thread, and answers whether it was picture.
     *
     * A false answer means control traffic on the video channel, which the caller goes on to handle
     * exactly where it always did. The assembler still sees it, because its run state is what
     * decides whether the next fragment is an orphan.
     *
     * **The media ack goes out from the video thread, after the decode.** Acking on receipt let the
     * backlog climb 918, 1803, 2732, 3671, 4666, 5602 over six windows at 64 KiB a message and never
     * recover. Acking behind the work costs the read thread nothing. It does not hold the queue to
     * the window we announce, though: with the feed held, a phone told `max_unacked=12` ran the
     * backlog to 256, so [VIDEO_BACKLOG_LIMIT] is the real ceiling.
     */
    fun dispatch(message: AapMessage): Boolean {
        val isPayload = video.isPayload(message)
        val acks = message.type == 0 || message.type == 1
        val target = handler
        if (target == null) {
            if (acks) sendMediaAck(message.channel)
            return isPayload
        }
        val messageChannel = message.channel
        if (backlog.get() >= VIDEO_BACKLOG_LIMIT) {
            // A phone that ignores its own window, or an ack we never got to send. Shed rather than
            // allocate, and tell the assembler the run has a hole so a partial access unit is
            // discarded instead of decoded as though it were whole. The ack still goes out, or the
            // window closes for good and the picture never comes back.
            if (shedTotal.getAndIncrement() == 0L) {
                AppLog.w("AapTransport: the video thread is $VIDEO_BACKLOG_LIMIT messages behind, " +
                    "shedding - see videoShed= on the transport dispatch line for how many")
            }
            if (isPayload) runHoled(true)
            if (acks) sendMediaAck(messageChannel)
            return isPayload
        }
        val size = message.size
        val copy = obtainBuffer(size)
        System.arraycopy(message.data, 0, copy, 0, size)
        val queued = AapMessage(messageChannel, message.flags, message.type, message.dataOffset, size, copy)
        backlog.incrementAndGet()
        target.post {
            try {
                video.process(queued)
            } catch (e: Exception) {
                AppLog.e("Error processing video message", e)
            } finally {
                backlog.decrementAndGet()
                recycleBuffer(copy)
                if (acks) sendMediaAck(messageChannel)
            }
        }
        return isPayload
    }

    /**
     * The reader's framing audit found a run short of the bytes its first fragment declared.
     *
     * Posted rather than called so it lands on the video thread in front of the run's last
     * fragment, which is the message that consumes it. See [AapVideo.onFragmentRunHoled].
     */
    fun runHoled(discardAssembledUnit: Boolean) {
        val target = handler ?: return
        target.post { video.onFragmentRunHoled(discardAssembledUnit) }
    }

    /** Video messages handed over and not yet processed. See [TransportDispatchMonitor]. */
    fun queueDepth(): Int = backlog.get()

    /** Video messages shed for the life of this lane, because the backlog was at its ceiling. */
    fun shedCount(): Long = shedTotal.get()

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
         * Messages the video thread may be behind before the lane sheds.
         *
         * The real ceiling, not a backstop: we announce 12 messages on wireless and 16 on USB, and a
         * phone held at 200ms a frame reached 256 in under three minutes, 21x the window. Sized so
         * the worst case is about 16 MB of copies rather than the 350 MB a run with no ceiling
         * reached. Only that artificial hold has reached it; the same route off it sits at 2 to 5.
         */
        private const val VIDEO_BACKLOG_LIMIT = 256

        private const val VIDEO_BUFFER_POOL_LIMIT = 8
        private const val MIN_VIDEO_BUFFER_BYTES = 64 * 1024
    }
}
