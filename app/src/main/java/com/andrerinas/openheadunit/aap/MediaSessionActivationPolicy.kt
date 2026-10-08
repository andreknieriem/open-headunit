package com.andrerinas.openheadunit.aap

/**
 * Policy governing activation and metadata syncing of Open Headunit's local MediaSessionCompat.
 *
 * In remote sessions (e.g., standard USB or Wi-Fi to another phone), Open Headunit's local
 * MediaSessionCompat captures hardware media keys (steering wheel buttons) and relays them
 * to the remote phone via AAP, while optionally receiving metadata from the phone to update
 * the local OS media notification/lockscreen.
 *
 * In Self-Mode (where Android Auto, Open Headunit, and media apps like Spotify/YouTube Music run
 * on the exact same device), activating Open Headunit's MediaSessionCompat registers Open Headunit
 * itself as an active media player with Android's MediaSessionService. This causes Android Auto
 * and the OS to collide: AA perceives Open Headunit as the media playback app, and when navigation
 * apps request transient audio focus, media playback routing and hardware buttons break (#929).
 *
 * Therefore, in Self-Mode / loopback sessions, Open Headunit's local MediaSessionCompat must NOT
 * be active or sync metadata.
 */
object MediaSessionActivationPolicy {

    /**
     * Determines whether Open Headunit's MediaSessionCompat should be active.
     *
     * @param isLoopbackSession true if the connection is a loopback session (self-mode socket)
     * @param isSelfModeLauncherActive true if the SelfModeLauncherManager is currently active
     * @return true if the session is remote (USB / Wi-Fi) and should activate the MediaSession
     */
    fun shouldActivate(
        isLoopbackSession: Boolean,
        isSelfModeLauncherActive: Boolean
    ): Boolean {
        return !isLoopbackSession && !isSelfModeLauncherActive
    }

    /**
     * Determines whether Android Auto media metadata and playback status from the phone
     * should be synced to Open Headunit's local MediaSessionCompat.
     *
     * @param isLoopbackSession true if the connection is a loopback session
     * @param isSelfModeLauncherActive true if the SelfModeLauncherManager is currently active
     * @param userPreference whether the user has enabled metadata syncing in settings
     * @return true if metadata should be pushed to the local MediaSessionCompat
     */
    fun shouldSyncAaMetadata(
        isLoopbackSession: Boolean,
        isSelfModeLauncherActive: Boolean,
        userPreference: Boolean
    ): Boolean {
        if (isLoopbackSession || isSelfModeLauncherActive) {
            return false
        }
        return userPreference
    }
}
