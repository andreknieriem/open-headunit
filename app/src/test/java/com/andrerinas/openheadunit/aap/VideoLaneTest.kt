package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/** A worker that queues jobs for the test to run, and never starts its thread. */
internal class FakeLaneWorker(override val thread: Thread = Thread()) : VideoLane.Worker {
    val jobs = mutableListOf<Runnable>()
    var quitCalled = false
    override fun post(job: Runnable): Boolean { jobs.add(job); return true }
    override fun quit() { quitCalled = true }
}

class VideoLaneTest {
    private val video = mock<AapVideo>()
    private val worker = FakeLaneWorker()
    private val acks = mutableListOf<Pair<Int, Int>>()
    private var session = 101

    init { whenever(video.isPayload(any())).thenAnswer { (it.arguments[0] as AapMessage).type in 0..1 } }

    private fun lane(channel: Int = Channel.ID_VID, started: Boolean = true): VideoLane =
        VideoLane(channel, video, "test", { session }, { ch, s -> acks.add(ch to s) }, { worker })
            .also { if (started) it.start() }

    private fun message(channel: Int, flags: Int, type: Int) =
        AapMessage(channel, flags.toByte(), type, 2, 16, ByteArray(16))

    private inline fun <T> counting(warnings: MutableList<String>, block: () -> T): T {
        val previous = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                if (priority == android.util.Log.WARN) warnings.add(msg.substringAfter(" | "))
            }
        }
        try { return block() } finally { AppLog.LOGGER = previous }
    }

    private fun fill(lane: VideoLane, channel: Int) {
        repeat(VideoLane.VIDEO_BACKLOG_LIMIT) { lane.dispatch(message(channel, 9, 0)) }
        assertEquals(VideoLane.VIDEO_BACKLOG_LIMIT, lane.queueDepth())
        acks.clear()
        worker.jobs.clear()
    }

    @Test fun `a complete payload acknowledges only after its job runs`() {
        val lane = lane()
        assertTrue(lane.dispatch(message(Channel.ID_VID, 11, 0)))
        assertTrue(acks.isEmpty())
        worker.jobs.single().run()
        assertEquals(listOf(Channel.ID_VID to 101), acks)
    }

    @Test fun `the acknowledgement carries the session read at receipt`() {
        val lane = lane()
        lane.dispatch(message(Channel.ID_VID, 11, 0))
        session = 202
        worker.jobs.single().run()
        assertEquals(listOf(Channel.ID_VID to 101), acks)
    }

    @Test fun `acknowledgement follows the completion rule on both lanes`() {
        for (channel in listOf(Channel.ID_VID, Channel.ID_VID2)) {
            for ((flags, type, expected) in listOf(
                Triple(9, 0, 0), Triple(8, 0, 0), Triple(10, 0, 1), Triple(11, 0, 1), Triple(11, 1, 0))) {
                acks.clear(); worker.jobs.clear()
                val lane = lane(channel)
                lane.dispatch(message(channel, flags, type))
                worker.jobs.forEach { it.run() }
                assertEquals("channel=$channel flags=$flags type=$type", expected, acks.size)
            }
        }
    }

    @Test fun `a lane that is not started acknowledges at once and posts nothing`() {
        val lane = lane(started = false)
        assertTrue(lane.dispatch(message(Channel.ID_VID, 11, 0)))
        assertEquals(listOf(Channel.ID_VID to 101), acks)
        assertTrue(worker.jobs.isEmpty())
        assertFalse(lane.dispatch(message(Channel.ID_VID, 11, 5)))
    }

    @Test fun `a payload at the ceiling is shed with a hole job and an immediate ack`() {
        val lane = lane()
        fill(lane, Channel.ID_VID)
        assertTrue(lane.dispatch(message(Channel.ID_VID, 11, 0)))
        assertEquals(VideoLane.VIDEO_BACKLOG_LIMIT, lane.queueDepth())
        assertEquals(1L, lane.shedCount())
        assertEquals(listOf(Channel.ID_VID to 101), acks)
        worker.jobs.single().run()
        verify(video).onFragmentRunHoled(true)
    }

    @Test fun `a non payload message at the ceiling posts no hole job`() {
        val lane = lane()
        fill(lane, Channel.ID_VID)
        assertFalse(lane.dispatch(message(Channel.ID_VID, 11, 5)))
        assertTrue(worker.jobs.isEmpty())
    }

    @Test fun `each lane warns once and the main lane keeps its exact text`() {
        val warnings = mutableListOf<String>()
        counting(warnings) {
            val main = lane()
            fill(main, Channel.ID_VID)
            repeat(2) { main.dispatch(message(Channel.ID_VID, 11, 0)) }
            val aux = lane(Channel.ID_VID2)
            fill(aux, Channel.ID_VID2)
            repeat(2) { aux.dispatch(message(Channel.ID_VID2, 11, 0)) }
        }
        assertEquals(listOf(
            "AapTransport: the video thread is 256 messages behind, shedding - see videoShed= on the transport dispatch line for how many",
            "AapTransport: the VIDEO_AUX thread is 256 messages behind, shedding",
        ), warnings)
    }

    @Test fun `an aux lane at its ceiling leaves the main lane untouched`() {
        val main = lane()
        val aux = VideoLane(Channel.ID_VID2, video, "aux", { session }, { _, _ -> }, { FakeLaneWorker() }).also { it.start() }
        repeat(VideoLane.VIDEO_BACKLOG_LIMIT + 1) { aux.dispatch(message(Channel.ID_VID2, 9, 0)) }
        assertEquals(1L, aux.shedCount())
        assertEquals(0L, main.shedCount())
        assertEquals(0, main.queueDepth())
    }

    @Test fun `runHoled with no worker does nothing`() {
        lane(started = false).runHoled(true)
        verify(video, never()).onFragmentRunHoled(any())
    }

    @Test fun `release resets counters and the thread even when the assembler throws`() {
        val lane = lane()
        fill(lane, Channel.ID_VID)
        lane.dispatch(message(Channel.ID_VID, 11, 0))
        whenever(video.release()).thenThrow(IllegalStateException("fixture"))
        try { lane.release(); fail() } catch (_: IllegalStateException) { }
        verify(video).release()
        assertEquals(0, lane.queueDepth())
        assertEquals(0L, lane.shedCount())
        assertNull(lane.thread)
    }
}
