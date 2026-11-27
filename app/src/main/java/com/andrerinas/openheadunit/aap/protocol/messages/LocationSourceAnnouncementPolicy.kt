package com.andrerinas.openheadunit.aap.protocol.messages

/**
 * Whether this head unit offers itself to Android Auto as the car's location source.
 *
 * Self Mode projects this device to itself, so the fix we would relay is one Android Auto can read
 * directly. Announcing it only moves the phone off a live read onto a copy a round trip staler, and
 * `GpsLocation`'s resend backstop re-stamps a fix up to a minute old as current, which is a lie
 * told to a client that could have asked the chip itself.
 */
object LocationSourceAnnouncementPolicy {

    /**
     * @param isSelfModeSession `CommManager.isLoopbackSession` — asked of the session, because a
     *                          launcher flag outlives a launch that never connected.
     */
    fun announcesLocation(useGpsForNavigation: Boolean, isSelfModeSession: Boolean): Boolean =
        useGpsForNavigation && !isSelfModeSession
}
