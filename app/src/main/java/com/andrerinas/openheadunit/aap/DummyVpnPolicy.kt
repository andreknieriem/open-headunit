package com.andrerinas.openheadunit.aap

/**
 * Who owns the dummy VPN, and which teardowns are allowed to take it down.
 *
 * The VPN used to be stopped from the last line of [AapService.stopWirelessServer], which
 * [AapService.initWifiMode] calls on every mode change. A user reported the VPN icon vanishing on
 * their second connection, and their log shows exactly that: `initWifiMode` at 03:28:00.278,
 * `VpnControl.stopVpn` three milliseconds later. The wireless server never owned it.
 *
 * Ownership is what replaces that. A teardown may only stop a VPN whose owner it is responsible
 * for, and a VPN this app did not start is never touched at all.
 */
object DummyVpnPolicy {

    /** Why the VPN is up. `null` means we did not start it, so we must not stop it. */
    enum class Owner {
        /** Offline Self Mode, which needs a non-null `activeNetwork` for our own process. */
        SELF_MODE,

        /** [com.andrerinas.openheadunit.utils.Settings.keepDummyVpnDuringSession]: up for one session. */
        SESSION,
    }

    /**
     * What is tearing down. There is deliberately no reason for a wireless re-init or a wireless
     * stop: those paths no longer touch the VPN at all, which is the fix. If one ever needs to
     * again, that is a decision to make here rather than a call to add there.
     */
    enum class Reason {
        /** A projection session ended, whatever it was for. */
        SESSION_ENDED,

        /** Self Mode brought the VPN up and no phone ever arrived. */
        SELF_MODE_NEVER_CONNECTED,

        SERVICE_DESTROYED,
    }

    fun shouldStop(owner: Owner?, reason: Reason): Boolean {
        // A VPN we did not start is never ours to stop, whatever is tearing down.
        if (owner == null) return false
        return when (reason) {
            Reason.SESSION_ENDED, Reason.SERVICE_DESTROYED -> true
            Reason.SELF_MODE_NEVER_CONNECTED -> owner == Owner.SELF_MODE
        }
    }

    /**
     * Whether a connection that is not Self Mode should bring the VPN up.
     *
     * [nativeWirelessSession] is not redundant with [keepDuringSession]. The toggle is only
     * rendered inside the Native AA block of the settings list, so a user who turns it on and then
     * switches connection mode is left with a `true` preference they can no longer see. Without
     * this argument that invisible preference would put a VPN on a USB session.
     */
    fun shouldStartForSession(
        keepDuringSession: Boolean,
        nativeWirelessSession: Boolean,
        currentOwner: Owner?,
        selfMode: Boolean,
        vpnAvailable: Boolean,
        alreadyPrepared: Boolean,
    ): Boolean =
        keepDuringSession &&
            nativeWirelessSession &&
            vpnAvailable &&
            alreadyPrepared &&
            !selfMode &&
            currentOwner == null
}
