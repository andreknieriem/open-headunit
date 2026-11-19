package com.andrerinas.openheadunit.connection.diagnostics

/**
 * Pipeline stage from which a [ConnectionErrorCode] originates.
 */
enum class ErrorStage(val displayName: String) {
    DISCOVERY_AND_CONNECTIVITY("Discovery & Connectivity"),
    TRANSPORT("Transport Layer"),
    PROTOCOL("Protocol Layer"),
    MEDIA_PIPELINE("Media Pipeline"),
}

/**
 * Structured, actionable error codes across discovery, transport, Android Auto protocol
 * negotiation, and media decoding stages.
 */
enum class ConnectionErrorCode(
    val stage: ErrorStage,
    val userMessage: String,
    val shortMessage: String,
    val troubleshootingSuggestions: List<String>,
) {
    // Discovery & Connectivity
    PHONE_NOT_FOUND(
        stage = ErrorStage.DISCOVERY_AND_CONNECTIVITY,
        userMessage = "Device discovery failed. No compatible Android Auto phone detected.",
        shortMessage = "No compatible Android Auto phone detected",
        troubleshootingSuggestions = listOf(
            "Verify your phone and head unit share the same Wi-Fi network or are paired via Bluetooth.",
            "Ensure Wireless Android Auto is enabled in Android Auto settings on your phone.",
            "If using Headunit Server mode, verify the Headunit Server is running on the phone.",
        ),
    ),

    PORT_UNREACHABLE(
        stage = ErrorStage.DISCOVERY_AND_CONNECTIVITY,
        userMessage = "Device discovered, but required Android Auto ports are inaccessible.",
        shortMessage = "Required Android Auto ports are inaccessible",
        troubleshootingSuggestions = listOf(
            "Disable any active VPN or firewall on the phone or head unit blocking TCP ports 5277 or 5288.",
            "Verify the target IP address belongs to the phone running Android Auto.",
            "Restart Android Auto's Headunit Server on the phone and try again.",
        ),
    ),

    HEADUNIT_SERVER_NOT_RUNNING(
        stage = ErrorStage.DISCOVERY_AND_CONNECTIVITY,
        userMessage = "HeadUnit server is not listening or failed to start.",
        shortMessage = "HeadUnit server is not listening or failed to start",
        troubleshootingSuggestions = listOf(
            "On the phone, open Android Auto -> menu (⋮) -> Start head unit server.",
            "If the Headunit Server is already active, stop and start it again to clear any hung session.",
            "On the head unit, toggle Wireless Connection off and back on to rebind port 5288.",
        ),
    ),

    WIFI_CLIENT_ISOLATION(
        stage = ErrorStage.DISCOVERY_AND_CONNECTIVITY,
        userMessage = "Network configuration prevents peer-to-peer communication between phone and head unit.",
        shortMessage = "Wi-Fi client isolation prevents peer-to-peer communication",
        troubleshootingSuggestions = listOf(
            "Disable AP/Client Isolation (or Guest Network isolation) on your Wi-Fi router or hotspot.",
            "Use your phone's mobile hotspot, the head unit's hotspot, or Native Wi-Fi Direct instead of an isolated network.",
        ),
    ),

    // Transport Layer
    SOCKET_TIMEOUT(
        stage = ErrorStage.TRANSPORT,
        userMessage = "Connection attempt exceeded configured timeout.",
        shortMessage = "Connection attempt exceeded configured timeout",
        troubleshootingSuggestions = listOf(
            "Move the phone closer to the head unit and check Wi-Fi signal strength.",
            "Ensure the phone has not switched to another Wi-Fi network during connection setup.",
            "Disable battery optimization for Android Auto on the phone.",
        ),
    ),

    TLS_HANDSHAKE_FAILED(
        stage = ErrorStage.TRANSPORT,
        userMessage = "Transport established, but TLS negotiation failed.",
        shortMessage = "TLS handshake with phone failed",
        troubleshootingSuggestions = listOf(
            "Verify the head unit's system date and time are set accurately (an incorrect clock causes TLS certificate validation to fail).",
            "Force-stop Android Auto on the phone to clear any stale TLS session state and reconnect.",
        ),
    ),

    // Protocol Layer
    AAP_NEGOTIATION_FAILED(
        stage = ErrorStage.PROTOCOL,
        userMessage = "Android Auto Protocol session negotiation failed after transport setup.",
        shortMessage = "Android Auto Protocol session negotiation failed",
        troubleshootingSuggestions = listOf(
            "Unlock your phone screen and check for any first-time Android Auto permission or car approval prompt.",
            "Force-stop Android Auto on the phone (or restart the Headunit Server) and reconnect.",
            "If connecting over USB, unplug and reconnect the USB cable.",
        ),
    ),

    // Media Pipeline
    DECODER_INIT_FAILED(
        stage = ErrorStage.MEDIA_PIPELINE,
        userMessage = "Video or audio decoder initialization failed.",
        shortMessage = "Video or audio decoder initialization failed",
        troubleshootingSuggestions = listOf(
            "Open Video Settings and switch the Video Codec to H.264.",
            "Lower the Video Resolution (e.g., 720p or 480p) and Frame Rate (30 FPS).",
            "Enable software decoder fallback in Video Settings.",
        ),
    ),

    VIDEO_FIRST_FRAME_TIMEOUT(
        stage = ErrorStage.MEDIA_PIPELINE,
        userMessage = "Decoder initialized successfully, but no first video frame was received within the expected timeout window.",
        shortMessage = "No first video frame received within timeout window",
        troubleshootingSuggestions = listOf(
            "Switch the Wi-Fi hotspot or Wi-Fi Direct band to 5 GHz for higher bandwidth.",
            "Lower the Video Resolution to 720p/480p and Frame Rate to 30 FPS in Video Settings.",
            "Unlock the phone screen if battery saver or lock screen restrictions pause video streaming.",
        ),
    );

    fun formattedUiTitle(): String = "$name (${stage.displayName})"

    fun formattedToastText(): String = "[$name] $userMessage"

    fun formattedDialogMessage(): String = buildString {
        append(userMessage)
        if (troubleshootingSuggestions.isNotEmpty()) {
            append("\n\nTroubleshooting:\n")
            troubleshootingSuggestions.forEach { suggestion ->
                append("• ").append(suggestion).append('\n')
            }
        }
    }.trimEnd()

    companion object {
        fun fromCodeOrNull(raw: String?): ConnectionErrorCode? {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }
    }
}
