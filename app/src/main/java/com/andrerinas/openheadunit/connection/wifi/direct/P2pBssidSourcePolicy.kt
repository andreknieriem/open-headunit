package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBssidPolicy

/**
 * Which address a WiFi Direct group is announced on, ranked.
 *
 * A read beats a hand-typed value: where the hardware answers, an address that disagrees with it
 * can only be wrong, and a wrong one is announced as a network the phone never finds and pins the
 * TCP endpoint to it. So the settings answer only where every rung came back empty, and the access
 * point's own address, being a different interface, comes last of all.
 */
object P2pBssidSourcePolicy {

    enum class Source {
        /** A rung that asked the hardware. */
        DETECTED,

        /** The WiFi Direct setting, typed for this transport. */
        P2P_SETTING,

        /** The access point setting, standing in for a group it does not belong to. */
        ACCESS_POINT_SETTING,

        /** Nothing usable anywhere. */
        NONE,
    }

    data class Choice(val bssid: String, val source: Source) {
        /**
         * Whether the address the phone is told is one the user fixed, which is what lets the
         * identity verdict call it stable. The access point's stand-in does not count: it is
         * constant, but it is the wrong interface and an endpoint pinned to it strands the phone.
         */
        val fixedByUser: Boolean get() = source == Source.P2P_SETTING

        /**
         * Whether this address can be the group's own, which is the only thing the identity verdict
         * can compare. The access point's stand-in cannot: measured against the last group it reads
         * as "the address moved", and it is then remembered as the address the next group is judged
         * against, so one stand-in grades every later group unstable.
         */
        val canBeGroupsOwn: Boolean get() = source != Source.ACCESS_POINT_SETTING && bssid.isNotEmpty()
    }

    /** @param detected the best address any rung produced, or null/masked where none did. */
    fun resolve(detected: String?, p2pOverride: String?, apOverride: String?): Choice {
        if (SoftApBssidPolicy.isUsable(detected)) {
            return Choice(SoftApBssidPolicy.choose(detected, emptyList()), Source.DETECTED)
        }
        SoftApBssidPolicy.choose(p2pOverride, emptyList())
            .takeIf { it.isNotEmpty() }
            ?.let { return Choice(it, Source.P2P_SETTING) }
        SoftApBssidPolicy.choose(apOverride, emptyList())
            .takeIf { it.isNotEmpty() }
            ?.let { return Choice(it, Source.ACCESS_POINT_SETTING) }
        return Choice("", Source.NONE)
    }

    /** The label for the log, so a capture says which tier answered. */
    fun label(source: Source): String = when (source) {
        Source.DETECTED -> "detected"
        Source.P2P_SETTING -> "WiFi Direct setting"
        Source.ACCESS_POINT_SETTING -> "access point setting (stand-in)"
        Source.NONE -> "nothing"
    }
}
