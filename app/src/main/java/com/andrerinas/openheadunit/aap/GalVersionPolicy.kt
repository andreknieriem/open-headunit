package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.usb.UsbRecoveryDevice
import java.net.InetAddress

/**
 * Which version to request first and which profile a reply selects.
 *
 * Inspected Android Auto handlers (9.7 to 18.2) never refuse a VersionRequest. They answer 1.7
 * to a request of 1.7 or lower and their maximum constant to anything higher, with success, and
 * keep the last request before TLS. One 6.1 request therefore reveals that maximum, and a phone
 * answering less receives one more, final request on the same connection.
 *
 * Google Play services 12.8.76 (2018) hosted a car service that accepted major version 1 only:
 * it answers a 6.x request with 1.5 and STATUS_NO_COMPATIBLE_VERSION, then closes the
 * connection. A short-lived hint lets that peer retry at 1.2; it is not a process-wide pin.
 */
internal class GalVersionPolicy {
    sealed interface Peer {
        data class Usb(
            val vendor: Int, val product: Int, val manufacturer: String?, val model: String?,
            val serial: String?, val attachmentPath: String?
        ) : Peer

        // Socket routes are not durable phone identities. Only remember a live, direct endpoint
        // briefly, and distinguish an accepted socket's listener from an outgoing service.
        data class Tcp(
            val local: InetAddress, val remote: InetAddress, val servicePort: Int, val inbound: Boolean
        ) : Peer
    }

    enum class Opening(val profile: GalProfile, val logSuffix: String) {
        DEFAULT(GalProfile.GAL_6_1, ""),
        SETTING(GalProfile.COMPATIBILITY, " (compatibility setting)"),
        PEER_REFUSAL(GalProfile.COMPATIBILITY, " (retry after peer refusal)")
    }

    private data class Refusal(val peer: Peer, val expiresAtMs: Long)

    private var refusal: Refusal? = null

    @Synchronized
    fun opening(compatibilityMode: Boolean, peer: Peer?, nowMs: Long): Opening {
        val hint = refusal
        val retry = hint != null && nowMs < hint.expiresAtMs && peer == hint.peer
        if (hint != null && (nowMs >= hint.expiresAtMs || retry)) refusal = null
        return when {
            compatibilityMode -> Opening.SETTING
            retry -> Opening.PEER_REFUSAL
            else -> Opening.DEFAULT
        }
    }

    @Synchronized
    fun noteRefusal(peer: Peer, nowMs: Long) {
        refusal = Refusal(peer, nowMs + REFUSAL_RETRY_WINDOW_MS)
    }

    companion object {
        private const val REFUSAL_RETRY_WINDOW_MS = 30_000L

        fun usbPeer(device: UsbRecoveryDevice): Peer.Usb? {
            // Serial survives USB re-enumeration. Without it, only reuse the exact attachment;
            // VID/PID and model alone identify a product, not the attached device.
            val path = if (device.serial == null) device.path.takeIf { it.isNotBlank() } ?: return null else null
            return Peer.Usb(device.vendor, device.product, device.manufacturer, device.model, device.serial, path)
        }

        fun isLegacyRefusal(opening: GalProfile, reply: AapVersionNegotiation.Result): Boolean =
            opening == GalProfile.GAL_6_1 && !reply.accepted && reply.major == 1 &&
                reply.minor in GalProfile.COMPATIBILITY.version.minor..32767

        /** The highest implemented profile the reply covers, never above the request already sent. */
        fun select(opening: GalProfile, reply: GalVersion): GalProfile =
            GalProfile.entries.lastOrNull { it <= opening && it.version <= reply } ?: GalProfile.COMPATIBILITY
    }
}
