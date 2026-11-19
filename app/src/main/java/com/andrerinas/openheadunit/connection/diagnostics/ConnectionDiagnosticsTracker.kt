package com.andrerinas.openheadunit.connection.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide tracker for connection pipeline stages, structured [ClassifiedConnectionError]
 * instances, and privacy-safe [DiagnosticsSnapshot] generation.
 */
object ConnectionDiagnosticsTracker {

    enum class StageState(val label: String) {
        NOT_STARTED("Not Started"),
        IN_PROGRESS("In Progress"),
        SUCCESS("Success"),
        CONNECTED("Connected"),
        INITIALIZED("Initialized"),
        STREAMING("Streaming"),
        FAILED("Failed"),
    }

    private val lock = Any()

    private var attemptStartedAtElapsedMs: Long = 0L
    private var attemptEndedAtElapsedMs: Long = 0L
    private var connectionModeLabel: String = "Wireless"
    private var connectionStateLabel: String = "Disconnected"
    private var discoveryState: StageState = StageState.NOT_STARTED
    private var transportState: StageState = StageState.NOT_STARTED
    private var tlsState: StageState = StageState.NOT_STARTED
    private var aapNegotiationState: StageState = StageState.NOT_STARTED
    private var decoderState: StageState = StageState.NOT_STARTED

    private var wifiConnected: Boolean = false
    private var sameSubnet: Boolean = false
    private var clientIsolationDetected: Boolean = false

    private val _latestError = MutableStateFlow<ClassifiedConnectionError?>(null)
    val latestError: StateFlow<ClassifiedConnectionError?> = _latestError.asStateFlow()

    private val _latestSnapshot = MutableStateFlow(buildSnapshotLocked(nowElapsedMs = 0L))
    val latestSnapshot: StateFlow<DiagnosticsSnapshot> = _latestSnapshot.asStateFlow()

    var clock: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    fun beginAttempt(mode: String = "Wireless") = synchronized(lock) {
        val now = clock()
        attemptStartedAtElapsedMs = now
        attemptEndedAtElapsedMs = 0L
        connectionModeLabel = mode
        connectionStateLabel = "Connecting"
        discoveryState = StageState.IN_PROGRESS
        transportState = StageState.NOT_STARTED
        tlsState = StageState.NOT_STARTED
        aapNegotiationState = StageState.NOT_STARTED
        decoderState = StageState.NOT_STARTED
        clientIsolationDetected = false
        _latestSnapshot.value = buildSnapshotLocked(now)
    }

    fun noteDiscoveryResult(
        found: Boolean,
        wifiConnected: Boolean = this.wifiConnected,
        sameSubnet: Boolean = this.sameSubnet,
        clientIsolation: Boolean = false,
    ) = synchronized(lock) {
        val now = clock()
        if (attemptStartedAtElapsedMs == 0L) attemptStartedAtElapsedMs = now
        this.wifiConnected = wifiConnected
        this.sameSubnet = sameSubnet
        this.clientIsolationDetected = clientIsolation
        discoveryState = if (found) StageState.SUCCESS else StageState.FAILED
        _latestSnapshot.value = buildSnapshotLocked(now)
    }

    fun noteNetworkContext(
        wifiConnected: Boolean,
        sameSubnet: Boolean,
        clientIsolation: Boolean = this.clientIsolationDetected,
    ) = synchronized(lock) {
        this.wifiConnected = wifiConnected
        this.sameSubnet = sameSubnet
        this.clientIsolationDetected = clientIsolation
        _latestSnapshot.value = buildSnapshotLocked(clock())
    }

    fun noteTransportConnected(mode: String? = null) = synchronized(lock) {
        val now = clock()
        if (attemptStartedAtElapsedMs == 0L) attemptStartedAtElapsedMs = now
        if (mode != null) connectionModeLabel = mode
        connectionStateLabel = "Connected"
        discoveryState = StageState.SUCCESS
        transportState = StageState.CONNECTED
        _latestSnapshot.value = buildSnapshotLocked(now)
    }

    fun noteTlsSucceeded() = synchronized(lock) {
        val now = clock()
        discoveryState = StageState.SUCCESS
        transportState = StageState.CONNECTED
        tlsState = StageState.SUCCESS
        aapNegotiationState = StageState.IN_PROGRESS
        _latestSnapshot.value = buildSnapshotLocked(now)
    }

    fun noteAapNegotiationSucceeded() = synchronized(lock) {
        val now = clock()
        discoveryState = StageState.SUCCESS
        transportState = StageState.CONNECTED
        tlsState = StageState.SUCCESS
        aapNegotiationState = StageState.SUCCESS
        connectionStateLabel = "Connected"
        _latestError.value = null
        _latestSnapshot.value = buildSnapshotLocked(now)
    }

