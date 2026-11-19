package com.andrerinas.openheadunit.connection.diagnostics

import com.andrerinas.openheadunit.aap.AapTransport
import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Immutable classified connection error carrying its [ConnectionErrorCode], stage, user-facing
 * description, context-sensitive troubleshooting suggestions, and optional technical detail for
 * debug logs.
 */
data class ClassifiedConnectionError(
    val code: ConnectionErrorCode,
    val userMessage: String = code.userMessage,
    val shortMessage: String = code.shortMessage,
    val troubleshootingSuggestions: List<String> = code.troubleshootingSuggestions,
    val technicalDetail: String? = null,
    val occurredAtElapsedMs: Long = 0L,
) {
    val stage: ErrorStage get() = code.stage
    fun formattedToastText(): String = "[$code] $userMessage"
    fun formattedDialogTitle(): String = code.formattedUiTitle()
    fun formattedDialogMessage(): String = code.formattedDialogMessage()
}

/**
 * Maps connection, discovery, transport, TLS/AAP handshake, and media decoder failures to one of
 * the required [ConnectionErrorCode] values so no failure is collapsed into a generic
 * "Connection Failed" message.
 */
object ConnectionErrorClassifier {

    fun classify(
        rawCause: String? = null,
        throwable: Throwable? = null,
        handshakeFailure: AapTransport.HandshakeFailure? = null,
        endpoint: String? = null,
        wifiConnected: Boolean = true,
        sameSubnet: Boolean = true,
        clientIsolationSuspected: Boolean = false,
        reachedHandshake: Boolean = false,
        renderedAnyFrame: Boolean = false,
        occurredAtElapsedMs: Long = 0L,
    ): ClassifiedConnectionError {
        val code = resolveCode(
            rawCause = rawCause,
            throwable = throwable,
            handshakeFailure = handshakeFailure,
            endpoint = endpoint,
            wifiConnected = wifiConnected,
            sameSubnet = sameSubnet,
            clientIsolationSuspected = clientIsolationSuspected,
            reachedHandshake = reachedHandshake,
            renderedAnyFrame = renderedAnyFrame,
        )
        val detail = buildTechnicalDetail(rawCause, throwable, endpoint)
        return ClassifiedConnectionError(
            code = code,
            userMessage = code.userMessage,
            shortMessage = code.shortMessage,
            troubleshootingSuggestions = code.troubleshootingSuggestions,
            technicalDetail = detail,
            occurredAtElapsedMs = occurredAtElapsedMs,
        )
    }

