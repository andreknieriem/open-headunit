package com.andrerinas.openheadunit.connection.diagnostics

import java.util.Locale

/**
 * Snapshot of connection, pipeline stage, network, and decoder state used to generate a
 * privacy-safe diagnostics block for GitHub issues and bug reports.
 */
data class DiagnosticsSnapshot(
    val appVersion: String = "Unknown",
    val platform: String = "Android",
    val connectionMode: String = "Wireless",
    val connectionState: String = "Disconnected",
    val discoveryStatus: String = "Not Started",
    val transportStatus: String = "Not Started",
    val tlsStatus: String = "Not Started",
    val aapNegotiationStatus: String = "Not Started",
    val errorCode: ConnectionErrorCode? = null,
    val errorMessage: String? = null,
    val sessionId: String = DiagnosticsSanitizer.REDACTED,
    val wifiConnected: Boolean = false,
    val sameSubnet: Boolean = false,
    val clientIsolationDetected: Boolean = false,
    val localAddress: String = DiagnosticsSanitizer.REDACTED,
    val remoteAddress: String = DiagnosticsSanitizer.REDACTED,
    val decoderState: String = "Not Started",
    val connectionDurationSeconds: Double = 0.0,
)

/**
 * Formats a [DiagnosticsSnapshot] into a shareable, privacy-safe report suitable for direct
 * inclusion in GitHub issues.
 */
object DiagnosticsExporter {

    private const val DIVIDER = "----------------------------------------"

    fun format(snapshot: DiagnosticsSnapshot): String {
        val codeLine = snapshot.errorCode?.name ?: "NONE"
        val rawErrorMsg = snapshot.errorMessage
            ?: snapshot.errorCode?.shortMessage
            ?: "None"
        val sanitizedErrorMsg = DiagnosticsSanitizer.sanitizeText(rawErrorMsg).ifBlank { "None" }
        val sanitizedVersion = DiagnosticsSanitizer.sanitizeText(snapshot.appVersion).ifBlank { "Unknown" }
        val sanitizedPlatform = DiagnosticsSanitizer.sanitizeText(snapshot.platform).ifBlank { "Android" }
        val sanitizedMode = DiagnosticsSanitizer.sanitizeText(snapshot.connectionMode).ifBlank { "Wireless" }
        val sanitizedState = DiagnosticsSanitizer.sanitizeText(snapshot.connectionState).ifBlank { "Disconnected" }
        val durationFormatted = String.format(Locale.US, "%.1f s", snapshot.connectionDurationSeconds.coerceAtLeast(0.0))

        return buildString {
            appendLine(DIVIDER)
            appendLine("Open HeadUnit Diagnostics")
            appendLine(DIVIDER)
            appendLine("App Version: $sanitizedVersion")
            appendLine("Platform: $sanitizedPlatform")
            appendLine("Connection Mode: $sanitizedMode")
            appendLine()
            appendLine("Connection State: $sanitizedState")
            appendLine("Discovery: ${snapshot.discoveryStatus}")
            appendLine("Transport: ${snapshot.transportStatus}")
            appendLine("TLS: ${snapshot.tlsStatus}")
            appendLine("AAP Negotiation: ${snapshot.aapNegotiationStatus}")
            appendLine()
            appendLine("Error Code: $codeLine")
            appendLine("Error Message: $sanitizedErrorMsg")
            appendLine()
            appendLine("Session ID: ${DiagnosticsSanitizer.REDACTED}")
            appendLine()
            appendLine("Network:")
            appendLine("- Wi\u2011Fi Connected: ${yesNo(snapshot.wifiConnected)}")
            appendLine("- Same Subnet: ${yesNo(snapshot.sameSubnet)}")
            appendLine("- Client Isolation Detected: ${yesNo(snapshot.clientIsolationDetected)}")
            appendLine("- Local Address: ${DiagnosticsSanitizer.REDACTED}")
            appendLine("- Remote Address: ${DiagnosticsSanitizer.REDACTED}")
            appendLine()
            appendLine("Decoder State: ${snapshot.decoderState}")
            append("Connection Duration: $durationFormatted")
            appendLine()
            append(DIVIDER)
        }
    }

    private fun yesNo(flag: Boolean): String = if (flag) "Yes" else "No"
}
