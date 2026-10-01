package com.andrerinas.openheadunit.aap

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.decoder.video.VideoFaultReporter
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.utils.AuditReportPolicy
import com.andrerinas.openheadunit.utils.Settings

internal interface AapRead {
    fun read(): Int

    /**
     * @param onVideoRunHoled called when a video fragment run turns out to be short of the bytes it
     *   declared; the argument says whether the length mismatch requires that the assembled unit
     *   should be discarded rather than decoded ([AuditRecoveryPolicy.shouldDiscardAssembledUnit]).
     *   A callback rather than an [AapVideo] reference: the reader owes the video path one fact and
     *   nothing else, and the two are wired together in [Factory] the way every other cross-manager
     *   coupling in this app is.
     * @param faultInjector reader-stage fault injection, or null. Distinct from the injector
     *   [AapVideo] holds because the two act at different points in the receive path - see
     *   [VideoFaultInjector.Stage]. Only one of the two is ever non-null in a session.
     */
    abstract class Base internal constructor(
        private val connection: ProjectionConnection?,
        internal val ssl: AapSsl,
        internal val handler: AapMessageHandler,
        private val onVideoRunHoled: (discardAssembledUnit: Boolean) -> Unit = {},
        internal val faultInjector: VideoFaultInjector? = null) : AapRead {

        /** Every line [faultInjector] prints. Shared wording with [AapVideo]'s - see the class. */
        internal val faultReporter = VideoFaultReporter("AapRead")

        init {
            faultInjector?.let { faultReporter.announce(it) }
        }

        /**
         * Whether this message should be treated as one that never arrived.
         *
         * Called by both readers once the whole body is in hand and **before** [auditFragment], which
         * is the entire point: a fragment dropped here is short in the audit's own accounting, and an
         * assembler-stage drop can never reproduce that because the audit has already counted it.
         *
         * ### The caller must still decrypt the message it drops
         *
         * An earlier version of this said the drop was safe because only bytes already consumed from
         * the connection are discarded, so the stream stays framed. The framing part is true and was
         * measured - a hardware round killed four sessions this way and produced **zero** of the
         * framing-desync lines. It is also not sufficient, because the byte stream is not the only
         * ordered state in the receive path.
         *
         * [AapSslContext.decrypt] calls `SSLEngine.unwrap`, whose TLS record sequence advances per
         * record, and the phone's sending side advances its own for every record it encrypts whether
         * or not we choose to look at it. A record we never unwrap leaves this engine permanently one
         * behind, every later unwrap fails authentication, and the session dies within seconds:
         * measured at 3.9s from handshake to `Connection closed (EOF)` on the first injected fault,
         * with a storm of `Decrypted payload too short: 0` in between.
         *
         * So both readers resolve this into a local, skip only [auditFragment] and the handler, and
         * decrypt unconditionally. Do not turn either back into an early return.
         */
        protected fun shouldDropForFaultInjection(channel: Int, flags: Int, encLen: Int): Boolean {
            val injector = faultInjector ?: return false
            if (channel != Channel.ID_VID) return false
            val effect = injector.effectFor(flags)
            faultReporter.onMessage(injector, effect, flags, encLen)
            return effect == VideoFaultInjector.Effect.DROP
        }

        private val fragmentAudit = FragmentedMessageAudit()
        private val reassembler = AapMessageReassembler()
        // Per-outcome print budgets refill so a noisy startup cannot silence later failures.
        // These counters govern reports only; repair is dispatched before consulting them.
        private val auditReports = IntArray(FragmentedMessageAudit.Outcome.entries.size)
        private val auditLastReportMs = LongArray(FragmentedMessageAudit.Outcome.entries.size)
        private val auditSuppressed = IntArray(FragmentedMessageAudit.Outcome.entries.size)

        override fun read(): Int {
            if (connection == null) {
                AppLog.e("No connection.")
                return -1
            }

            return doRead(connection)
        }

        /**
         * TLS is fully consumed even for an injected drop; only delivered plaintext is counted.
         * Skipping an encrypted record would desynchronise TLS and turn a fragment-loss exercise
         * into a disconnect. Audit before dispatch so a bad final video fragment is marked for
         * discard before the video worker can finish its assembly.
         */
        protected fun deliverFragment(message: AapMessage, declaredTotal: Int) {
            auditFragment(message.channel, message.flags.toInt(), message.size, declaredTotal)
            reassembler.accept(message, declaredTotal)?.let { handler.handle(it) }
        }

        private fun auditFragment(channel: Int, flags: Int, plaintextLength: Int, declaredTotal: Int) {
            val result = fragmentAudit.onMessage(channel, flags, plaintextLength, declaredTotal) ?: return
            // Before the print budget deliberately: suppressing a repeated log must not suppress
            // repair. The callback queues recovery ahead of this fragment on the video worker.
            if (AuditRecoveryPolicy.shouldRequestKeyframe(result.outcome, result.channel)) {
                onVideoRunHoled(AuditRecoveryPolicy.shouldDiscardAssembledUnit(result))
            }

            val channelName = Channel.name(channel)
            val index = result.outcome.ordinal
            val now = SystemClock.elapsedRealtime()
            if (!AuditReportPolicy.shouldReport(auditReports[index], auditLastReportMs[index], now)) {
                auditSuppressed[index]++
                return
            }
            val suppressed = auditSuppressed[index]
            auditSuppressed[index] = 0
            auditReports[index]++
            auditLastReportMs[index] = now
            val suffix = if (suppressed > 0) " (and $suppressed more since the last report)" else ""
            AppLog.w("AapRead: %s on %s - %s%s", result.outcome, channelName, result, suffix)
        }

        protected abstract fun doRead(connection: ProjectionConnection): Int
    }

