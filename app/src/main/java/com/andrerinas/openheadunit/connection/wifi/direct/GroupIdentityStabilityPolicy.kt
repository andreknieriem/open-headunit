package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.MacAddressOrigin
import com.andrerinas.openheadunit.connection.wifi.MacAddressPolicy

/** Whether the network the phone is handed will be the same network next time. */
enum class GroupIdentityStability {
    /** Same name and same BSSID as the last group this unit hosted, or an address the user fixed. */
    STABLE,
    /** Nothing to compare against yet, or nothing readable to compare. */
    UNPROVEN,
    /** Same name, different BSSID: this unit re-addresses the group on every create. */
    CHANGED,
    /** The platform names the group itself and hands out a different name every create. */
    RENAMED,
    /** Not asked on this delivery. The Native transports both measure; nothing else needs to. */
    NOT_MEASURED,
}

/** What a group looked like from the outside, kept so the next one can be compared to it. */
data class ObservedP2pGroup(val ssid: String, val bssid: String)

/**
 * Decides, per unit and from evidence, whether the WiFi Direct group's identity repeats.
 *
 * A persistent group keeps its name and passphrase, but the platform is allowed to re-randomize
 * the group's own address on every create, and whether it does is a per-unit configuration this
 * app cannot read. The phone stores the BSSID beside the name and joins on both, so a record that
 * names a stale address is a network it can never find. The verdict is therefore measured, from
 * two consecutive bring-ups, and never assumed.
 */
object GroupIdentityStabilityPolicy {

    /**
     * How many creates must hand back a different name before it is a property of the unit rather
     * than a comparison that has not settled.
     *
     * Three: one is ordinary, two could be a rotation the user asked for, and by the third nothing
     * else explains it.
     */
    const val NAME_CHANGES_BEFORE_MEASURED = 3

    data class Verdict(
        val stability: GroupIdentityStability,
        /** What to keep for the next comparison, or null when this bring-up taught nothing. */
        val remember: ObservedP2pGroup?,
        val reason: String,
        /**
         * Creates whose name did not match the last one, to be kept for the next. A repeat resets
         * it only where the app can name the group: below Q it cannot, so a name that came back
         * once there is luck rather than a property and the count carries on.
         */
        val nameChanges: Int = 0,
    )

