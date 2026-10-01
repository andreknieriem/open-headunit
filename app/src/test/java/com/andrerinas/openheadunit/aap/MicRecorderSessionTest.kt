package com.andrerinas.openheadunit.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioRecord
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Recorder.start, SCO receiver and capture thread wired to the real session controller. */
class MicRecorderSessionTest {
    private class Fixture(var source: Int = MicRecorder.SOURCE_BLUETOOTH_SCO) : AutoCloseable {
        val work = ConcurrentLinkedQueue<() -> Unit>()
        val sends = ConcurrentLinkedQueue<() -> Unit>()
        val responses = mutableListOf<Pair<Int, Boolean>>()
        val reports = mutableListOf<MicUplinkMonitor.Report>()
        val receivers = mutableListOf<BroadcastReceiver>()
        val context = mock<Context>()
        val audio = mock<AudioManager>()
        val prefs = mock<SharedPreferences>()
        val claim = mock<MicRecorder.ForegroundMicrophoneClaim>()
        val previousClaim = MicRecorder.foregroundClaim
        val errors = AtomicInteger()
        val failure = CountDownLatch(1)
        var initialized = true
        var readError: Int? = null
        var throwRead = false
        var delayFirstRead = false
        val firstReadEntered = CountDownLatch(1)
        val lateFirstReturn = CountDownLatch(1)
        val reads = AtomicInteger()
        val captureThreads = ConcurrentLinkedQueue<Thread>()
        private val resources = mutableListOf<AutoCloseable>()
        private val level = AppLog::class.java.getDeclaredField("cachedLogLevel").apply { isAccessible = true }
        private val oldLevel = level.getInt(null)
        val records: org.mockito.MockedConstruction<AudioRecord>
        val recorder: MicRecorder
        val controller: MicSessionController
        init {
            level.setInt(null, Int.MAX_VALUE)
            resources.add(Mockito.mockStatic(SystemClock::class.java))
            val permission = Mockito.mockStatic(PermissionChecker::class.java)
            resources.add(permission)
            permission.`when`<Int> { PermissionChecker.checkSelfPermission(any(), any()) }.thenReturn(PermissionChecker.PERMISSION_GRANTED)
            val minimum = Mockito.mockStatic(AudioRecord::class.java)
            resources.add(minimum)
            minimum.`when`<Int> { AudioRecord.getMinBufferSize(any(), any(), any()) }.thenReturn(4096)
            resources.add(Mockito.mockConstruction(IntentFilter::class.java))
            val compat = Mockito.mockStatic(ContextCompat::class.java)
            resources.add(compat)
            compat.`when`<Intent?> { ContextCompat.registerReceiver(any(), any(), any(), any<Int>()) }
                .thenAnswer { receivers.add(it.arguments[1] as BroadcastReceiver); null }
            whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
            whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audio)
            whenever(prefs.getInt(any(), any())).thenAnswer {
                if (it.arguments[0] == "mic-input-source") source else it.arguments[1]
            }
            whenever(claim.claim()).thenReturn(true)
            MicRecorder.foregroundClaim = claim
            records = Mockito.mockConstruction(AudioRecord::class.java) { record, construction ->
                whenever(record.state).thenReturn(if (initialized) AudioRecord.STATE_INITIALIZED else AudioRecord.STATE_UNINITIALIZED)
                val released = CountDownLatch(1)
                doAnswer { released.countDown(); null }.whenever(record).stop()
                doAnswer { released.countDown(); null }.whenever(record).release()
                whenever(record.read(any<ByteArray>(), any(), any())).thenAnswer {
                    captureThreads.add(Thread.currentThread())
                    reads.incrementAndGet()
                    if (construction.count == 1) firstReadEntered.countDown()
                    if (throwRead) throw IllegalStateException("native read failure")
                    readError ?: run {
                        // Native read can outlive interrupt; release is what unblocks it.
                        while (released.count != 0L) {
                            try { released.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                        }
                        if (construction.count == 1 && delayFirstRead) {
                            while (lateFirstReturn.count != 0L) {
                                try { lateFirstReturn.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                            }
                        }
                        -6
                    }
                }
            }
            resources.add(records)
            recorder = MicRecorder(context) { work.add(it) }
            controller = connect(recorder)
        }
        fun connect(target: MicRecorder) = MicSessionController(
                lifecycle = { work.add(it) }, sending = { sends.add(it) },
                startCapture = { callbacks ->
                    target.listener = object : MicRecorder.Listener {
                        override fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int, peak: Int) =
                            callbacks.data(mic_buf, mic_audio_len, peak)
                        override fun onMicCaptureFailed(error: Int) {
                            callbacks.failed(); errors.incrementAndGet(); failure.countDown()
                        }
                        override fun isCurrent() = callbacks.isCurrent()
                    }
                    target.start()
                }, stopCapture = { target.stop() },
                response = { id, ok -> sends.add { responses.add(id to ok) } },
                data = { _, _ -> }, clockMs = { 1000 }, timestampUs = { 1000000 }, report = { reports.add(it) })
        fun workAll() { while (true) (work.poll() ?: break).invoke() }
        fun sendAll() { while (true) (sends.poll() ?: break).invoke() }
        fun open() { controller.open(2); workAll(); sendAll() }
        fun queueState(state: Int, receiver: BroadcastReceiver = receivers.last(), previous: Int = -1) {
            val intent = mock<Intent>()
            whenever(intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)).thenReturn(state)
            whenever(intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -1)).thenReturn(previous)
            receiver.onReceive(context, intent)
        }
        fun state(state: Int, receiver: BroadcastReceiver = receivers.last(), previous: Int = -1) {
            queueState(state, receiver, previous); workAll(); sendAll()
        }
        override fun close() {
            lateFirstReturn.countDown()
            controller.close(shutdown = true); workAll(); sendAll()
            // Let a released read observe retirement before restoring platform test doubles.
            captureThreads.distinct().forEach { it.join(3000) }
            MicRecorder.foregroundClaim = previousClaim
            resources.asReversed().forEach { it.close() }
            level.setInt(null, oldLevel)
        }
    }

    @Test fun `fatal read return and exception retire once and permit an actual new capture`() {
        for (exception in listOf(false, true)) Fixture(source = 1).use { f ->
            f.readError = -6; f.throwRead = exception
            f.open()
            assertTrue(f.failure.await(3, TimeUnit.SECONDS))
            f.workAll(); f.sendAll()
            assertEquals(1, f.reads.get())
            assertEquals(1, f.responses.count { it == (1 to false) })
            verify(f.records.constructed().single()).release()
            verify(f.claim).release()
            f.readError = null; f.throwRead = false
            f.open()
            assertEquals(2, f.records.constructed().size)
            assertTrue(f.responses.contains(2 to true))
        }
    }

    @Test fun `initial disconnected broadcast does not cancel the connection attempt`() = Fixture().use { f ->
        f.open()
        f.state(AudioManager.SCO_AUDIO_STATE_DISCONNECTED, previous = AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertEquals(0, f.errors.get())
        verify(f.context, never()).unregisterReceiver(any())
        f.state(AudioManager.SCO_AUDIO_STATE_CONNECTING)
        f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertEquals(1, f.records.constructed().size)
        verify(f.records.constructed().single()).startRecording()
    }

    @Test fun `async SCO initialization failure closes the run and a later open starts anew`() = Fixture().use { f ->
        f.initialized = false
        f.open(); f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertEquals(1, f.responses.count { it == (1 to false) })
        verify(f.records.constructed().single()).release()
        verify(f.claim).release()
        val old = f.receivers.single()
        f.initialized = true
        f.open(); f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        val size = f.records.constructed().size
        f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED, old)
        assertEquals(size, f.records.constructed().size)
        assertEquals(1, f.errors.get())
        assertTrue(f.responses.contains(2 to true))
    }

    @Test fun `actual SCO disconnect retires the logical run before reopening`() = Fixture().use { f ->
        f.open(); f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        val old = f.receivers.single()
        f.state(AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
        assertEquals(1, f.responses.count { it == (1 to false) })
        f.open(); f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertEquals(2, f.records.constructed().size)
        val current = f.records.constructed().last()
        f.state(AudioManager.SCO_AUDIO_STATE_DISCONNECTED, old)
        verify(current, never()).stop()
        verify(f.claim, times(1)).release()
    }

    @Test fun `partial SCO startup and cleanup exceptions still retire the receiver and mode`() {
        for (failAtFlag in listOf(false, true)) Fixture().use { f ->
            if (failAtFlag) doThrow(SecurityException("route failed")).whenever(f.audio).isBluetoothScoOn = true
            else doThrow(SecurityException("start failed")).whenever(f.audio).startBluetoothSco()
            doThrow(SecurityException("stop failed")).whenever(f.audio).stopBluetoothSco()
            doThrow(SecurityException("flag cleanup failed")).whenever(f.audio).isBluetoothScoOn = false
            doThrow(SecurityException("unregister failed")).whenever(f.context).unregisterReceiver(any())
            f.open()
            val old = f.receivers.single()
            verify(f.context).unregisterReceiver(old)
            verify(f.audio).mode = AudioManager.MODE_NORMAL
            verify(f.claim).release()
            assertEquals(listOf(1 to false), f.responses)
            f.controller.close(shutdown = true); f.workAll()
            f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED, old)
            assertTrue(f.records.constructed().isEmpty())
        }
    }
    @Test fun `retired read error cannot close the replacement capture`() = Fixture(source = 1).use { f ->
        f.delayFirstRead = true
        f.open()
        assertTrue(f.firstReadEntered.await(3, TimeUnit.SECONDS))
        val oldThread = f.captureThreads.single()
        f.controller.close(reply = true); f.workAll()
        f.open()
        val current = f.records.constructed().last()
        f.lateFirstReturn.countDown()
        oldThread.join(3000)
        assertFalse("retired read did not finish", oldThread.isAlive)
        f.workAll(); f.sendAll()
        verify(current, never()).stop()
        verify(f.claim, times(1)).release()
        assertEquals(0, f.errors.get())
        assertTrue(f.responses.contains(2 to true))
    }

    @Test fun `queued connected callback cannot open capture after poll side close`() = Fixture().use { f ->
        f.open()
        f.queueState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        f.controller.close()
        f.workAll(); f.sendAll()
        assertTrue(f.records.constructed().isEmpty())
    }

    @Test fun `late old callback and repeated cleanup preserve a new transport recorder`() = Fixture().use { f ->
        f.open()
        val old = f.receivers.single()
        f.controller.close(shutdown = true); f.workAll()
        val replacement = MicRecorder(f.context) { f.work.add(it) }
        val next = f.connect(replacement)
        try {
            next.open(2); f.workAll()
            f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            val current = f.records.constructed().single()
            assertTrue(MicRecorder.holdsCommunicationMode)
            clearInvocations(f.audio)
            f.recorder.stop()
            f.state(AudioManager.SCO_AUDIO_STATE_CONNECTED, old)
            verify(current, never()).stop()
            verify(f.audio, never()).mode = AudioManager.MODE_NORMAL
            verify(f.claim, times(1)).release()
            assertTrue(MicRecorder.holdsCommunicationMode)
        } finally { next.close(shutdown = true); f.workAll() }
    }

}