    object Factory {
        fun create(
            connection: ProjectionConnection,
            transport: AapTransport,
            recorder: MicRecorder,
            aapAudio: AapAudio,
            aapVideo: AapVideo,
            settings: Settings,
            context: Context,
            onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null,
            onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null
        ): AapRead {
            val handler = AapMessageHandlerType(
                transport,
                recorder,
                aapAudio,
                aapVideo,
                settings,
                context,
                onAaMediaMetadata,
                onAaPlaybackStatus
            )

            // Read framing is a transport-shape question, not a handshake-timing one:
            // every socket-backed connection (Nearby, WiFi Direct, Hotspot, manual IP) frames
            // one AA message per blocking read, same as it always has. Only USB/libusb bulk
            // transfers can batch multiple messages into one read and need the FIFO reassembly
            // in AapReadMultipleMessages. Do NOT key this off isSingleMessage - that flag only
            // controls the Nearby-specific handshake settle-delay/drain skip in
            // AapTransport.handshake() and is unrelated to read framing.
            // Only ever non-null for a Stage.READER mode; Stage.ASSEMBLER modes stay with AapVideo,
            // and VideoFaultInjector.isActiveAt is what keeps the two from both claiming one mode.
            val readerFaults = VideoFaultInjector(
                settings.debugVideoFaultInjection,
                settings.debugVideoFaultRate,
                settings.debugVideoFaultBudget
            ).takeIf { it.isActiveAt(VideoFaultInjector.Stage.READER) }

            // Through the transport so the verdict lands on the video thread in front of the
            // fragment it belongs to, rather than on AapVideo's state from this one.
            val onVideoRunHoled = { discard: Boolean -> transport.dispatchVideoRunHoled(discard) }

            return if (connection is SocketProjectionConnection)
                AapReadSingleMessage(connection, transport.ssl, handler, onVideoRunHoled, readerFaults,
                    captureTiming = { transport.audioTimingActive }, onSlowRead = transport::recordSlowAudioRead)
            else
                AapReadMultipleMessages(connection, transport.ssl, handler, onVideoRunHoled, readerFaults)
        }
    }
}