    /**
     * @param appNamesGroup whether this platform lets the app ask for a name at all. Below API 29
     *   `WifiP2pConfig.Builder` does not exist, so the create reinvokes whatever profile the
     *   platform kept and a changed name is the platform's doing rather than a rotation of ours.
     * @param nameChangesSoFar the previous verdict's [Verdict.nameChanges].
     * @param bssidIsGroupsOwn whether the address read belongs to this group's own interface. A
     *   stand-in from another one is a well-formed address that answers a different question, and
     *   comparing it reads as a move and is then remembered as what the next group is judged on.
     * @param readNotCreated whether this group was found already up and adopted rather than created.
     * @param previousStability what the creates before it earned, which a read hands straight back.
     */
    fun assess(
        keepIdentity: Boolean,
        requestedName: String?,
        ssid: String,
        bssid: String,
        bssidUsable: Boolean,
        staticOverride: Boolean,
        previous: ObservedP2pGroup?,
        appNamesGroup: Boolean = true,
        nameChangesSoFar: Int = 0,
        bssidIsGroupsOwn: Boolean = true,
        readNotCreated: Boolean = false,
        previousStability: GroupIdentityStability = GroupIdentityStability.UNPROVEN,
    ): Verdict {
        // The count survives every branch below that observed nothing about the name, or a run with
        // one unreadable BSSID in it erases a measurement built from the bring-ups either side. A
        // bring-up is assessed twice on some units, so only a repeat under Q's naming API resets it.
        if (!keepIdentity) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, null, "a new network is made on every create",
                nameChanges = nameChangesSoFar,
            )
        }
        if (requestedName != null && requestedName != ssid) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, null,
                "the platform named the group $ssid instead of the $requestedName asked for",
                nameChanges = nameChangesSoFar,
            )
        }
        if (!bssidUsable) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, null, "no BSSID could be read for this group",
                nameChanges = nameChangesSoFar,
            )
        }
        if (!bssidIsGroupsOwn) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, null,
                "the address announced ($bssid) belongs to another interface, so it says nothing about this group",
                nameChanges = nameChangesSoFar,
            )
        }
        val observed = ObservedP2pGroup(ssid, bssid)
        // A group found already up is the same group instance, so its address repeating says
        // nothing about what a create would do. Promoting on it graded a unit that re-addresses
        // every create as stable, and the endpoint that went out on the strength of it is the one
        // the phone then pins and cannot find.
        if (readNotCreated) {
            // A stored verdict outlives the build that wrote it, and a unit whose group survives
            // every relaunch never creates again to correct it. Below Q a measured rename settles
            // it whatever was stored: no API there names a group, so nothing made the name repeat.
            if (renameMeasured(appNamesGroup, nameChangesSoFar)) {
                return Verdict(
                    GroupIdentityStability.RENAMED, null,
                    "this group was already up and was read rather than created, and this platform " +
                        "has named the group itself on $nameChangesSoFar creates",
                    nameChanges = nameChangesSoFar,
                )
            }
            return Verdict(
                previousStability, null,
                "this group was already up and was read rather than created, so nothing new was measured",
                nameChanges = nameChangesSoFar,
            )
        }
        if (staticOverride) {
            return Verdict(
                GroupIdentityStability.STABLE, observed,
                "the static BSSID setting fixes the address the phone is told",
                nameChanges = nameChangesSoFar,
            )
        }
        return when {
            // A generated address is the platform's per-create one, so it already answers what a
            // second bring-up would. Saying "the next one decides" of it promises nothing.
            previous == null && MacAddressPolicy.origin(bssid) == MacAddressOrigin.GENERATED -> Verdict(
                GroupIdentityStability.CHANGED, observed,
                "first group under this name, and $bssid is generated rather than this interface's own, so the next create moves it",
                nameChanges = nameChangesSoFar,
            )
            previous == null -> Verdict(
                GroupIdentityStability.UNPROVEN, observed,
                "first group under this name; the next one decides",
                nameChanges = nameChangesSoFar,
            )
            previous.ssid != ssid -> nameChangedVerdict(observed, previous, appNamesGroup, nameChangesSoFar)
            previous.bssid == bssid -> repeatedIdentityVerdict(observed, appNamesGroup, nameChangesSoFar)
            else -> Verdict(
                GroupIdentityStability.CHANGED, observed,
                "same name but the BSSID moved from ${previous.bssid} to $bssid; this unit re-addresses the group on every create",
                nameChanges = repeatedNameChanges(appNamesGroup, nameChangesSoFar),
            )
        }
    }

    /**
     * What a repeated name leaves the count at.
     *
     * Zero where the app asked for the name and got it, because that is the lever working. Below Q
     * there is no lever, and a unit is assessed once per group-info callback rather than once per
     * create, so a repeat there is as likely to be the leftover group seen again as a real one.
     */
    private fun repeatedNameChanges(appNamesGroup: Boolean, nameChangesSoFar: Int): Int =
        if (appNamesGroup) 0 else nameChangesSoFar

    /**
     * Whether this unit has been seen to rename its own group, which below Q nothing can undo.
     *
     * Asked by both the repeat branch and the read branch, so a name that comes back cannot mean
     * one thing when it was created and another when it was only found.
     */
    private fun renameMeasured(appNamesGroup: Boolean, nameChangesSoFar: Int): Boolean =
        !appNamesGroup && nameChangesSoFar >= NAME_CHANGES_BEFORE_MEASURED

    /**
     * A name and address that both repeated.
     *
     * Below Q, once the rename has been measured, this is the surviving group seen again rather
     * than a create that held: no API there names a group, so nothing could have made the name come
     * back. Promoting on it hands the phone an endpoint and STATIC credentials for a network whose
     * next create renames it, which is the one failure the verdict exists to prevent.
     */
    private fun repeatedIdentityVerdict(
        observed: ObservedP2pGroup,
        appNamesGroup: Boolean,
        nameChangesSoFar: Int,
    ): Verdict {
        if (renameMeasured(appNamesGroup, nameChangesSoFar)) {
            return Verdict(
                GroupIdentityStability.RENAMED, observed,
                "same name and same BSSID, but this platform has named the group itself on " +
                    "$nameChangesSoFar creates, so this is that group seen again rather than a " +
                    "name that held",
                nameChanges = nameChangesSoFar,
            )
        }
        return Verdict(
            GroupIdentityStability.STABLE, observed,
            "same name and same BSSID as the last group",
            nameChanges = repeatedNameChanges(appNamesGroup, nameChangesSoFar),
        )
    }

    /**
     * A name that did not repeat. On a platform that lets us ask for one this is transient, and the
     * next create settles it. On one that does not, it is the platform choosing, and after
     * [NAME_CHANGES_BEFORE_MEASURED] of them saying "the next one decides" is a promise that will
     * never be kept: no API below 29 names a group, so nothing this app can do will change it.
     */
    private fun nameChangedVerdict(
        observed: ObservedP2pGroup,
        previous: ObservedP2pGroup,
        appNamesGroup: Boolean,
        nameChangesSoFar: Int,
    ): Verdict {
        if (appNamesGroup) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, observed,
                "the name changed since the last group (${previous.ssid}), so the address cannot be compared yet",
                nameChanges = 0,
            )
        }
        val changes = nameChangesSoFar + 1
        if (changes < NAME_CHANGES_BEFORE_MEASURED) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, observed,
                "the name changed since the last group (${previous.ssid}), so the address cannot be compared yet",
                nameChanges = changes,
            )
        }
        return Verdict(
            GroupIdentityStability.RENAMED, observed,
            "this platform names the group itself and has picked a different name on $changes " +
                "creates, so the kept identity cannot apply here and no setting reaches it",
            nameChanges = changes,
        )
    }

    fun label(stability: GroupIdentityStability): String = when (stability) {
        GroupIdentityStability.STABLE -> "yes"
        GroupIdentityStability.UNPROVEN -> "unproven"
        GroupIdentityStability.CHANGED -> "no"
        GroupIdentityStability.RENAMED -> "no (the platform names it)"
        GroupIdentityStability.NOT_MEASURED -> "not measured"
    }
}
