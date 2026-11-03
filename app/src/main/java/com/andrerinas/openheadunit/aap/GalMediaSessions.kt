package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media

/**
 * Sole owner of sink IDs, selected formats, ACK policy and options. Microphone credits remain
 * in the uplink controller. Advertise once, then use that exact offer for both Setup and Start.
 */
internal class GalMediaSessions {
    data class Session(
        val id: Int,
        val configurationIndex: Int,
        val format: GalMediaFormat,
        val ackless: Boolean,
        val active: Boolean,
        val options: Media.MediaOptions?,
        val generation: Long,
        val previousGeneration: Long? = null,
        private val laboratory: GalLaboratoryOptions? = null
    ) {
        val runtime = if (!active) GalRuntimeOptions() else GalRuntimeOptions.from(
            options, forceAvSync = laboratory?.avSync == GalLaboratoryOptions.Mode.ON
        ).let {
            if (laboratory == null) it else it.withLaboratory(options, laboratory, format)
        }
    }

    private var nextGeneration = 0L
    private val channels = mutableMapOf<Int, Session>()
    private var policy: GalMediaPolicy? = null
    private var offer: GalMediaOffer? = null

    @Synchronized fun advertise(policy: GalMediaPolicy, offer: GalMediaOffer) {
        check(this.offer == null) { "Media discovery is already fixed for this connection" }
        this.policy = policy
        this.offer = offer
    }

    @Synchronized fun configurationIndices(channel: Int): List<Int> = offer?.indices(channel) ?: emptyList()

    @Synchronized fun codec(channel: Int): Int? = offer?.codec(channel)

    @Synchronized fun format(channel: Int, index: Int): GalMediaFormat? = offer?.format(channel, index)

    @Synchronized fun start(channel: Int, request: Media.Start): Session? {
        val format = offer?.format(channel, request.configurationIndex) ?: return null
        if (format is GalMediaFormat.Audio && !GalAudioFormat.supported(format.value)) return null
        val inspect = policy!!.optionsFor(channel).embedded
        val session = Session(request.sessionId, request.configurationIndex, format,
            request.flowControl == Media.Start.FlowControl.ACKLESS, true,
            if (inspect && request.hasOptions()) request.options else null, ++nextGeneration,
            previousGeneration = channels[channel]?.generation,
            laboratory = policy!!.laboratory.takeIf { inspect && it != GalLaboratoryOptions() })
        channels[channel] = session
        return session
    }

    @Synchronized fun active(): Map<Int, Session> = channels.filterValues { it.active }

    @Synchronized fun current(channel: Int): Session? = channels[channel]

    /** Capture ID and policy under one lock. Null means no ACK; zero retains pre-Start behavior. */
    @Synchronized fun ackSession(channel: Int): Int? {
        val session = channels[channel]
        return if (session?.ackless == true) null else session?.id ?: 0
    }

    @Synchronized fun stop(channel: Int) {
        // Keep queued tail ownership. Stop is not a decoder flush or an implicit mode change.
        channels[channel]?.let { channels[channel] = it.copy(active = false, options = null) }
    }

    @Synchronized fun acceptsUpdate(channel: Int): Boolean =
        channels[channel]?.active == true &&
            policy?.optionsFor(channel)?.standalone == true

    @Synchronized fun update(channel: Int, options: Media.MediaOptions): Boolean {
        if (!acceptsUpdate(channel)) return false
        val session = channels.getValue(channel)
        // 0x8014 has no session ID: serial receipt associates it with the active sink.
        // Defensive receiver policy, NOT a proven sender snapshot/patch contract: preserve
        // omitted fields, replace each present field wholesale (including Duration messages).
        // A new Start still resets all options, and explicit false disables a prior request.
        val merged = session.options?.toBuilder() ?: Media.MediaOptions.newBuilder()
        for ((field, value) in options.allFields) merged.setField(field, value)
        // Unknown options have no receiver semantics. Appending them would let individually
        // bounded updates grow retained session state without bound, including unknown Start data.
        merged.setUnknownFields(com.google.protobuf.UnknownFieldSet.getDefaultInstance())
        channels[channel] = session.copy(options = merged.build())
        return true
    }
}