    fun noteDecoderInitialized() = synchronized(lock) {
        val now = clock()
        decoderState = StageState.INITIALIZED
        _latestSnapshot.value = buildSnapshotLocked(now)
    }

    fun noteFirstVideoFrameRendered() = synchronized(lock) {
        val now = clock()
        decoderState = StageState.STREAMING
        connectionStateLabel = "Projecting"
        _latestError.value = null
        _latestSnapshot.value = buildSnapshotLocked(now)
    }

    /**
     * Classifies and records a structured error, updating the corresponding stage status and
     * logging both the user-friendly error code/suggestion and the underlying debug exception.
     */
    fun recordError(
        rawCause: String? = null,
        throwable: Throwable? = null,
        handshakeFailure: AapTransport.HandshakeFailure? = null,
        endpoint: String? = null,
        mode: String? = null,
        wifiConnected: Boolean? = null,
        sameSubnet: Boolean? = null,
        clientIsolationSuspected: Boolean = false,
        reachedHandshake: Boolean = false,
        renderedAnyFrame: Boolean = false,
    ): ClassifiedConnectionError = synchronized(lock) {
        val now = clock()
        if (attemptStartedAtElapsedMs == 0L) attemptStartedAtElapsedMs = now
        attemptEndedAtElapsedMs = now
        if (mode != null) connectionModeLabel = mode
        if (wifiConnected != null) this.wifiConnected = wifiConnected
        if (sameSubnet != null) this.sameSubnet = sameSubnet
        if (clientIsolationSuspected) this.clientIsolationDetected = true

        val classified = ConnectionErrorClassifier.classify(
            rawCause = rawCause,
            throwable = throwable,
            handshakeFailure = handshakeFailure,
            endpoint = endpoint,
            wifiConnected = this.wifiConnected,
            sameSubnet = this.sameSubnet,
            clientIsolationSuspected = this.clientIsolationDetected,
            reachedHandshake = reachedHandshake,
            renderedAnyFrame = renderedAnyFrame,
            occurredAtElapsedMs = now,
        )

        connectionStateLabel = "Failed"
        when (classified.code) {
            ConnectionErrorCode.PHONE_NOT_FOUND -> {
                discoveryState = StageState.FAILED
                transportState = StageState.NOT_STARTED
                tlsState = StageState.NOT_STARTED
                aapNegotiationState = StageState.NOT_STARTED
            }
            ConnectionErrorCode.WIFI_CLIENT_ISOLATION -> {
                clientIsolationDetected = true
                discoveryState = StageState.FAILED
                transportState = StageState.FAILED
                tlsState = StageState.NOT_STARTED
                aapNegotiationState = StageState.NOT_STARTED
            }
            ConnectionErrorCode.PORT_UNREACHABLE,
            ConnectionErrorCode.HEADUNIT_SERVER_NOT_RUNNING -> {
                if (discoveryState == StageState.NOT_STARTED || discoveryState == StageState.IN_PROGRESS) {
                    discoveryState = StageState.SUCCESS
                }
                transportState = StageState.FAILED
                tlsState = StageState.NOT_STARTED
                aapNegotiationState = StageState.NOT_STARTED
            }
            ConnectionErrorCode.SOCKET_TIMEOUT -> {
                if (discoveryState == StageState.NOT_STARTED || discoveryState == StageState.IN_PROGRESS) {
                    discoveryState = StageState.SUCCESS
                }
                transportState = StageState.FAILED
            }
            ConnectionErrorCode.TLS_HANDSHAKE_FAILED -> {
                discoveryState = StageState.SUCCESS
                transportState = StageState.CONNECTED
                tlsState = StageState.FAILED
                aapNegotiationState = StageState.NOT_STARTED
            }
            ConnectionErrorCode.AAP_NEGOTIATION_FAILED -> {
                discoveryState = StageState.SUCCESS
                transportState = StageState.CONNECTED
                if (tlsState != StageState.SUCCESS) {
                    tlsState = StageState.NOT_STARTED
                }
                aapNegotiationState = StageState.FAILED
            }
            ConnectionErrorCode.DECODER_INIT_FAILED -> {
                discoveryState = StageState.SUCCESS
                transportState = StageState.CONNECTED
                tlsState = StageState.SUCCESS
                aapNegotiationState = StageState.SUCCESS
                decoderState = StageState.FAILED
            }
            ConnectionErrorCode.VIDEO_FIRST_FRAME_TIMEOUT -> {
                discoveryState = StageState.SUCCESS
                transportState = StageState.CONNECTED
                tlsState = StageState.SUCCESS
                aapNegotiationState = StageState.SUCCESS
                if (decoderState == StageState.NOT_STARTED) {
                    decoderState = StageState.INITIALIZED
                }
            }
        }

        _latestError.value = classified
        _latestSnapshot.value = buildSnapshotLocked(now)

        // Log structured error code + user-friendly description + troubleshooting at ERROR,
        // and preserve underlying exception details in DEBUG logs.
        try {
            val suggestion = classified.troubleshootingSuggestions.firstOrNull().orEmpty()
            AppLog.e(
                "Diagnostics [${classified.code.name}] (${classified.stage.displayName}): " +
                    "${classified.userMessage}" +
                    if (suggestion.isNotEmpty()) " | Troubleshooting: $suggestion" else ""
            )
            if (classified.technicalDetail != null || throwable != null) {
                if (throwable != null) {
                    AppLog.d("Diagnostics [${classified.code.name}] technical detail: ${classified.technicalDetail.orEmpty()}", throwable)
                } else {
                    AppLog.d("Diagnostics [${classified.code.name}] technical detail: ${classified.technicalDetail}")
                }
            }
        } catch (_: Throwable) {
            // Safe in pure JVM unit tests where Android Log is not mocked.
        }

        return classified
    }

