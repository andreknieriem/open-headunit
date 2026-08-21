package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.AccessoryConnection
import com.andrerinas.openheadunit.utils.AppLog

internal class AapReadSingleMessage(
        connection: AccessoryConnection,
        ssl: AapSsl,
        handler: AapMessageHandler,
        onVideoRunHoled: () -> Unit = {},
        faultInjector: VideoFaultInjector? = null)
    : AapRead.Base(connection, ssl, handler, onVideoRunHoled, faultInjector) {

    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    // Increase to 4MB to handle large 1080p/4K/HEVC I-frames
    private val msgBuffer = ByteArray(4 * 1024 * 1024) 
    private val fragmentSizeBuffer = ByteArray(4)

    override fun doRead(connection: AccessoryConnection): Int {
        try {
            // Step 1: Read the encrypted header.
            // No timeout limit (0 = infinite) because this waits for the
            // NEXT message — the phone can be idle for minutes and that's normal.
            // TCP keepAlive will detect a truly dead connection.
            val isSocket = connection is com.andrerinas.openheadunit.connection.SocketAccessoryConnection
            val timeout = if (isSocket) 15000 else 0
            val headerSize = connection.recvBlocking(recvHeader.buf, recvHeader.buf.size, timeout, true)
            when (AapReadRecoveryPolicy.afterHeaderRead(headerSize, AapMessageIncoming.EncryptedHeader.SIZE, isSocket)) {
                AapReadRecoveryPolicy.Outcome.CONTINUE -> {
                    // Either the whole header arrived, or nothing did on a transport that tolerates
                    // a quiet bus. Only the second needs to go round again without one.
                    if (headerSize != AapMessageIncoming.EncryptedHeader.SIZE) return 0
                }
                AapReadRecoveryPolicy.Outcome.DISCONNECT_EOF -> {
                    AppLog.i("AapRead: Connection closed (EOF). Disconnecting.")
                    return -1
                }
                AapReadRecoveryPolicy.Outcome.DISCONNECT_IDLE -> {
                    AppLog.w("AapRead: WiFi read timeout (${timeout}ms) - connection lost.")
                    return -1
                }
                AapReadRecoveryPolicy.Outcome.DISCONNECT_DESYNC -> {
                    AppLog.e(
                        "AapRead: partial header, $headerSize of ${AapMessageIncoming.EncryptedHeader.SIZE} bytes. " +
                            "The rest of it is still on the socket and cannot be located again - disconnecting to resync."
                    )
                    return -1
                }
            }

            recvHeader.decode()

            // Immediate check for Magic Garbage in the header bytes.
            // This is the most reliable path for intentional disconnects from the Helper.
            if (isMagicGarbage(recvHeader.buf, 0, recvHeader.buf.size)) {
                AppLog.i("AapRead: Magic Garbage detected in header. Clean disconnect.")
                return -2
            }

            // Only a first fragment carries the total size, and only then is this meaningful.
            var declaredTotal = 0
            if (recvHeader.flags == 0x09) {
                // Once header arrived, data should be flowing — 10s timeout is valid here
                val readSize = connection.recvBlocking(fragmentSizeBuffer, 4, 10000, true)
                when (AapReadRecoveryPolicy.afterFragmentTotalRead(readSize, 4)) {
                    AapReadRecoveryPolicy.Outcome.CONTINUE -> Unit
                    AapReadRecoveryPolicy.Outcome.DISCONNECT_EOF -> {
                        AppLog.i("AapRead: Connection closed reading the fragment total.")
                        return -1
                    }
                    else -> {
                        AppLog.e(
                            "AapRead: fragment total read returned $readSize of 4 - this message's header is " +
                                "already consumed, so the body would be read as the next header. Disconnecting to resync."
                        )
                        return -1
                    }
                }
                declaredTotal = Utils.bytesToInt(fragmentSizeBuffer, 0, false)
            }

            // Step 2: Read the encrypted message body
            // Header arrived so body should follow quickly — 10s timeout
            if (AapReadRecoveryPolicy.afterDeclaredLength(recvHeader.enc_len, msgBuffer.size) !=
                AapReadRecoveryPolicy.Outcome.CONTINUE
            ) {
                // Nearly always a header read out of garbage rather than a message too big to hold.
                // Either way the body cannot be consumed, so skipping would compound the loss.
                AppLog.e(
                    "AapRead: declared message size ${recvHeader.enc_len} is outside the ${msgBuffer.size}-byte " +
                        "buffer - the stream is no longer framed. Disconnecting to resync."
                )
                return -1
            }

            val msgSize = connection.recvBlocking(msgBuffer, recvHeader.enc_len, 10000, true)
            when (AapReadRecoveryPolicy.afterBodyRead(msgSize, recvHeader.enc_len)) {
                AapReadRecoveryPolicy.Outcome.CONTINUE -> Unit
                AapReadRecoveryPolicy.Outcome.DISCONNECT_EOF -> {
                    AppLog.i("AapRead: Connection closed during body read.")
                    return -1
                }
                else -> {
                    // "got 0" is the timeout catch's return value, not a count: recvBlocking uses
                    // readFully, which loops, so an unknown number of these bytes are already gone.
                    AppLog.e(
                        "AapRead: body read returned $msgSize of ${recvHeader.enc_len} expected - an unknown " +
                            "number of bytes were consumed, so the stream can no longer be framed. Disconnecting to resync."
                    )
                    return -1
                }
            }

            // Reader-stage fault injection, resolved before the audit and acted on after the
            // decrypt. Both halves of that are load-bearing - see shouldDropForFaultInjection.
            val injectedDrop =
                shouldDropForFaultInjection(recvHeader.chan, recvHeader.flags, recvHeader.enc_len)

            // The whole body arrived, so this fragment can be counted against the run's declared
            // total. Done before decryption because the total is a framing quantity - and skipped
            // for an injected drop, which is what leaves the run short of what it declared.
            if (!injectedDrop) {
                auditFragment(recvHeader.chan, recvHeader.flags, recvHeader.enc_len, declaredTotal)
            }

            // Step 3: Decrypt the message. Unconditionally, including a message about to be dropped:
            // the SSL engine's record sequence advances per record and the phone's does too, so a
            // record we never unwrap desynchronises the session for good.
            val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)

            if (msg == null) {
                // If decryption failed because of a Magic Garbage signal, return -2 to signal clean quit
                if (ssl is AapSslContext && ssl.isUserDisconnect) {
                    AppLog.i("AapRead: Magic Garbage detected in decryption. Triggering clean disconnect.")
                    return -2
                }
                return 0
            }

            // Now the message can be thrown away: the SSL engine has seen it, the audit has not,
            // and nothing downstream ever will - which is a fragment that failed to arrive, as far
            // as everything past this point can tell.
            if (injectedDrop) return 0

            // Step 4: Handle the decrypted message
            handler.handle(msg)
            return 0
        } catch (e: Exception) {
            // Stays at 0 on purpose, unlike the read sites above. recvBlocking catches its own
            // IOException and SocketTimeoutException, so anything reaching here was thrown after the
            // body had been read in full - decode, decrypt or a handler - and the stream is still
            // framed. Carrying on costs one message; the read failures above cost the session.
            AppLog.e("AapRead: Error in read loop (ignored): ${e.message}")
            return 0
        }
    }

    private fun isMagicGarbage(buffer: ByteArray, start: Int, length: Int): Boolean {
        if (length < 4) return false // Need at least some bytes to verify
        // Check if at least the first 4 bytes are 0xFF
        for (i in 0 until 4.coerceAtMost(length)) {
            if (buffer[start + i] != 0xFF.toByte()) return false
        }
        return true
    }
}
