package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import java.security.MessageDigest

/**
 * The address and passphrase of an access point or group as last read, and whether that address
 * outlived a restart. For a group, a restart is a new create.
 */
data class SoftApAddressRecord(
    val ip: String,
    val passphraseDigest: String,
    val bootCount: Int?,
    val spannedBoot: Boolean,
    /**
     * The access point's BSSID the restart proof was earned on, or null where none is tracked (a
     * WiFi Direct group grades its own). Android's stock Wi-Fi HAL gives an access point one random
     * address per boot, so it repeats at every bring-up inside a boot and moves at the next one.
     */
    val bssid: String? = null,
)

/** The access point an endpoint went out on: everything the phone stored and will insist on. */
data class SoftApAdvertisedEndpoint(
    val ssid: String,
    val passphraseDigest: String,
    val bssid: String,
    val ip: String,
)

/**
 * The half of an access point's or WiFi Direct group's identity that a name and BSSID do not cover.
 *
 * The phone dials the address it was given, and tethering picks a random one per boot since R, so
 * the address has to repeat across a restart before an endpoint is safe. The same holds for an
 * access point's BSSID where the Wi-Fi HAL randomises it per boot (Android 10's default): the
 * restart proof belongs to the BSSID it was earned on. It only ever demotes.
 */
object SoftApEndpointStabilityPolicy {

    data class Verdict(
        val stability: GroupIdentityStability,
        val remember: SoftApAddressRecord?,
        val reason: String?,
    )

    fun passphraseDigest(passphrase: String): String =
        MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Grades [ip] and [passphrase] against [previous], on top of the name-and-BSSID [identity].
     *
     * A null [bootCount] means the platform cannot say, which is below API 24 where tethering used a
     * fixed address, so a repeat there is enough. [readNotCreated] marks a read of a surviving group,
     * which repeats by construction and so proves nothing.
     */
    fun grade(
        identity: GroupIdentityStability,
        ip: String,
        passphrase: String,
        bootCount: Int?,
        previous: SoftApAddressRecord?,
        readNotCreated: Boolean = false,
        network: String = "access point",
        bssid: String? = null,
    ): Verdict {
        if (ip.isBlank()) {
            return Verdict(demote(identity, GroupIdentityStability.UNPROVEN), null, "no address could be read for the $network")
        }
        val digest = passphraseDigest(passphrase)
        if (previous == null) {
            return Verdict(
                demote(identity, GroupIdentityStability.UNPROVEN),
                SoftApAddressRecord(ip, digest, bootCount, spannedBoot = false, bssid = bssid),
                "first reading of the $network's address at $ip; it has to come back after a restart",
            )
        }
        if (bssid != null && previous.bssid != null && !previous.bssid.equals(bssid, ignoreCase = true)) {
            // The name-and-BSSID verdict compares bring-ups, and inside one boot a per-boot BSSID
            // repeats. Starting the restart proof again keeps that repeat from grading stable.
            return Verdict(
                demote(identity, GroupIdentityStability.CHANGED),
                SoftApAddressRecord(ip, digest, bootCount, spannedBoot = false, bssid = bssid),
                "the $network came back with a new BSSID ($bssid, was ${previous.bssid}), and the phone would keep the old one",
            )
        }
        if (previous.ip != ip || previous.passphraseDigest != digest) {
            val moved = if (previous.ip != ip) "its address moved from ${previous.ip} to $ip" else "its password changed"
            return Verdict(
                demote(identity, GroupIdentityStability.CHANGED),
                SoftApAddressRecord(ip, digest, bootCount, spannedBoot = false, bssid = bssid),
                "the $network came back but $moved, and the phone would keep the old one",
            )
        }
        val spanned = previous.spannedBoot || (!readNotCreated &&
            (bootCount == null || previous.bootCount == null || bootCount != previous.bootCount))
        // A record from before the BSSID was tracked earned its proof on an unknown BSSID: start it again on
        // this one. A stable access point pays one more restart; a per-boot one never gets a free pass.
        val adopts = bssid != null && previous.bssid == null
        val record = previous.copy(spannedBoot = spanned && !adopts, bssid = bssid ?: previous.bssid)
        if (adopts && identity == GroupIdentityStability.STABLE) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, record.copy(bootCount = bootCount),
                "the $network's BSSID $bssid is tracked from now on; it has to come back after a restart",
            )
        }
        if (!spanned && identity == GroupIdentityStability.STABLE) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, record,
                "same address $ip, but not yet seen across a restart, which is when tethering picks a new one",
            )
        }
        return Verdict(identity, record, null)
    }

    /** How the group now up came to be. UNDECIDED is until a bring-up chooses to adopt or create. */
    enum class GroupOrigin { CREATED, READ, UNDECIDED }

    /** A fresh or stopped manager has chosen nothing, and a group from before it can still be up. */
    const val UNDECIDED_UNTIL_BRING_UP = true

    fun groupOrigin(wasRead: Boolean, adoptDecisionPending: Boolean): GroupOrigin = when {
        adoptDecisionPending -> GroupOrigin.UNDECIDED
        wasRead -> GroupOrigin.READ
        else -> GroupOrigin.CREATED
    }

    /** Only a create proves the address; an undecided group may be a surviving one. */
    fun gradesAsRead(origin: GroupOrigin): Boolean = origin != GroupOrigin.CREATED

    /** An undecided group or an unread address writes nothing, so a later delivery's grade is recorded. */
    fun recordsAddress(origin: GroupOrigin, ip: String?): Boolean =
        origin != GroupOrigin.UNDECIDED && !ip.isNullOrBlank()

    /** What moved between the access point an endpoint went out on and this one, or null if nothing did. */
    fun movedSinceAdvertised(
        advertised: SoftApAdvertisedEndpoint?,
        ssid: String,
        passphrase: String,
        bssid: String,
        ip: String,
    ): String? {
        if (advertised == null) return null
        val moved = buildList {
            if (advertised.ssid != ssid) add("name ${advertised.ssid} -> $ssid")
            if (advertised.passphraseDigest != passphraseDigest(passphrase)) add("password")
            if (!advertised.bssid.equals(bssid, ignoreCase = true)) add("BSSID ${advertised.bssid} -> $bssid")
            if (advertised.ip != ip) add("address ${advertised.ip} -> $ip")
        }
        return moved.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** The move of a group's IP under the network an endpoint went out on, or null if it did not move. */
    fun groupAddressMoved(advertised: SoftApAdvertisedEndpoint?, ssid: String, passphrase: String, ip: String): String? {
        if (advertised == null || !sameNetwork(advertised, ssid, passphrase) || advertised.ip == ip) return null
        return "address ${advertised.ip} -> $ip"
    }

    /** Whether [ssid] and [passphrase] are the network the endpoint went out on; a rotation is not. */
    fun sameNetwork(advertised: SoftApAdvertisedEndpoint?, ssid: String, passphrase: String): Boolean =
        advertised != null && advertised.ssid == ssid && advertised.passphraseDigest == passphraseDigest(passphrase)

    fun advertisement(ssid: String, passphrase: String, bssid: String, ip: String): SoftApAdvertisedEndpoint? {
        if (ssid.isBlank() || ip.isBlank()) return null
        return SoftApAdvertisedEndpoint(ssid, passphraseDigest(passphrase), bssid, ip)
    }

    /** CHANGED and RENAMED already say more than this policy can, so only the two open verdicts move. */
    private fun demote(identity: GroupIdentityStability, to: GroupIdentityStability): GroupIdentityStability =
        if (identity == GroupIdentityStability.STABLE || identity == GroupIdentityStability.UNPROVEN) to else identity
}
