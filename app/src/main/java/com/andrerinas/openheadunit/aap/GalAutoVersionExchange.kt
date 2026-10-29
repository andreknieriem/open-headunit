package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import java.io.IOException

/**
 * Opt-in pre-TLS discovery, separate from exact manual version requests.
 * GMS 12.8's cold handler rejects non-major-1 requests. Inspected AA9–18 handlers instead
 * mask their higher ceiling with 1.7 until a request exceeds 1.7, so 1.8 reveals that ceiling
 * without starting with an incompatible major. Both retain the REQUEST for feature gates:
 * a probe must be followed by the concrete final request, even when returning to 1.2.
 *
 * Responses have no request ID. Each phase sends once and consumes one complete response;
 * timeout, incomplete write or refusal ends this connection instead of reassigning a late
 * reply to another phase. All phases share the transport's original negotiation deadline.
 */
internal class GalAutoVersionExchange(
    private val frames: HandshakeFrameReader,
    private val now: () -> Long,
    private val send: (ByteArray, Int) -> Int,
    private val log: (String) -> Unit
) {
    var phase: String = "baseline"
        private set
    var requestCount: Int = 0
        private set
    /** Preserve transport recovery for broken I/O/framing, not a valid refusal or read timeout. */
    var transportFailed: Boolean = false
        private set
    var responseSize: Int = 0
        private set

    fun negotiate(deadline: Long): GalSessionProfile {
        val baseline = exchange("baseline", GalProfile.COMPATIBILITY.version, deadline)
        val probe = if (baseline.version >= MASKED_CEILING) {
            exchange("probe", CEILING_PROBE, deadline)
        } else null
        val ceiling = (probe ?: baseline).version
        val selected = GalProfile.entries.lastOrNull { it.version <= ceiling }
            ?: throw IOException("GAL peer ceiling $ceiling has no implemented profile")
        val final = if (probe != null || selected != GalProfile.COMPATIBILITY) {
            exchange("final", selected.version, deadline)
        } else baseline
        // Inspected senders can report success even above their supported ceiling. The final
        // response, not the probe's options or a successful status alone, authorizes this profile.
        if (final.version < selected.version) {
            throw IOException("GAL final response ${final.version} is below request ${selected.version}")
        }
        phase = "complete"
        return GalSessionProfile(selected, final)
    }

    private fun exchange(stage: String, requested: GalVersion, deadline: Long): AapVersionNegotiation.Result {
        phase = stage
        val remaining = deadline - now()
        if (remaining <= 0) throw IOException("GAL $stage deadline expired")
        val bytes = Messages.versionRequest(requested.major, requested.minor)
        log("GAL mode=auto phase=$stage request=$requested")
        requestCount++
        transportFailed = true
        val written = send(bytes, minOf(remaining, 2000L).toInt())
        transportFailed = written != bytes.size
        if (transportFailed) {
            throw IOException("GAL $stage request write incomplete: $written/${bytes.size}")
        }
        while (now() < deadline) {
            val frame = try {
                frames.readFrame(deadline)
            } catch (e: IOException) {
                transportFailed = true
                throw e
            } ?: throw IOException("GAL $stage response incomplete or timed out")
            if (HandshakeMessagePolicy.isTerminalResponse(frame)) {
                throw IOException("GAL peer terminated $stage exchange")
            }
            if (!HandshakeMessagePolicy.isLateVersionResponse(frame)) {
                log("GAL mode=auto phase=$stage ignored non-version frame (${frame.size} bytes)")
                continue
            }
            val response = AapVersionNegotiation.parse(frame, frame.size)
                ?: throw IOException("GAL malformed $stage response")
            log("GAL mode=auto phase=$stage request=$requested peer=${response.version} status=${response.statusName}")
            if (!response.accepted || response.major !in 1..32767 || response.minor !in 0..32767) {
                throw IOException("GAL rejected $stage response")
            }
            responseSize = frame.size
            return response
        }
        throw IOException("GAL $stage deadline expired")
    }

    private companion object {
        val MASKED_CEILING = GalVersion(1, 7)
        val CEILING_PROBE = GalVersion(1, 8)
    }
}
