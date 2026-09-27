package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * How a new WiFi Direct network identity reaches the air, and whether it may go out now.
 *
 * From API 29 the app names the group itself, so a recreate carries the new pair. Below 29 every
 * create reinvokes the platform's own stored profile, so that profile has to be deleted first or
 * the name never changes.
 */
object P2pIdentityRotationPolicy {

    /** The first level where `WifiP2pConfig.Builder` can name the group this app creates. */
    const val NAMED_CREATE_SDK = 29

    enum class Mechanism { NAMED_RECREATE, PURGE_PROFILE_THEN_RECREATE }

    fun mechanism(sdkInt: Int): Mechanism =
        if (sdkInt >= NAMED_CREATE_SDK) Mechanism.NAMED_RECREATE
        else Mechanism.PURGE_PROFILE_THEN_RECREATE

    /**
     * Whether the group may be recreated for the new identity right now. A projecting session or a
     * handshake mid-exchange owns the group, and taking it away costs the user the drive.
     */
    fun applyNow(
        sessionLive: Boolean,
        handshakeInFlight: Boolean,
        nativeWifiDirectActive: Boolean,
        createOutstanding: Boolean = false,
    ): Boolean = deferralReason(sessionLive, handshakeInFlight, nativeWifiDirectActive, createOutstanding) == null

    /** Why the rotation waits for the next create, or null when it can be applied now. */
    fun deferralReason(
        sessionLive: Boolean,
        handshakeInFlight: Boolean,
        nativeWifiDirectActive: Boolean,
        createOutstanding: Boolean = false,
    ): String? = when {
        !nativeWifiDirectActive ->
            "no WiFi Direct group of this mode is up, so there is nothing to rename yet"
        sessionLive ->
            "a phone is projecting on this group and recreating it would end the session"
        handshakeInFlight ->
            "a phone is being handed the credentials right now and would be given a network that is going away"
        // A second create under one the platform is still holding is answered BUSY for two minutes.
        createOutstanding ->
            "a group is still being asked for, and the next create after it carries the new identity"
        else -> null
    }

    /**
     * Whether a group already up is read rather than torn down and asked for again.
     *
     * Above [NAMED_CREATE_SDK] the whole pair is the test, not just the name: a survivor matched on
     * name alone served the phone the old passphrase after the stored one changed. Below it the
     * platform names the group and nothing was requested, so any group of ours is read, which is the
     * one kind of unit that cannot afford a recreate.
     */
    fun readsExistingGroup(
        sdkInt: Int,
        isGroupOwner: Boolean,
        liveName: String?,
        requestedName: String,
        livePassphrase: String?,
        requestedPassphrase: String,
    ): Boolean = isGroupOwner && (sdkInt < NAMED_CREATE_SDK ||
        (liveName == requestedName && livePassphrase == requestedPassphrase))

    /**
     * Whether the platform's stored profile must be deleted before the create.
     *
     * Below 29 it is the only rename lever there is, and it is also how "a new network on every
     * connection" can be honoured on a platform that otherwise reinvokes the same profile forever.
     */
    fun purgeBeforeCreate(sdkInt: Int, keepIdentity: Boolean, rotationPending: Boolean): Boolean =
        sdkInt < NAMED_CREATE_SDK && (rotationPending || !keepIdentity)
}
