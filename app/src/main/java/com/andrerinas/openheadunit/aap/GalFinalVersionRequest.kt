package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import java.io.IOException

/**
 * Confirms a lower profile on the same connection before TLS. The phone keeps the last
 * request for its feature gates, so a covering reply alone cannot authorize a lower profile.
 *
 * Responses have no request ID. USB retries of the opening request can leave up to
 * `possibleOpeningReplies` earlier replies outstanding, including writes that reported an
 * error after possible delivery. On the ordered stream, consume up to that many valid but
 * ambiguous replies. Each buys one resend of the same lower request, so a lost opening
 * request no longer starves confirmation, but an opening reply alone cannot authorize TLS.
 * TCP has no opening retries and always sends the lower request exactly once.
 * All writes and reads share this exchange's own deadline; other failures stop immediately.
 */
internal class GalFinalVersionRequest(
    private val frames: HandshakeFrameReader,
    private val now: () -> Long,
    private val send: (ByteArray, Int) -> Int,
    private val log: (String) -> Unit
) {
    /** Preserve transport recovery for broken I/O/framing, not a valid refusal or read timeout. */
    var transportFailed: Boolean = false
        private set
    var responseSize: Int = 0
        private set

    fun request(profile: GalProfile, deadline: Long, possibleOpeningReplies: Int): GalSessionProfile {
        var unconfirmed = possibleOpeningReplies
        val requested = profile.version
        val bytes = Messages.versionRequest(requested.major, requested.minor)
        fun sendRequest() {
            val remaining = deadline - now()
            if (remaining <= 0) throw IOException("GAL final request deadline expired")
            log("GAL final request=$requested")
            transportFailed = true
            val written = send(bytes, minOf(remaining, 2000L).toInt())
            transportFailed = written != bytes.size
            if (transportFailed) throw IOException("GAL final request write incomplete: $written/${bytes.size}")
        }

        sendRequest()
        while (now() < deadline) {
            val frame = try {
                frames.readFrame(deadline)
            } catch (e: IOException) {
                transportFailed = true
                throw e
            } ?: throw IOException("GAL final response incomplete or timed out")
            if (now() >= deadline) throw IOException("GAL final response deadline expired")
            if (HandshakeMessagePolicy.isTerminalResponse(frame)) throw IOException("GAL peer terminated the final exchange")
            if (!HandshakeMessagePolicy.isLateVersionResponse(frame)) {
                log("GAL final request ignored non-version frame (${frame.size} bytes)")
                continue
            }
            val response = AapVersionNegotiation.parse(frame, frame.size)
                ?: throw IOException("GAL malformed final response")
            log("GAL final request=$requested peer=${response.version} status=${response.statusName}")
            // Inspected senders report success even above their maximum, so a successful status
            // alone does not authorize the profile: the reply must also cover it.
            if (!response.accepted || !response.version.wellFormed || response.version < requested) {
                throw IOException("GAL final response ${response.version} (${response.statusName}) refuses $requested")
            }
            if (unconfirmed > 0) {
                unconfirmed--
                log("GAL final response could be an opening reply; requesting confirmation again")
                sendRequest()
                continue
            }
            responseSize = frame.size
            return GalSessionProfile(profile, response)
        }
        throw IOException("GAL final request deadline expired")
    }
}
