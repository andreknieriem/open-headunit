package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import java.io.IOException

/**
 * The one final VersionRequest after an opening reply below the opening profile, on the same
 * connection before TLS. The phone keeps the last request for its feature gates, so the session
 * runs at this profile whatever higher reply the opening request received.
 *
 * Responses have no request ID. This request is sent once and consumes one complete response;
 * timeout, incomplete write or refusal ends this connection instead of reassigning a late reply.
 * It shares the transport's original negotiation deadline.
 *
 * A resent opening request can still be answered after this request is sent. Its reply repeats
 * the opening reply, so up to [staleReplies] replies equal to that one are skipped rather than
 * taken as confirmation; a final reply that happens to repeat it then times out, which fails
 * closed.
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

    fun request(profile: GalProfile, deadline: Long, opening: AapVersionNegotiation.Result,
                staleReplies: Int): GalSessionProfile {
        var stale = staleReplies
        val requested = profile.version
        val remaining = deadline - now()
        if (remaining <= 0) throw IOException("GAL final request deadline expired")
        val bytes = Messages.versionRequest(requested.major, requested.minor)
        log("GAL final request=$requested")
        transportFailed = true
        val written = send(bytes, minOf(remaining, 2000L).toInt())
        transportFailed = written != bytes.size
        if (transportFailed) throw IOException("GAL final request write incomplete: $written/${bytes.size}")
        while (now() < deadline) {
            val frame = try {
                frames.readFrame(deadline)
            } catch (e: IOException) {
                transportFailed = true
                throw e
            } ?: throw IOException("GAL final response incomplete or timed out")
            if (HandshakeMessagePolicy.isTerminalResponse(frame)) throw IOException("GAL peer terminated the final exchange")
            if (!HandshakeMessagePolicy.isLateVersionResponse(frame)) {
                log("GAL final request ignored non-version frame (${frame.size} bytes)")
                continue
            }
            val response = AapVersionNegotiation.parse(frame, frame.size)
                ?: throw IOException("GAL malformed final response")
            log("GAL final request=$requested peer=${response.version} status=${response.statusName}")
            if (stale > 0 && response.version == opening.version && response.status == opening.status) {
                stale--
                log("GAL final request skipped a repeated opening reply")
                continue
            }
            // Inspected senders report success even above their maximum, so a successful status
            // alone does not authorize the profile: the reply must also cover it.
            if (!response.accepted || !response.version.wellFormed || response.version < requested) {
                throw IOException("GAL final response ${response.version} (${response.statusName}) refuses $requested")
            }
            responseSize = frame.size
            return GalSessionProfile(profile, response)
        }
        throw IOException("GAL final request deadline expired")
    }
}