    fun resolveCode(
        rawCause: String? = null,
        throwable: Throwable? = null,
        handshakeFailure: AapTransport.HandshakeFailure? = null,
        endpoint: String? = null,
        wifiConnected: Boolean = true,
        sameSubnet: Boolean = true,
        clientIsolationSuspected: Boolean = false,
        reachedHandshake: Boolean = false,
        renderedAnyFrame: Boolean = false,
    ): ConnectionErrorCode {
        // 1. Direct match if rawCause already names a ConnectionErrorCode
        ConnectionErrorCode.fromCodeOrNull(rawCause)?.let { return it }

        val combinedText = buildString {
            if (!rawCause.isNullOrBlank()) append(rawCause).append(' ')
            if (throwable != null) {
                append(throwable.javaClass.name).append(' ')
                append(throwable.message.orEmpty()).append(' ')
                throwable.cause?.let { c ->
                    append(c.javaClass.name).append(' ')
                    append(c.message.orEmpty())
                }
            }
        }

        // Check if any ConnectionErrorCode name appears in combinedText
        for (entry in ConnectionErrorCode.entries) {
            if (combinedText.contains(entry.name, ignoreCase = true)) {
                return entry
            }
        }

        // 2. Media Pipeline stage checks
        if (rawCause.equals("video_first_frame_timeout", ignoreCase = true) ||
            (reachedHandshake && !renderedAnyFrame && rawCause.equals("video_starved", ignoreCase = true))
        ) {
            return ConnectionErrorCode.VIDEO_FIRST_FRAME_TIMEOUT
        }

        if (rawCause.equals("decoder_init_failed", ignoreCase = true) ||
            rawCause.equals("read_failed", ignoreCase = true) ||
            combinedText.contains("Start reading failed", ignoreCase = true) ||
            combinedText.contains("MediaCodec", ignoreCase = true) ||
            combinedText.contains("decoder", ignoreCase = true)
        ) {
            return ConnectionErrorCode.DECODER_INIT_FAILED
        }

        // 3. Handshake / TLS / AAP Protocol checks
        if (handshakeFailure == AapTransport.HandshakeFailure.SSL ||
            rawCause.equals("tls_handshake_failed", ignoreCase = true) ||
            throwable is SSLException ||
            throwable?.cause is SSLException ||
            combinedText.contains("SSL", ignoreCase = true) ||
            combinedText.contains("TLS", ignoreCase = true)
        ) {
            return ConnectionErrorCode.TLS_HANDSHAKE_FAILED
        }

        if (handshakeFailure == AapTransport.HandshakeFailure.PEER_SILENT ||
            rawCause.equals("peer_silent", ignoreCase = true) ||
            combinedText.contains("peer never responded", ignoreCase = true)
        ) {
            return if (endpoint?.endsWith(":5277") == true) {
                ConnectionErrorCode.HEADUNIT_SERVER_NOT_RUNNING
            } else {
                ConnectionErrorCode.AAP_NEGOTIATION_FAILED
            }
        }

        if (handshakeFailure == AapTransport.HandshakeFailure.TRANSPORT_ERROR ||
            handshakeFailure == AapTransport.HandshakeFailure.OTHER ||
            rawCause.equals("aap_negotiation_failed", ignoreCase = true) ||
            rawCause.equals("handshake_failed", ignoreCase = true) ||
            combinedText.contains("Handshake failed", ignoreCase = true) ||
            combinedText.contains("VERSION_RESPONSE", ignoreCase = true)
        ) {
            return ConnectionErrorCode.AAP_NEGOTIATION_FAILED
        }

        // 4. Discovery & Connectivity / Transport Layer checks
        if (clientIsolationSuspected ||
            rawCause.equals("wifi_client_isolation", ignoreCase = true) ||
            combinedText.contains("client isolation", ignoreCase = true)
        ) {
            return ConnectionErrorCode.WIFI_CLIENT_ISOLATION
        }

        if (throwable is SocketTimeoutException ||
            throwable?.cause is SocketTimeoutException ||
            rawCause.equals("socket_timeout", ignoreCase = true) ||
            combinedText.contains("SocketTimeoutException", ignoreCase = true) ||
            combinedText.contains("ETIMEDOUT", ignoreCase = true) ||
            combinedText.contains("timed out", ignoreCase = true) ||
            combinedText.contains("timeout", ignoreCase = true)
        ) {
            return ConnectionErrorCode.SOCKET_TIMEOUT
        }

        if (throwable is BindException ||
            throwable?.cause is BindException ||
            rawCause.equals("headunit_server_not_running", ignoreCase = true) ||
            combinedText.contains("BindException", ignoreCase = true) ||
            combinedText.contains("port 5288", ignoreCase = true)
        ) {
            return ConnectionErrorCode.HEADUNIT_SERVER_NOT_RUNNING
        }

        if (throwable is NoRouteToHostException ||
            throwable?.cause is NoRouteToHostException ||
            combinedText.contains("EHOSTUNREACH", ignoreCase = true) ||
            combinedText.contains("ENETUNREACH", ignoreCase = true) ||
            combinedText.contains("NoRouteToHostException", ignoreCase = true)
        ) {
            return if (wifiConnected && sameSubnet) {
                ConnectionErrorCode.WIFI_CLIENT_ISOLATION
            } else {
                ConnectionErrorCode.PORT_UNREACHABLE
            }
        }

        if (throwable is ConnectException ||
            throwable?.cause is ConnectException ||
            combinedText.contains("ECONNREFUSED", ignoreCase = true) ||
            combinedText.contains("Connection refused", ignoreCase = true)
        ) {
            return if (endpoint?.endsWith(":5277") == true) {
                ConnectionErrorCode.HEADUNIT_SERVER_NOT_RUNNING
            } else {
                ConnectionErrorCode.PORT_UNREACHABLE
            }
        }

        if (rawCause.equals("phone_not_found", ignoreCase = true) ||
            combinedText.contains("no phone", ignoreCase = true) ||
            combinedText.contains("not found", ignoreCase = true)
        ) {
            return ConnectionErrorCode.PHONE_NOT_FOUND
        }

        if (rawCause.equals("port_unreachable", ignoreCase = true)) {
            return ConnectionErrorCode.PORT_UNREACHABLE
        }

        // Default mapping for any remaining socket/transport connect failure
        if (endpoint?.endsWith(":5277") == true) {
            return ConnectionErrorCode.HEADUNIT_SERVER_NOT_RUNNING
        }
        return ConnectionErrorCode.PORT_UNREACHABLE
    }

    private fun buildTechnicalDetail(
        rawCause: String?,
        throwable: Throwable?,
        endpoint: String?,
    ): String? {
        if (rawCause == null && throwable == null && endpoint == null) return null
        return buildString {
            if (!rawCause.isNullOrBlank()) append("cause=").append(rawCause)
            if (!endpoint.isNullOrBlank()) {
                if (isNotEmpty()) append(", ")
                append("endpoint=").append(endpoint)
            }
            if (throwable != null) {
                if (isNotEmpty()) append(", ")
                append("exception=").append(throwable.javaClass.simpleName)
                if (!throwable.message.isNullOrBlank()) {
                    append(": ").append(throwable.message)
                }
            }
        }
    }
}
