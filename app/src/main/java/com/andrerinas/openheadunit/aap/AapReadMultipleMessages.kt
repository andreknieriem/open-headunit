package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Utils
import java.nio.ByteBuffer

internal class AapReadMultipleMessages(
    connection: ProjectionConnection,
    ssl: AapSsl,
    handler: AapMessageHandler,
    onVideoRunHoled: (discardAssembledUnit: Boolean) -> Unit = {},
    faultInjector: VideoFaultInjector? = null)
    : AapRead.Base(connection, ssl, handler, onVideoRunHoled, faultInjector) {

    // Increase buffers to 4MB to handle large 1080p/4K/HEVC I-frames
    private val fifo = ByteBuffer.allocate(4 * 1024 * 1024)
    private val recvBuffer = ByteArray(Messages.DEF_BUFFER_LENGTH)
    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    private val msgBuffer = ByteArray(4 * 1024 * 1024)
    private val skipBuffer = ByteArray(4)

    override fun doRead(connection: ProjectionConnection): Int {
        val size = try {
            connection.recvBlocking(recvBuffer, recvBuffer.size, 5000, false)
        } catch (e: Exception) {
            AppLog.e("AapRead: Fatal read error: ${e.message}")
            return -1
        }

        if (size < 0) {
            // If the connection is dead (e.g. resetInterface failed to re-claim),
            // signal the transport to quit instead of spinning on a broken connection.
            if (!connection.isConnected) {
                AppLog.e("AapRead: Connection lost. Stopping read loop.")
                fifo.clear()
                return -1
            }
            // It was a timeout or temporary error. Do NOT clear the FIFO because USB/TCP
            // is reliable and no bytes were lost. Discarding FIFO would desynchronize the stream.
            return 0
        }
        if (size == 0) return 0

        try {
            if (fifo.remaining() < size) {
                AppLog.w("AapRead: FIFO overflow! Size: $size, Remaining: ${fifo.remaining()}. Disconnecting.")
                return -1
            }
            fifo.put(recvBuffer, 0, size)
            processBulk()
        } catch (e: Exception) {
            AppLog.e("AapRead: Error in processBulk: ${e.message}")
            fifo.clear()
            return -1 // A discarded stream prefix cannot be resynchronised by guessing a header.
        }
        return 0
    }

    /**
     * One bulk read may end inside a header/body or contain several AAP frames. Mark the start
     * before consuming a header, and reset there if either the optional total or the body is
     * incomplete. compact() retains that entire prefix for the next read; it must not be fed
     * to TLS early or discarded as a malformed short message.
     */
    private fun processBulk() {
        fifo.flip()

        while (fifo.remaining() >= AapMessageIncoming.EncryptedHeader.SIZE) {
            fifo.mark()
            fifo.get(recvHeader.buf, 0, recvHeader.buf.size)
            recvHeader.decode()

            // Only a first fragment carries the total size, and only then is this meaningful.
            var declaredTotal = 0
            if (AapMessageFraming.carriesTotalLength(recvHeader.flags)) {
                if (fifo.remaining() < 4) {
                    fifo.reset()
                    break
                }
                fifo.get(skipBuffer, 0, 4)
                declaredTotal = Utils.bytesToInt(skipBuffer, 0, false)
            }

            if (recvHeader.enc_len > msgBuffer.size || recvHeader.enc_len < 0) {
                throw java.io.IOException("Invalid AAP frame length ${recvHeader.enc_len}")
            }

            if (fifo.remaining() < recvHeader.enc_len) {
                fifo.reset()
                break
            }

            fifo.get(msgBuffer, 0, recvHeader.enc_len)

            // Reader-stage fault injection - see the same branch in AapReadSingleMessage, and
            // shouldDropForFaultInjection for why the decrypt below is not skipped with it.
            val injectedDrop =
                shouldDropForFaultInjection(recvHeader.chan, recvHeader.flags, recvHeader.enc_len)

            try {
                // Unconditional, including for a message about to be dropped: the SSL engine's
                // record sequence advances per record and a record we never unwrap desynchronises
                // the session for good.
                val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)

                if (msg != null && !injectedDrop) {
                    deliverFragment(msg, declaredTotal)
                }
            } catch (e: java.io.IOException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("AapRead: Handling error: ${e.message}")
            }
        }

        fifo.compact()
    }
}
