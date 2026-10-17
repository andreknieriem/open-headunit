package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch

/** Both lanes through the real transport bodies: dispatch, hole routing, quit and the join. */
class AuxVideoLaneTransportTest {
    private lateinit var clock: org.mockito.MockedStatic<android.os.SystemClock>
    private lateinit var androidLog: org.mockito.MockedStatic<android.util.Log>
    @org.junit.Before fun stubStatics() {
        clock = mockStatic(android.os.SystemClock::class.java)
        androidLog = mockStatic(android.util.Log::class.java)
    }
    @org.junit.After fun restoreStatics() { androidLog.close(); clock.close() }

    /** A worker on a real thread that stays alive until [quit]. */
    private class ThreadWorker(body: (() -> Unit)? = null) : VideoLane.Worker {
        val release = CountDownLatch(1)
        override val thread = Thread { release.await(); body?.invoke() }.also { it.start() }
        override fun post(job: Runnable) = true
        override fun quit() { release.countDown() }
    }

    private class Fixture(val transport: AapTransport, val settings: Settings,
                          val mainVideo: AapVideo, val auxVideo: AapVideo)

    private fun fixture(auxEnabled: Boolean = false, mainWorker: VideoLane.Worker = FakeLaneWorker(),
                        auxWorker: VideoLane.Worker? = null): Fixture {
        val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        val settings = mock(Settings::class.java)
        whenever(settings.auxDisplayEnabled).thenReturn(auxEnabled)
        val mainVideo = mock(AapVideo::class.java)
        val auxVideo = mock(AapVideo::class.java)
        whenever(mainVideo.isPayload(any())).thenReturn(true)
        whenever(auxVideo.isPayload(any())).thenReturn(true)
        fun set(name: String, value: Any?) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)
        set("tlsWriter", AapTlsWriter(mock(AapSsl::class.java)) { _, length -> length })
        set("quitLock", Any())
        set("lifecycleLock", Any())
        set("terminated", CountDownLatch(1))
        set("settings", settings)
        for (name in listOf("videoDecoder", "aapAudio", "aapVideo", "ssl", "micSessions", "focusCycleLever")) {
            val field = AapTransport::class.java.getDeclaredField(name).apply { isAccessible = true }
            field.set(transport, mock(field.type))
        }
        val main = VideoLane(Channel.ID_VID, mainVideo, "main", { 1 }, { _, _ -> }, { mainWorker })
        main.start()
        set("videoLane", main)
        if (auxWorker != null) {
            val aux = VideoLane(Channel.ID_VID2, auxVideo, "aux", { 1 }, { _, _ -> }, { auxWorker })
            aux.start()
            set("auxVideoLane", aux)
        }
        return Fixture(transport, settings, mainVideo, auxVideo)
    }

    private fun aux(transport: AapTransport): VideoLane? =
        AapTransport::class.java.getDeclaredField("auxVideoLane").apply { isAccessible = true }.get(transport) as VideoLane?

    private fun message(channel: Int) = AapMessage(channel, 11, 0, 2, 16, ByteArray(16))

    @Test fun `with the feature off the auxiliary channel is ignored and builds nothing`() {
        val f = fixture(auxEnabled = false)
        assertFalse(f.transport.dispatchVideo(message(Channel.ID_VID2)))
        assertNull(aux(f.transport))
    }

    @Test fun `quit joins the auxiliary worker before releasing its assembler`() {
        val worker = ThreadWorker()
        val f = fixture(auxWorker = worker)
        var aliveAtRelease: Boolean? = null
        doAnswer { aliveAtRelease = worker.thread.isAlive; null }.whenever(f.auxVideo).release()
        f.transport.quit()
        val retiring = AapTransport::class.java.getDeclaredField("retiringWorkers")
            .apply { isAccessible = true }.get(f.transport) as List<*>
        assertTrue(retiring.contains(worker.thread))
        f.transport.awaitTermination()
        assertFalse(worker.thread.isAlive)
        assertEquals(false, aliveAtRelease)
        assertNull(aux(f.transport))
    }

    @Test fun `quit on the auxiliary worker's own thread does not join itself`() {
        lateinit var f: Fixture
        val worker = ThreadWorker { f.transport.quit() }
        f = fixture(auxWorker = worker)
        worker.release.countDown()
        worker.thread.join(5000)
        assertFalse("quit deadlocked on its own join", worker.thread.isAlive)
    }

    @Test fun `after quit no auxiliary lane is built even when enabled`() {
        val f = fixture(auxEnabled = true)
        f.transport.quit()
        assertFalse(f.transport.dispatchVideo(message(Channel.ID_VID2)))
        assertNull(aux(f.transport))
    }

    @Test fun `a hole is posted to the lane of its own channel only`() {
        val mainWorker = FakeLaneWorker()
        val auxWorker = FakeLaneWorker()
        val f = fixture(mainWorker = mainWorker, auxWorker = auxWorker)
        f.transport.dispatchVideoRunHoled(Channel.ID_VID2, true)
        assertEquals(1, auxWorker.jobs.size)
        assertTrue(mainWorker.jobs.isEmpty())
        auxWorker.jobs.single().run()
        verify(f.auxVideo).onFragmentRunHoled(true)
        verify(f.mainVideo, never()).onFragmentRunHoled(any())
        f.transport.dispatchVideoRunHoled(Channel.ID_VID, true)
        assertEquals(1, mainWorker.jobs.size)
        assertEquals(1, auxWorker.jobs.size)
        f.transport.dispatchVideoRunHoled(Channel.ID_INP, true)
        assertEquals(1, mainWorker.jobs.size)
        assertEquals(1, auxWorker.jobs.size)
    }

    @Test fun `an auxiliary shed does not move the main lane's counters`() {
        val f = fixture(auxEnabled = true, auxWorker = FakeLaneWorker())
        repeat(VideoLane.VIDEO_BACKLOG_LIMIT + 1) { f.transport.dispatchVideo(message(Channel.ID_VID2)) }
        assertEquals(0L, f.transport.videoShedCount())
        assertEquals(0, f.transport.videoQueueDepth())
    }
}