    fun clearError() = synchronized(lock) {
        _latestError.value = null
        _latestSnapshot.value = buildSnapshotLocked(clock())
    }

    fun resetForTest() = synchronized(lock) {
        attemptStartedAtElapsedMs = 0L
        attemptEndedAtElapsedMs = 0L
        connectionModeLabel = "Wireless"
        connectionStateLabel = "Disconnected"
        discoveryState = StageState.NOT_STARTED
        transportState = StageState.NOT_STARTED
        tlsState = StageState.NOT_STARTED
        aapNegotiationState = StageState.NOT_STARTED
        decoderState = StageState.NOT_STARTED
        wifiConnected = false
        sameSubnet = false
        clientIsolationDetected = false
        _latestError.value = null
        _latestSnapshot.value = buildSnapshotLocked(0L)
    }

    fun currentSnapshot(context: Context? = null): DiagnosticsSnapshot = synchronized(lock) {
        if (context != null) {
            refreshNetworkStateFromContextLocked(context)
        }
        buildSnapshotLocked(clock())
    }

    fun exportFormattedDiagnostics(context: Context? = null): String =
        DiagnosticsExporter.format(currentSnapshot(context))

    private fun refreshNetworkStateFromContextLocked(context: Context) {
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val hasWifi = cm.allNetworks.any { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                }
                wifiConnected = hasWifi
            } else {
                @Suppress("DEPRECATION")
                wifiConnected = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
            }
        } catch (_: Throwable) {
        }
    }

    private fun buildSnapshotLocked(nowElapsedMs: Long): DiagnosticsSnapshot {
        val err = _latestError.value
        val endMs = if (attemptEndedAtElapsedMs > 0L) attemptEndedAtElapsedMs else nowElapsedMs
        val durationSec = if (attemptStartedAtElapsedMs > 0L && endMs >= attemptStartedAtElapsedMs) {
            (endMs - attemptStartedAtElapsedMs) / 1000.0
        } else {
            0.0
        }
        val versionName = try {
            BuildConfig.VERSION_NAME
        } catch (_: Throwable) {
            "Unknown"
        }
        val platformName = try {
            val release = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }
            if (release != null) "Android $release" else "Android"
        } catch (_: Throwable) {
            "Android"
        }
        return DiagnosticsSnapshot(
            appVersion = versionName,
            platform = platformName,
            connectionMode = connectionModeLabel,
            connectionState = connectionStateLabel,
            discoveryStatus = discoveryState.label,
            transportStatus = transportState.label,
            tlsStatus = tlsState.label,
            aapNegotiationStatus = aapNegotiationState.label,
            errorCode = err?.code,
            errorMessage = err?.shortMessage,
            sessionId = DiagnosticsSanitizer.REDACTED,
            wifiConnected = wifiConnected,
            sameSubnet = sameSubnet,
            clientIsolationDetected = clientIsolationDetected,
            localAddress = DiagnosticsSanitizer.REDACTED,
            remoteAddress = DiagnosticsSanitizer.REDACTED,
            decoderState = decoderState.label,
            connectionDurationSeconds = durationSec,
        )
    }
}
