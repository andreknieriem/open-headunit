package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.aap.GalRuntimeOptions
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.video.VideoDecoder.CodecType
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class VideoTimedSoftwareQueueTest {
    private class NonWaitingQueue(capacity: Int) : ArrayBlockingQueue<Any>(capacity) {
        var pollBeforeRemoval = false
        var inFlight: Any? = null

        override fun offer(element: Any, timeout: Long, unit: TimeUnit): Boolean =
            error("timed FFmpeg enqueue must not wait for the worker")

        override fun remove(element: Any?): Boolean {
            if (pollBeforeRemoval) inFlight = poll()
            return super.remove(element)
        }
    }

    private class Fixture(capacity: Int, val type: CodecType = CodecType.H264) {
        val queue = NonWaitingQueue(capacity)
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 }, elapsedRealtime = { 2_000L })
        val drops: Long get() = decoderField("framesDropped").getLong(decoder)
        private fun decoderField(name: String) = VideoDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }
        val pool: Collection<*> get() = decoderField("framePool").get(decoder) as Collection<*>

        init {
            val controller = GalPresentationController()
            controller.start(Channel.ID_VID, 1,
                GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(100_000_000, 250_000_000)))
            decoderField("running").set(decoder, true)
            decoderField("currentCodecType").set(decoder, type)
            decoderField("frameQueue").set(decoder, queue)
            decoderField("softwareVideoDecoder").set(decoder, mock<FfmpegVideoDecoder>())
            decoderField("outputTiming").set(decoder, GalVideoOutputTiming(controller, 1))
        }

        fun send(unit: ByteArray) {
            val receiveBuffer = ByteArray(unit.size + 3)
            unit.copyInto(receiveBuffer, destinationOffset = 3)
            decoder.decode(receiveBuffer, 3, unit.size, forceSoftware = true, announcedCodec = type)
            receiveBuffer.fill(0x7f) // The video dispatcher recycles transport storage after decode returns.
        }

        fun bytes(frame: Any): List<Byte> {
            val size = frame.javaClass.getDeclaredField("size").apply { isAccessible = true }.getInt(frame)
            val data = frame.javaClass.getDeclaredField("data").apply { isAccessible = true }.get(frame) as ByteArray
            return data.take(size)
        }

        fun queued() = queue.map(::bytes)
    }

    private fun avc(header: Int, tag: Int) = byteArrayOf(0, 0, 0, 1, header.toByte(), tag.toByte())
    private fun annex(hex: String) = byteArrayOf(0, 0, 0, 1) +
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hevc(kind: Int, tag: Int) = byteArrayOf(0, 0, 1, (kind shl 1).toByte(), 1, tag.toByte())

    @Test fun `startup burst keeps separate configuration and admits an IDR in bounded FIFO order`() {
        val f = Fixture(3)
        val config = annex("6742c01fda0320f684000003000400000300f03c60ca80") + annex("68ce0f2c80")
        val first = avc(0x41, 2)
        f.send(config)
        f.send(first)
        f.send(avc(0x41, 3))
        val evicted = f.queue.last()
        val keyframe = avc(0x65, 4)
        f.send(keyframe)
        assertEquals(listOf(config.toList(), first.toList(), keyframe.toList()), f.queued())
        assertEquals(1L, f.drops)
        assertTrue(f.pool.contains(evicted))
    }

    @Test fun `sustained overload protects queued config and can admit recovery across repeated full queues`() {
        val f = Fixture(2)
        val mixed = avc(0x68, 0xC0) + avc(0x41, 2)
        val first = avc(0x41, 1)
        f.send(first)
        f.send(mixed)
        f.send(avc(0x65, 3))
        assertEquals(listOf(first.toList(), mixed.toList()), f.queued())
        assertEquals(1L, f.drops)

        var nextConsumed = first.toList()
        repeat(4) { iteration ->
            // Consume one older unit, then fill the freed slot with a delta before the IDR arrives.
            assertEquals(nextConsumed, f.bytes(f.queue.remove()))
            val kept = f.bytes(f.queue.element())
            f.send(avc(0x41, iteration + 10))
            val recovery = avc(0x65, iteration + 20)
            f.send(recovery)
            f.send(avc(0x41, iteration + 30)) // must not displace the accepted IDR
            assertEquals(listOf(kept, recovery.toList()), f.queued())
            nextConsumed = kept
        }
        assertEquals(9L, f.drops)
    }

    @Test fun `HEVC CRA cannot displace a queued reference but a base layer IDR can`() {
        val f = Fixture(1, CodecType.H265)
        val reference = hevc(1, 10)
        f.send(reference)
        f.send(hevc(21, 11))
        assertEquals(listOf(reference.toList()), f.queued())
        val idr = hevc(34, 0xC0) + hevc(20, 12)
        f.send(idr)
        assertEquals(listOf(idr.toList()), f.queued())
        assertEquals(2L, f.drops)
    }

    @Test fun `dequeue racing an IDR never recycles the consumer's frame`() {
        val f = Fixture(1)
        val reference = avc(0x41, 10)
        f.send(reference)
        val inFlight = f.queue.element()
        f.queue.pollBeforeRemoval = true
        val idr = avc(0x65, 11)
        f.send(idr)
        assertSame(inFlight, f.queue.inFlight)
        assertEquals(reference.toList(), f.bytes(inFlight))
        assertFalse(f.pool.contains(inFlight))
        assertEquals(listOf(idr.toList()), f.queued())
        assertEquals(0L, f.drops)
    }
}
