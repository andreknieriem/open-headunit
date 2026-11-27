package com.andrerinas.openheadunit.aap.protocol.messages

/**
 * Which optional audio sinks service discovery announces.
 *
 * System sounds are not part of this decision: Android Auto requires a system-sound sink, and
 * `ServiceDiscoveryResponse` announces it unconditionally. These two are the ones a setting and
 * Self Mode can drop, and they are now separable — a navigation-only session keeps speech (map
 * guidance) while dropping media (music).
 *
 * The Self Mode half must be a fact about *this* session: it used to read a launcher flag that
 * outlived a failed launch, so a wireless session announced system sounds only and had no audio at
 * all.
 */
object AudioSinkAnnouncementPolicy {

    /**
     * [speech] carries navigation guidance and assistant replies (`ID_AU1`); [media] carries music
     * and other media apps (`ID_AUD`).
     */
    data class Announcements(val speech: Boolean, val media: Boolean)

    /**
     * Why Self Mode drops both sinks at all is not recorded anywhere in this repo and is not
     * settled; only the gating is. A session that is not Self Mode's must never be affected by it.
     *
     * Maps-only is the owner's narrower request: keep the map's voice, drop music. It applies to
     * sessions that would otherwise announce both, and it never resurrects a sink the setting or
     * Self Mode has already dropped.
     */
    fun announce(
        sinkEnabled: Boolean,
        isSelfModeSession: Boolean,
        mapsAudioOnly: Boolean,
    ): Announcements {
        if (!sinkEnabled || isSelfModeSession) return Announcements(speech = false, media = false)
        return Announcements(speech = true, media = !mapsAudioOnly)
    }
}
