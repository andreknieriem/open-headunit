package com.andrerinas.openheadunit.decoder.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.aap.protocol.MicCaptureFormat
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

class MicRecorder(
    private val context: Context,
    private val enqueueLifecycle: (() -> Unit) -> Unit = ::postLifecycle
) {

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val settings = Settings(context)

    /**
     * What the hardware is opened at. Normally [MicCaptureFormat.SAMPLE_RATE_HZ], which is also the
     * only rate the phone is ever told about; a device that refuses it captures higher and is
     * decimated by its capture worker before anything leaves here.
     */
    private val captureRateHz: Int
    private val micBufferSize: Int

    private val decimationFactor: Int

    // Indicates whether mic recording is available on this device
    val isAvailable: Boolean

    init {
        val decision = MicCaptureRatePolicy.decide(settings.micSampleRate) { rate ->
            AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }
        if (decision == null) {
            // Named in the user's terms because it is the one situation the rate setting exists for,
            // and because no reporter log has ever shown it.
            AppLog.w("MicRecorder: this device will not open ${MicCaptureFormat.SAMPLE_RATE_HZ} Hz " +
                "mono capture, which is the only rate Android Auto accepts, and no whole multiple " +
                "of it either. The microphone is unavailable")
            captureRateHz = MicCaptureFormat.SAMPLE_RATE_HZ
            micBufferSize = 0
            decimationFactor = 1
            isAvailable = false
        } else {
            captureRateHz = decision.captureRateHz
            // Room for two whole messages, so assembling one never becomes the reason a read
            // overruns. The minimum the device asks for is often less than half of that.
            val twoChunks = 2 * MicCaptureFormat.CHUNK_BYTES * decision.decimationFactor
            micBufferSize = maxOf(decision.minBufferSize, twoChunks)
            decimationFactor = decision.decimationFactor
            if (!decision.isDirect) {
                AppLog.w("MicRecorder: capturing at $captureRateHz Hz and converting ${decimationFactor}:1")
            }
            isAvailable = true
        }
    }

    /** Everything a late read or conversion can mutate belongs to its capture run. */
    private class CaptureRun(val record: AudioRecord, val source: Int, size: Int,
                             factor: Int, val listener: Listener?) {
        val buffer = ByteArray(size)
        val converter = if (factor == 1) null else MicPcmDecimator(factor)
        val wireBuffer = converter?.let { ByteArray(it.outputCapacity(size)) } ?: ByteArray(0)
        lateinit var thread: Thread
        val startedMs = SystemClock.elapsedRealtime()
        var bytes = 0L
        var emptyReads = 0
        var peak = 0
    }
    @Volatile private var activeCapture: CaptureRun? = null
    var listener: Listener? = null
    private var claimedForeground: ForegroundMicrophoneClaim? = null

    // Tracks whether this instance started Bluetooth SCO so we can clean it up
    private var bluetoothScoStarted = false
    private var scoReceiver: BroadcastReceiver? = null

    companion object {
        // One hardware lifecycle FIFO also spans transport replacement: old cleanup precedes reopen.
        private val lifecycle = java.util.concurrent.Executors.newSingleThreadExecutor { job ->
            Thread(job, "mic_lifecycle").apply { isDaemon = true }
        }
        internal fun postLifecycle(job: () -> Unit) { lifecycle.execute(job) }

        // Sentinel value stored in settings to indicate Bluetooth SCO mode
        const val SOURCE_BLUETOOTH_SCO = 100

        /**
         * True while the uplink is the one holding MODE_IN_COMMUNICATION for SCO routing.
         *
         * Read by anything that infers a phone call from the audio mode, so our own microphone
         * does not look like one.
         */
        @Volatile
        var holdsCommunicationMode = false
            private set

        /** RECORD_AUDIO is missing, or a ROM has revoked its app-op. */
        const val ERROR_NO_PERMISSION = -3

        /** No usable capture configuration on this device. */
        const val ERROR_UNAVAILABLE = -4

        /** AudioRecord would not initialise or start. */
        const val ERROR_RECORDER_FAILED = -5

        /** The foreground service could not claim the microphone type, so capture must not open. */
        const val ERROR_NO_FOREGROUND_TYPE = -6

        /**
         * How capture asks for the microphone foreground-service type, which Android 14 will not
         * grant at service start.
         *
         * Held here rather than passed in because the service is a singleton and a [MicRecorder]
         * is not: transports come and go within one service. Null outside a running service, where
         * there is nothing to claim and capture proceeds as it always did.
         */
        @Volatile
        @JvmStatic
        var foregroundClaim: ForegroundMicrophoneClaim? = null
    }

    /** Implemented by the foreground service, which is the only thing that can call startForeground. */
    interface ForegroundMicrophoneClaim {

        /** Adds the microphone type. False means it was refused and capture must not open. */
        fun claim(): Boolean

        /** Drops it again, so it is held only while capture is running. */
        fun release()
    }

    interface Listener {
        /**
         * One read from the microphone. [peak] is this read's loudest sample, already measured
         * here so the transport does not scan the same bytes a second time.
         */
        fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int, peak: Int)
        /** Bound to the original logical session; a late failure must never close its replacement. */
        fun onMicCaptureFailed(error: Int) {}
        fun isCurrent(): Boolean = true
    }

    /** Called only by the microphone lifecycle worker; poll-side DATA retirement happens first. */
    fun stop() {
        val run = activeCapture
        activeCapture = null
        run?.thread?.interrupt()
        if (run != null) logCaptureSummary(run)
        val record = audioRecord
        audioRecord = null
        cleanup("AudioRecord stop") { record?.stop() }
        cleanup("AudioRecord release") { record?.release() }
        val oldAec = aec; aec = null
        val oldNs = ns; ns = null
        val oldAgc = agc; agc = null
        cleanup("AEC") { oldAec?.release() }
        cleanup("NS") { oldNs?.release() }
        cleanup("AGC") { oldAgc?.release() }
        // Receiver ownership can precede a failed startBluetoothSco/setCommunicationDevice call.
        val ownedSco = bluetoothScoStarted
        cleanupSco()
        if (ownedSco) holdsCommunicationMode = false
        val claim = claimedForeground
        claimedForeground = null
        cleanup("foreground claim") { claim?.release() }
    }

    private inline fun cleanup(name: String, release: () -> Unit) {
        try { release() } catch (e: Exception) { AppLog.e("MicRecorder: Error releasing $name", e) }
    }

    private fun logCaptureSummary(run: CaptureRun) {
        val elapsedMs = SystemClock.elapsedRealtime() - run.startedMs
        val expectedBytes = captureRateHz.toLong() * 2L * elapsedMs / 1000L
        val percent = if (expectedBytes > 0) run.bytes * 100L / expectedBytes else -1L
        AppLog.i("MicRecorder: capture summary | source=${getAudioSourceName(run.source)} (${run.source}) " +
            "rate=$captureRateHz elapsed=${elapsedMs}ms bytes=${run.bytes} " +
            "($percent% of expected) emptyReads=${run.emptyReads} peak=${run.peak}/32767")
    }

    private fun cleanupSco() {
        val receiver = scoReceiver
        scoReceiver = null // Invalidate callbacks even when unregister or route cleanup throws.
        val ownedRouting = bluetoothScoStarted
        bluetoothScoStarted = false
        cleanup("SCO receiver") { receiver?.let { context.unregisterReceiver(it) } }
        if (!ownedRouting) return
        cleanup("SCO routing") {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                cleanup("communication device") { audioManager.clearCommunicationDevice() }
            } else {
                cleanup("Bluetooth SCO") { audioManager.stopBluetoothSco() }
                cleanup("SCO routing flag") {
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                }
            }
            cleanup("audio mode") { audioManager.mode = AudioManager.MODE_NORMAL }
        }
    }

    private fun micAudioRead(run: CaptureRun): Int {
        val len = run.record.read(run.buffer, 0, run.buffer.size)
        if (activeCapture !== run) return 0
        if (len <= 0) {
            if (len == 0) run.emptyReads++
            return len
        }
        run.bytes += len
        val converter = run.converter
        val bytes = if (converter == null) run.buffer else run.wireBuffer
        val wireLen = converter?.decimate(run.buffer, len, bytes) ?: len
        if (wireLen > 0) {
            val peak = peakAmplitude(bytes, wireLen)
            run.peak = maxOf(run.peak, peak)
            run.listener?.onMicDataAvailable(bytes, wireLen, peak)
        }
        return len
    }

    /**
     * Loudest sample in this read, as a 16-bit magnitude.
     *
     * What separates a microphone routed nowhere from a working one: both deliver bytes at the
     * expected rate, but a dead input delivers zeros. Scanned every fourth frame, since this runs
     * on the capture thread and a peak survives that.
     */
    private fun peakAmplitude(buf: ByteArray, len: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            i += 8
        }
        return peak
    }

    private fun getAudioSource(index: Int): Int {
        return when (index) {
            0 -> MediaRecorder.AudioSource.DEFAULT
            1 -> MediaRecorder.AudioSource.MIC
            2 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            3 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            4, SOURCE_BLUETOOTH_SCO -> SOURCE_BLUETOOTH_SCO
            else -> MediaRecorder.AudioSource.DEFAULT
        }
    }

    fun start(): Int {
        if (!isAvailable) {
            AppLog.w("MicRecorder: Cannot start, mic not available on this device")
            return ERROR_UNAVAILABLE
        }

        // Which of the two failed matters: a denied permission is fixable in this app's settings,
        // a revoked app-op is not and lives in the ROM's own privacy screen. One reporter chased a
        // granted permission for weeks because this line named only the first.
        val permission = PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (permission != PermissionChecker.PERMISSION_GRANTED) {
            if (permission == PermissionChecker.PERMISSION_DENIED_APP_OP) {
                AppLog.e("MicRecorder: RECORD_AUDIO is granted but this ROM has revoked the " +
                    "microphone app-op; it has to be re-enabled in the system's own privacy settings")
            } else {
                AppLog.e("MicRecorder: No RECORD_AUDIO permission")
            }
            return ERROR_NO_PERMISSION
        }

        // Android 14 refuses the microphone foreground-service type to a service started in the
        // background, so it is claimed here instead, where the projection is on screen. Declining
        // the phone's request is the right answer to a refusal; capturing without the type is not.
        val claim = foregroundClaim
        if (claim != null && !claim.claim()) return ERROR_NO_FOREGROUND_TYPE
        claimedForeground = claim

        val configuredSource = getAudioSource(settings.micInputSource)

        return if (configuredSource == SOURCE_BLUETOOTH_SCO) {
            startScoAndRecord()
        } else {
            startRecording(configuredSource)
        }
    }

    private fun startScoAndRecord(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Check for BLUETOOTH_CONNECT permission on Android 12+ (API 31+)
        val hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionChecker.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PermissionChecker.PERMISSION_GRANTED
        } else {
            true
        }

        // Own cleanup before the first external mutation, including partially failed setup.
        bluetoothScoStarted = true
        val attemptListener = listener
        // Set audio mode to MODE_IN_COMMUNICATION to force SCO routing
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            holdsCommunicationMode = true
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Failed to set audio mode to MODE_IN_COMMUNICATION", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBluetoothPermission) {
            AppLog.i("MicRecorder: API 31+. Using setCommunicationDevice for Bluetooth routing.")
            val devices = audioManager.availableCommunicationDevices
            val bluetoothDevice = devices.find { 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (bluetoothDevice != null) {
                val success = audioManager.setCommunicationDevice(bluetoothDevice)
                AppLog.i("MicRecorder: setCommunicationDevice result: $success for device: ${bluetoothDevice.productName} (${bluetoothDevice.type})")
            } else {
                AppLog.w("MicRecorder: No Bluetooth SCO/BLE headset found in available communication devices.")
            }
            // On API 31+, we can start recording directly on the communication channel
            val result = startRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            return result
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                AppLog.w("MicRecorder: Missing BLUETOOTH_CONNECT permission on API 31+. Falling back to legacy SCO.")
            }
            // Legacy path (API < 31 or missing BLUETOOTH_CONNECT permission on API 31+)
            // 1. Listen for SCO connection state
            scoReceiver = object : BroadcastReceiver() {
                private var connectingOrConnected = false
                override fun onReceive(context: Context, intent: Intent) {
                    val receiver = this
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    enqueueLifecycle {
                        if (scoReceiver !== receiver || attemptListener?.isCurrent() == false) return@enqueueLifecycle
                        when (state) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTING -> connectingOrConnected = true
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                connectingOrConnected = true
                                val result = startRecording(MediaRecorder.AudioSource.MIC)
                                if (result != 0) attemptListener?.onMicCaptureFailed(result)
                            }
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                                // Registration may deliver the initial sticky DISCONNECTED state.
                                if (connectingOrConnected) {
                                    attemptListener?.onMicCaptureFailed(ERROR_RECORDER_FAILED)
                                }
                            }
                        }
                    }
                }
            }
            
            ContextCompat.registerReceiver(context, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)
            
            // 2. Start SCO
            AppLog.i("MicRecorder: Starting Bluetooth SCO...")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            // Capture starts inside the receiver once SCO connects, so all this path can report is
            // that the link was asked for.
            return 0
        }
    }

    /** Returns 0 once capture is running, or [ERROR_RECORDER_FAILED] if it never started. */
    private fun startRecording(source: Int): Int {
        try {
            if (audioRecord != null) return 0 // Already recording
            
            AppLog.i("MicRecorder: Initializing AudioRecord with source: ${getAudioSourceName(source)} ($source), SampleRate: $captureRateHz, BufferSize: $micBufferSize")
            audioRecord = AudioRecord(source, captureRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, micBufferSize)
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.e("MicRecorder: Failed to initialize AudioRecord")
                return ERROR_RECORDER_FAILED
            }
            
            val audioSessionId = audioRecord?.audioSessionId ?: 0
            if (audioSessionId != 0) {
                try {
                    if (settings.micNoiseSuppressor && NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(audioSessionId)
                        ns?.enabled = true
                        AppLog.i("MicRecorder: NoiseSuppressor: ${if (ns?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micNoiseSuppressor) {
                        AppLog.i("MicRecorder: NoiseSuppressor: Unsupported on this device")
                    }
                    
                    if (settings.micAutoGainControl && AutomaticGainControl.isAvailable()) {
                        agc = AutomaticGainControl.create(audioSessionId)
                        agc?.enabled = true
                        AppLog.i("MicRecorder: AutomaticGainControl: ${if (agc?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micAutoGainControl) {
                        AppLog.i("MicRecorder: AutomaticGainControl: Unsupported on this device")
                    }
                    
                    if (settings.micEchoCanceler && AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(audioSessionId)
                        aec?.enabled = true
                        AppLog.i("MicRecorder: AcousticEchoCanceler: ${if (aec?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micEchoCanceler) {
                        AppLog.i("MicRecorder: AcousticEchoCanceler: Unsupported on this device")
                    }
                } catch (e: Exception) {
                    AppLog.e("MicRecorder: Error initializing AudioFX", e)
                }
            }
            
            audioRecord?.startRecording()

            val run = CaptureRun(audioRecord!!, source, micBufferSize, decimationFactor, listener)
            run.thread = Thread({
                requestAudioThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                try {
                    while (activeCapture === run && run.listener?.isCurrent() != false) {
                        val result = micAudioRead(run)
                        if (result < 0) {
                            if (activeCapture === run) {
                                AppLog.e("MicRecorder: terminal capture read error $result")
                                run.listener?.onMicCaptureFailed(result)
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (activeCapture === run) {
                        AppLog.e("MicRecorder: capture read failed", e)
                        run.listener?.onMicCaptureFailed(ERROR_RECORDER_FAILED)
                    }
                }
            }, "mic_audio")
            activeCapture = run
            run.thread.start()

            return 0
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Error during startRecording", e)
            return ERROR_RECORDER_FAILED
        }
    }

    private fun getAudioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            SOURCE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            else -> "UNKNOWN ($source)"
        }
    }
}
