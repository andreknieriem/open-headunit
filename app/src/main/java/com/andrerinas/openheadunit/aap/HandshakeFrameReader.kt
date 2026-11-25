package com.andrerinas.openheadunit.aap

import java.io.IOException

/**
 * Cleartext handshake envelope: channel(1), flags(1), body length(2), type(2), payload.
 * Length includes type, but excludes the four-byte transport header. Version and TLS handshake
 * messages use channel 0 and flags 3 (one complete, unencrypted message).
 *
 * TCP reads and USB bulk transfers are not message boundaries. Request only the bytes remaining
 * in this frame; the connection retains any following frame for the next handshake phase.
 * Use non-readFully I/O so every consumed byte is counted even if a later read times out.
 */
internal class HandshakeFrameReader(
    private val now: () -> Long,
    private val minimumReadTimeoutMs: Int = 1,
    private val skipEncryptedFrames: Boolean = false,
    private val receive: (ByteArray, Int, Int) -> Int
) {
    private var frame = ByteArray(4)
    private var used = 0
    private var scratch = ByteArray(4)
    private var readingBody = false
    private var encrypted = false
    private var skipPending = false

    /** The prefix was read before the request. Finish this envelope only to keep alignment. */
    fun skipPendingFrame() {
        skipPending = used != 0
    }

    /** Only for abandoning stale input before our request; never reset a live exchange. */
    fun discardPendingFrame() {
        frame = ByteArray(4)
        used = 0
        readingBody = false
        encrypted = false
        skipPending = false
    }

    /** Null means no complete frame yet; partial bytes survive the caller's retry deadline. */
    fun readFrame(deadline: Long, stopOnIdle: Boolean = false): ByteArray? {
        while (now() < deadline) {
            val needed = frame.size - used
            if (scratch.size < needed) scratch = ByteArray(needed)
            // USB bulk APIs can lose a partial transfer when given a tiny timeout. Stop at
            // the end of this window rather than issue an unsafe read or extend the deadline.
            val remaining = deadline - now()
            if (remaining < minimumReadTimeoutMs) return null
            // Before sending VersionRequest, drain in short windows and stop on the first
            // idle read. Keep the same parser afterwards: an idle gap can split any envelope.
            val timeout = remaining.coerceAtMost(
                if (stopOnIdle) minimumReadTimeoutMs.toLong() else Int.MAX_VALUE.toLong()).toInt()
            val count = receive(scratch, needed, timeout)
            if (count < 0) return null
            if (count == 0) {
                if (stopOnIdle) return null
                continue // USB zero-length packet: still inside the negotiation window.
            }
            if (count > needed) throw IOException("Handshake read exceeded requested length")
            scratch.copyInto(frame, used, 0, count)
            used += count
            if (used != frame.size) continue
            if (!readingBody) {
                val flags = frame[1].toInt() and 0xff
                val length = ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
                encrypted = skipEncryptedFrames && flags in 0x08..0x0f
                if (!encrypted && (flags != 3 || length < 2)) {
                    throw IOException("Invalid cleartext handshake header")
                }
                // A dongle can still relay old encrypted traffic after the USB drain goes quiet.
                // Only version negotiation may discard it; TLS negotiation remains strict.
                // FIRST without LAST (0x09/0x0d) adds a four-byte total outside the u16 length.
                // CONTROL is independent of encryption and fragmentation. Consume that total
                // field without allocating from its value or decrypting the old session's body.
                // Even the largest envelope is bounded to 4 + 4 + 65,535 bytes.
                val extra = if (encrypted && AapMessageFraming.carriesTotalLength(flags)) 4 else 0
                frame = frame.copyOf(4 + extra + length)
                readingBody = true
                if (used != frame.size) continue
            }
            val complete = frame
            frame = ByteArray(4)
            used = 0
            readingBody = false
            if (encrypted || skipPending) {
                skipPending = false
                continue
            }
            return complete
        }
        return null
    }
}
