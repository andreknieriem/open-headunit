package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.P2pGroupIdentityPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.StoredP2pIdentity

/**
 * Whether the WiFi Direct network a WPP endpoint was advertised under has to come back once.
 *
 * A phone holding that endpoint joins only the network it was stored with, never falls back to
 * Bluetooth, and can only be told to drop it by a rejection on that network. So a rename brings
 * the advertised pair up one more time, endpoint withheld, before the new one goes on the air.
 */
object EndpointRetirementPolicy {

    /**
     * Whether the next create owes the advertised network a retirement.
     *
     * Where the app names the group, anything but the kept pair is a move. Below API 29 the platform
     * reinvokes its own profile, so only a pending purge moves the name.
     */
    fun owed(
        advertised: StoredP2pIdentity?,
        keepIdentity: Boolean,
        kept: StoredP2pIdentity?,
        appNamesGroup: Boolean,
        rotationPending: Boolean,
    ): Boolean {
        if (advertised == null) return false
        return if (appNamesGroup) !(keepIdentity && kept == advertised)
        else !keepIdentity || rotationPending
    }

    /** Whether the group on the air is the advertised one being retired rather than a live identity. */
    fun isRetiring(
        advertised: StoredP2pIdentity?,
        keepIdentity: Boolean,
        kept: StoredP2pIdentity?,
        appNamesGroup: Boolean,
        rotationPending: Boolean,
        liveName: String,
        livePassphrase: String,
    ): Boolean =
        owed(advertised, keepIdentity, kept, appNamesGroup, rotationPending) &&
            advertised?.networkName == liveName && advertised.passphrase == livePassphrase

    /** The pair to remember once an endpoint went out on [strategy], or null when there is none. */
    fun recordsAdvertisement(strategy: NativeStrategy, liveName: String, livePassphrase: String): StoredP2pIdentity? {
        if (strategy != NativeStrategy.WIFI_DIRECT) return null
        return StoredP2pIdentity(liveName, livePassphrase).takeIf { P2pGroupIdentityPolicy.isValid(it) }
    }
}
