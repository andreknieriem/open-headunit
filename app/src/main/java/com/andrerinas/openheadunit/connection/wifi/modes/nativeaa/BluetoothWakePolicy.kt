package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import java.util.UUID

/**
 * Rules for the Native AA wake poke: which of the phone's Bluetooth records it may reach for, when
 * it may connect at all, and which stored MACs are worth keeping.
 *
 * The poke raises an ACL connection so the phone notices a wireless-capable head unit. It is
 * load-bearing — some units never connect without it — so nothing here switches it off. What the
 * rules decide is when a poke would cost more than it gains.
 *
 * Pure and unit-tested; the sockets live in `NativeAaHandshakeManager`.
 */
object BluetoothWakePolicy {

    /**
     * What one wake attempt did, rather than only whether the phone answered.
     *
     * [STOOD_DOWN] opened nothing, so it cost the phone nothing and is not an attempt spent; the
     * budgets that count attempts need the difference ([NativeDriverSelectionPolicy.wakeRoundSpent]).
     * [HELD_LINK] is the stand-down for [WakeReason.TARGET_CONNECTED] only: the target holds this
     * unit's hands-free link. A dead-link cycle or an unreadable target is a plain [STOOD_DOWN].
     */
    enum class WakeOutcome { ANSWERED, DIALLED, STOOD_DOWN, HELD_LINK, NOT_PAIRED }

    /** Handsfree Profile — Audio Gateway. The phone's hands-free side; a call rides on this. */
    val HFP_AG_UUID: UUID = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb")

    /**
     * Headset Profile — Audio Gateway. Long stored under the name `A2DP_SOURCE_UUID`, which it never
     * was: A2DP Source is `0000110a`. Both values are confirmed against
     * nisargjhaveri/WirelessAndroidAutoDongle and mossyhub/openautolink.
     */
    val HSP_AG_UUID: UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")

    /**
     * The records a poke tries, in order — openautolink's ConnectProfile fallback chain.
     *
     * Deliberately not a setting. A selectable HSP-AG-only mode was built and removed once the rig
     * measured a successful HSP-AG poke leaving this unit's hands-free link down for three minutes,
     * the same outcome as HFP-AG: a phone serves both records from one headset connection, so which
     * one is asked for was never the lever. [wakeDecision] is.
     */
    val POKE_TARGETS: List<UUID> = listOf(HFP_AG_UUID, HSP_AG_UUID)

    /**
     * Whether a hands-free service level connection can be opened over [uuid].
     *
     * Only Hands-Free. Headset speaks `AT+CKPD` and knows nothing of `BRSF` or `CIND`, so the walk
     * would answer every command with an error, reach a false "established" on those errors alone,
     * and then poll a channel that cannot answer it every two seconds.
     */
    fun carriesServiceLevelConnection(uuid: UUID): Boolean = uuid == HFP_AG_UUID

    /** Reader-facing name for a target, so a log says what was touched rather than a UUID. */
    fun profileName(uuid: UUID): String = when (uuid) {
        HFP_AG_UUID -> "HFP-AG"
        HSP_AG_UUID -> "HSP-AG"
        else -> uuid.toString()
    }

    /** What this head unit's own Bluetooth stack says about its hands-free link. */
    enum class HandsFreeLink {
        /** A link is up. Poking would take the phone's slot away from it. */
        CONNECTED,

        /** No link. Nothing for a poke to displace. */
        ABSENT,

        /** The adapter would not say. Pokes anyway, see [wakeDecision]. */
        UNREADABLE;

        companion object {
            /** Maps [com.andrerinas.openheadunit.utils.BluetoothHelper.handsFreeLinkState]'s
             *  three-valued answer, so the null case is named rather than implied. */
            fun of(connected: Boolean?): HandsFreeLink = when (connected) {
                true -> CONNECTED
                false -> ABSENT
                null -> UNREADABLE
            }
        }
    }

    /** What the poke target's own connection to this unit reads as, from `BluetoothDevice.isConnected()`. */
    enum class TargetLink {
        CONNECTED,
        ABSENT,

        /** The hidden method is unreachable on this ROM. Never taken as absence. */
        UNREADABLE;

        companion object {
            fun of(connected: Boolean?): TargetLink = when (connected) {
                true -> CONNECTED
                false -> ABSENT
                null -> UNREADABLE
            }
        }
    }

    /** Why a poke went out, or did not, with a hands-free link in the picture. */
    enum class WakeReason {
        /** No hands-free link in either role, so nothing a poke could displace. */
        NO_LINK,

        /** A driver switch names the phone being left as the link's holder. */
        SWITCH_TARGET,

        /** Only the gateway role is up: this unit is the phone side, so the peer is a car kit or headset. */
        GATEWAY_ONLY,

        /** The target holds no connection to this unit at all, so the link cannot be its. */
        TARGET_ABSENT,

