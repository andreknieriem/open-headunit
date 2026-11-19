package com.andrerinas.openheadunit.connection.diagnostics

/**
 * Privacy sanitizer for exported diagnostics and user-facing error summaries.
 *
 * Ensures exported diagnostics never contain:
 * - Raw IPv4 / IPv6 addresses
 * - MAC / BSSID / Bluetooth addresses
 * - Phone numbers
 * - Device serial numbers
 * - SSIDs
 * - Account identifiers / email addresses
 * - Authentication tokens / OAuth credentials
 * - TLS keys or PEM certificates
 * - Full session identifiers
 */
object DiagnosticsSanitizer {

    const val REDACTED = "[redacted]"
    const val MASKED = "[masked]"

    private val PEM_BLOCK_REGEX = Regex(
        "-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?-----END [A-Z0-9 ]+-----",
        RegexOption.IGNORE_CASE,
    )

    private val MAC_ADDRESS_REGEX = Regex(
        "\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b"
    )

    private val IPV4_ADDRESS_REGEX = Regex(
        "\\b(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}(?::\\d{1,5})?\\b"
    )

    private val IPV6_ADDRESS_REGEX = Regex(
        "(?:\\[?(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}\\]?(?::\\d{1,5})?)|(?:\\b::1\\b)|(?:\\bfe80:[0-9A-Fa-f:%]+\\b)",
        RegexOption.IGNORE_CASE,
    )

    private val EMAIL_REGEX = Regex(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
    )

    private val SENSITIVE_KEY_VALUE_REGEX = Regex(
        "(?i)\\b(ssid|bssid|mac|bt_mac|bluetooth_address|serial|serial_number|device_serial|" +
            "session_id|sessionid|token|access_token|refresh_token|oauth|bearer|password|passphrase|" +
            "secret|api_key|apikey|account|account_id|cert|certificate|private_key|tls_key|phone|phone_number)" +
            "\\s*([:=])\\s*(\"[^\"]*\"|'[^']*'|[^\\s,;]+)"
    )

    private val BEARER_TOKEN_REGEX = Regex(
        "(?i)\\b(Bearer|OAuth)\\s+[A-Za-z0-9._~+/=-]{6,}\\b"
    )

    private val PHONE_NUMBER_REGEX = Regex(
        "(?<!\\w)(?:\\+\\d{1,3}[\\s.-]?)?(?:\\(\\d{2,4}\\)[\\s.-]?)?\\d{3}[\\s.-]\\d{3,4}[\\s.-]\\d{3,4}(?!\\w)"
    )

    fun redact(): String = REDACTED

    fun mask(): String = MASKED

    /**
     * Scrubs all sensitive patterns from [input], replacing each occurrence with `[redacted]`.
     */
    fun sanitizeText(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var result = input
        result = PEM_BLOCK_REGEX.replace(result, REDACTED)
        result = BEARER_TOKEN_REGEX.replace(result) { match ->
            "${match.groupValues[1]} $REDACTED"
        }
        result = SENSITIVE_KEY_VALUE_REGEX.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]} $REDACTED"
        }
        result = MAC_ADDRESS_REGEX.replace(result, REDACTED)
        result = EMAIL_REGEX.replace(result, REDACTED)
        result = IPV4_ADDRESS_REGEX.replace(result, REDACTED)
        result = IPV6_ADDRESS_REGEX.replace(result, REDACTED)
        result = PHONE_NUMBER_REGEX.replace(result, REDACTED)
        return result
    }
}