        /** The target is connected while a link is up; taken as its link. */
        TARGET_CONNECTED,

        /** The target's state could not be read while our own client link is up. */
        TARGET_UNREADABLE
    }

    data class WakeDecision(val poke: Boolean, val reason: WakeReason)

    /** What a wake reason says about another device holding this unit's hands-free link. */
    enum class ForeignLink { RAISE, CLEAR, UNCHANGED }

    /**
     * Whether the record that another device holds this unit's hands-free link stands.
     *
     * The poke still goes out on [ForeignLink.RAISE]: the link is not the target's, so nothing of
     * the target's is taken. What it costs is that a unit serving one link at a time has none left
     * to give the phone it just woke, which is the connected profile Android Auto asks for.
     * An unreadable target answers nothing either way.
     */
    fun foreignHandsFreeLink(reason: WakeReason): ForeignLink = when (reason) {
        WakeReason.TARGET_ABSENT, WakeReason.SWITCH_TARGET -> ForeignLink.RAISE
        WakeReason.NO_LINK, WakeReason.GATEWAY_ONLY, WakeReason.TARGET_CONNECTED -> ForeignLink.CLEAR
        WakeReason.TARGET_UNREADABLE -> ForeignLink.UNCHANGED
    }

    /**
     * Whether the wake poke may run. A poke that connects takes the phone's single hands-free slot
     * and this unit's own client link to it is dropped to make room, so a link that could be the
     * target's stands the poke down. Every other link is somebody else's.
     */
    fun wakeDecision(
        clientRoleLink: HandsFreeLink,
        gatewayRoleLink: HandsFreeLink,
        targetLink: TargetLink,
        linkIsAnotherPhones: Boolean = false
    ): WakeDecision = when {
        clientRoleLink != HandsFreeLink.CONNECTED && gatewayRoleLink != HandsFreeLink.CONNECTED ->
            WakeDecision(true, WakeReason.NO_LINK)
        linkIsAnotherPhones -> WakeDecision(true, WakeReason.SWITCH_TARGET)
        clientRoleLink == HandsFreeLink.ABSENT -> WakeDecision(true, WakeReason.GATEWAY_ONLY)
        targetLink == TargetLink.CONNECTED -> WakeDecision(false, WakeReason.TARGET_CONNECTED)
        targetLink == TargetLink.ABSENT -> WakeDecision(true, WakeReason.TARGET_ABSENT)
        else -> WakeDecision(false, WakeReason.TARGET_UNREADABLE)
    }

    /** What a guard stand-down tells the wake loops: only a target that holds the link is [WakeOutcome.HELD_LINK]. */
    fun standDownOutcome(reason: WakeReason): WakeOutcome =
        if (reason == WakeReason.TARGET_CONNECTED) WakeOutcome.HELD_LINK else WakeOutcome.STOOD_DOWN

    /** What a stored Auto Start MAC's pairing state read as, when the poke went looking for it. */
    enum class BondReading {
        /** Paired. Safe to poke. */
        BONDED,

        /** A positive not-paired answer, taken from a working adapter. */
        NOT_BONDED,

        /** Not a Bluetooth address at all, so it can never become valid. */
        MALFORMED,

        /** The adapter is off, or would not answer. Says nothing about the device. */
        UNREADABLE
    }

    /**
     * Whether a poke may open a socket to a device in this state.
     *
     * Only a confirmed pairing. A poke is a raw RFCOMM `connect()` to a device we assume already
     * trusts us; against an unpaired one the OS starts a new pairing negotiation as a side effect,
     * which the user sees as the head unit asking to pair every time the phone's radio comes back.
     *
     * Strict where [shouldForget] is lenient: refusing to poke costs a retry seconds later.
     */
    fun mayPoke(reading: BondReading): Boolean = reading == BondReading.BONDED

    /**
     * Whether a stored MAC should be dropped from the Auto Start list.
     *
     * Only on evidence the device is genuinely gone. **Never on [BondReading.UNREADABLE]** —
     * `getBondState()` answers "not bonded" when the Bluetooth service is merely unavailable, so
     * treating that as unpaired would delete the user's configured device on any poke round that
     * landed while the adapter was off. This head unit's Bluetooth is known to cycle itself, so that
     * window is reachable, and the deletion is written straight through to device-protected storage
     * where nothing would restore it.
     *
     * Lenient where [mayPoke] is strict: a MAC kept one round too long costs nothing, because
     * [mayPoke] will not poke it.
     *
     * A manufacturer pairing cache is independent of Android's bonds. True preserves that
     * preference; null preserves it when the cache cannot be read. Neither permits an Android poke.
     * A syntactically invalid address is still removable regardless of provider availability.
     */
    fun shouldForget(reading: BondReading, manufacturerPairing: Boolean? = false): Boolean =
        reading == BondReading.MALFORMED ||
            (reading == BondReading.NOT_BONDED && manufacturerPairing == false)
}
