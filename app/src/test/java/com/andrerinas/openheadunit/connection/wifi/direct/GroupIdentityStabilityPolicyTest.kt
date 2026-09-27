package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.MacAddressOrigin
import com.andrerinas.openheadunit.connection.wifi.MacAddressPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupIdentityStabilityPolicyTest {

    private val name = "DIRECT-K7-HeadUnit"
    private val a = "DE:B3:88:55:B3:92"
    private val b = "DE:B3:88:55:B3:93"

    /** The head unit's own station address, which is what the sysfs last resort answered with. */
    private val station = "06:54:F8:6E:60:B2"

    /** Globally unique, so an interface's own rather than one the platform generated per create. */
    private val factory = "00:27:15:43:06:6A"

    private fun assess(
        keep: Boolean = true,
        requested: String? = name,
        ssid: String = name,
        bssid: String = a,
        usable: Boolean = true,
        override: Boolean = false,
        previous: ObservedP2pGroup? = null,
        appNamesGroup: Boolean = true,
        nameChangesSoFar: Int = 0,
        groupsOwn: Boolean = true,
    ) = GroupIdentityStabilityPolicy.assess(
        keep, requested, ssid, bssid, usable, override, previous, appNamesGroup, nameChangesSoFar,
        groupsOwn
    )

    @Test
    fun `a first group at a generated address is called changed, and is remembered`() {
        // The address already says the platform made it for this create, so there is nothing for a
        // second bring-up to add. Still remembered, so a kept group can prove itself stable below.
        val v = assess()
        assertEquals(GroupIdentityStability.CHANGED, v.stability)
        assertEquals(ObservedP2pGroup(name, a), v.remember)
        assertTrue(v.reason.contains("generated"))
    }

    @Test
    fun `a first group at the interface's own address is unproven, and waits`() {
        val v = assess(bssid = factory)
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertEquals(ObservedP2pGroup(name, factory), v.remember)
    }

    @Test
    fun `a generated address that comes back is stable, because the group was kept`() {
        // The point of holding the group: the first verdict predicts a move, and not creating one
        // is what stops it. A generated address must not veto stability on the next read.
        val first = assess()
        val second = assess(previous = first.remember)
        assertEquals(GroupIdentityStability.STABLE, second.stability)
    }

    @Test
    fun `the same name and address twice is stable`() {
        val v = assess(previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.STABLE, v.stability)
        assertEquals(ObservedP2pGroup(name, a), v.remember)
    }

    @Test
    fun `the same name with a moved address is changed, and the new address is what is kept`() {
        val v = assess(bssid = b, previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.CHANGED, v.stability)
        assertEquals(ObservedP2pGroup(name, b), v.remember)
        assertTrue(v.reason, v.reason.contains(a) && v.reason.contains(b))
    }

    @Test
    fun `a unit that re-addresses every create never reaches stable`() {
        var previous: ObservedP2pGroup? = null
        val seen = mutableListOf<GroupIdentityStability>()
        for (mac in listOf(a, b, a, b)) {
            val v = assess(bssid = mac, previous = previous)
            seen += v.stability
            previous = v.remember
        }
        assertEquals(List(4) { GroupIdentityStability.CHANGED }, seen)
    }

    @Test
    fun `a new identity resets the comparison rather than reading as changed`() {
        val v = assess(ssid = "DIRECT-Q2-HeadUnit", requested = "DIRECT-Q2-HeadUnit", previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertEquals(ObservedP2pGroup("DIRECT-Q2-HeadUnit", a), v.remember)
    }

    @Test
    fun `a static BSSID setting is stable at once`() {
        val v = assess(override = true)
        assertEquals(GroupIdentityStability.STABLE, v.stability)
    }

    @Test
    fun `not keeping the identity is never stable and teaches nothing`() {
        val v = assess(keep = false, previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertNull(v.remember)
    }

    @Test
    fun `a group the platform renamed cannot be the one the phone stored`() {
        val v = assess(ssid = "DIRECT-zz-Android", previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertNull(v.remember)
        assertTrue(v.reason.contains(name))
    }

    @Test
    fun `an unreadable BSSID is unproven and does not overwrite what was learned`() {
        val v = assess(bssid = "02:00:00:00:00:00", usable = false, previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertNull(v.remember)
    }

    // --- a platform that names the group itself -------------------------------------------------

    @Test
    fun `a changed name stays unproven while the app is the one that could have renamed it`() {
        // Above API 29 the app asks for the name, so a different one is a rotation of ours and the
        // next create settles it however many times it happens.
        var changes = 0
        repeat(5) {
            val v = assess(
                requested = "DIRECT-zz-New", ssid = "DIRECT-zz-New",
                previous = ObservedP2pGroup(name, a), appNamesGroup = true, nameChangesSoFar = changes
            )
            assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
            assertEquals(0, v.nameChanges)
            changes = v.nameChanges
        }
    }

    @Test
    fun `below the naming API a changed name is counted, and measured on the third`() {
        var changes = 0
        val seen = mutableListOf<GroupIdentityStability>()
        repeat(GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED) { i ->
            val v = assess(
                requested = null, ssid = "DIRECT-x$i-HeadUnit",
                previous = ObservedP2pGroup(name, a), appNamesGroup = false, nameChangesSoFar = changes
            )
            seen += v.stability
            changes = v.nameChanges
        }
        assertEquals(GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED, changes)
        assertEquals(GroupIdentityStability.UNPROVEN, seen.first())
        assertEquals(GroupIdentityStability.RENAMED, seen.last())
    }

    @Test
    fun `the measured verdict says the setting cannot reach it, rather than that the next one decides`() {
        val v = assess(
            requested = null, ssid = "DIRECT-zz-Other", previous = ObservedP2pGroup(name, a),
            appNamesGroup = false,
            nameChangesSoFar = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED,
        )
        assertEquals(GroupIdentityStability.RENAMED, v.stability)
        assertTrue(v.reason.contains("no setting reaches it"))
        assertFalse("the reason must not promise a comparison that will never happen",
            v.reason.contains("cannot be compared yet"))
    }

    @Test
    fun `a name that repeats clears the count where the app asked for that name`() {
        val v = assess(
            previous = ObservedP2pGroup(name, a), appNamesGroup = true,
            nameChangesSoFar = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED - 1,
        )
        assertEquals(GroupIdentityStability.STABLE, v.stability)
        assertEquals(0, v.nameChanges)
    }

    @Test
    fun `below the naming API a repeated name does not clear the count`() {
        // Measured on an API 19 tablet: every bring-up is assessed twice, and the first callback
        // re-reads the group the last one left up, which repeats the name without a create.
        val near = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED - 1
        for (bssid in listOf(a, b)) {
            val v = assess(
                requested = null, bssid = bssid, previous = ObservedP2pGroup(name, a),
                appNamesGroup = false, nameChangesSoFar = near,
            )
            assertEquals(near, v.nameChanges)
        }
    }

    @Test
    fun `four bring-ups of two callbacks each reach the measured verdict on the third`() {
        // The shape the API 19 tablet produces and the branch tests all missed: a leftover group
        // re-read under the old name, then the new one, per bring-up. Names from that round.
        val names = listOf("DIRECT-LR-Nav", "DIRECT-oX-Nav", "DIRECT-bA-Nav", "DIRECT-e6-Nav")
        var changes = 0
        var previous = ObservedP2pGroup(names[0], a)
        val reached = mutableListOf<GroupIdentityStability>()
        for (settled in names.drop(1)) {
            val ephemeral = assess(
                requested = null, ssid = previous.ssid, bssid = b, previous = previous,
                appNamesGroup = false, nameChangesSoFar = changes,
            )
            changes = ephemeral.nameChanges
            previous = ephemeral.remember!!
            val v = assess(
                requested = null, ssid = settled, bssid = a, previous = previous,
                appNamesGroup = false, nameChangesSoFar = changes,
            )
            changes = v.nameChanges
            previous = v.remember!!
            reached += v.stability
        }
        assertEquals(
            listOf(
                GroupIdentityStability.UNPROVEN,
                GroupIdentityStability.UNPROVEN,
                GroupIdentityStability.RENAMED,
            ),
            reached
        )
    }

    @Test
    fun `a branch that observed nothing about the name carries the count rather than erasing it`() {
        val so_far = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED - 1
        val previous = ObservedP2pGroup(name, a)
        assertEquals(so_far, assess(keep = false, nameChangesSoFar = so_far).nameChanges)
        assertEquals(so_far, assess(ssid = "DIRECT-zz-Other", nameChangesSoFar = so_far).nameChanges)
        assertEquals(so_far, assess(usable = false, nameChangesSoFar = so_far).nameChanges)
        assertEquals(so_far, assess(previous = null, nameChangesSoFar = so_far).nameChanges)
        assertEquals(0, assess(previous = previous, nameChangesSoFar = so_far).nameChanges)
        assertEquals(0, assess(bssid = b, previous = previous, nameChangesSoFar = so_far).nameChanges)
    }

    @Test
    fun `one unreadable BSSID between two renames does not lose the first of them`() {
        var changes = assess(
            requested = null, ssid = "DIRECT-x1-HeadUnit", previous = ObservedP2pGroup(name, a),
            appNamesGroup = false, nameChangesSoFar = 0
        ).nameChanges
        assertEquals(1, changes)
        changes = assess(
            requested = null, usable = false, appNamesGroup = false, nameChangesSoFar = changes
        ).nameChanges
        changes = assess(
            requested = null, ssid = "DIRECT-x3-HeadUnit", previous = ObservedP2pGroup(name, a),
            appNamesGroup = false, nameChangesSoFar = changes
        ).nameChanges
        assertEquals(GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED - 1, changes)
    }

    @Test
    fun `the existing verdicts are untouched by the new inputs defaulting`() {
        assertEquals(GroupIdentityStability.CHANGED, assess().stability)
        assertEquals(GroupIdentityStability.UNPROVEN, assess(bssid = factory).stability)
        assertEquals(GroupIdentityStability.STABLE, assess(previous = ObservedP2pGroup(name, a)).stability)
        assertEquals(
            GroupIdentityStability.CHANGED,
            assess(bssid = b, previous = ObservedP2pGroup(name, a)).stability
        )
        assertEquals(GroupIdentityStability.UNPROVEN, assess(keep = false).stability)
        assertEquals(GroupIdentityStability.STABLE, assess(override = true).stability)
    }

    @Test
    fun `every verdict carries a reason and every stability a label`() {
        for (v in listOf(assess(), assess(keep = false), assess(override = true),
            assess(previous = ObservedP2pGroup(name, a)), assess(bssid = b, previous = ObservedP2pGroup(name, a)))) {
            assertTrue(v.reason.isNotBlank())
        }
        for (s in GroupIdentityStability.values()) {
            assertTrue(GroupIdentityStabilityPolicy.label(s).isNotBlank())
        }
    }

    @Test
    fun `an address from another interface is not compared and is not remembered`() {
        val v = assess(bssid = station, previous = ObservedP2pGroup(name, a), groupsOwn = false)
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertNull(v.remember)
        assertTrue(v.reason.contains("another interface"))
    }

    @Test
    fun `a stand-in does not grade a group that never moved as changed`() {
        // The round-3 shape: the group kept its address, the chain answered with the station's, and
        // the verdict read "the BSSID moved" against an address no group ever had.
        assertEquals(
            GroupIdentityStability.CHANGED,
            assess(bssid = station, previous = ObservedP2pGroup(name, a)).stability
        )
        assertEquals(
            GroupIdentityStability.UNPROVEN,
            assess(bssid = station, previous = ObservedP2pGroup(name, a), groupsOwn = false).stability
        )
    }

    @Test
    fun `an unreadable address is still answered before the interface question`() {
        val v = assess(usable = false, groupsOwn = false)
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertTrue(v.reason.contains("no BSSID could be read"))
    }

    @Test
    fun `the name change count survives an address from another interface`() {
        assertEquals(2, assess(groupsOwn = false, nameChangesSoFar = 2).nameChanges)
    }

    @Test
    fun `a group read as it was found is not evidence its address repeats`() {
        // The MT50 re-addresses every create. Force-stop, relaunch, and the surviving group is
        // compared with the record it wrote itself, which matches because it is the same group.
        val group = ObservedP2pGroup("DIRECT-hu", "26:E2:6D:4E:57:18")
        val verdict = GroupIdentityStabilityPolicy.assess(
            keepIdentity = true,
            requestedName = null,
            ssid = group.ssid,
            bssid = group.bssid,
            bssidUsable = true,
            staticOverride = false,
            previous = group,
            readNotCreated = true,
            previousStability = GroupIdentityStability.CHANGED,
        )
        assertEquals(GroupIdentityStability.CHANGED, verdict.stability)
        assertNull("a read teaches nothing, so it must not become the yardstick", verdict.remember)
    }

    @Test
    fun `a read does not cost a unit the stability its creates earned`() {
        val group = ObservedP2pGroup("DIRECT-hu", "00:27:15:43:06:6A")
        val verdict = GroupIdentityStabilityPolicy.assess(
            keepIdentity = true,
            requestedName = null,
            ssid = group.ssid,
            bssid = group.bssid,
            bssidUsable = true,
            staticOverride = false,
            previous = group,
            readNotCreated = true,
            previousStability = GroupIdentityStability.STABLE,
        )
        assertEquals(GroupIdentityStability.STABLE, verdict.stability)
        assertNull(verdict.remember)
    }

    @Test
    fun `a first read on a unit with no record promises nothing`() {
        val verdict = GroupIdentityStabilityPolicy.assess(
            keepIdentity = true,
            requestedName = null,
            ssid = "DIRECT-hu",
            bssid = "26:E2:6D:4E:57:18",
            bssidUsable = true,
            staticOverride = false,
            previous = null,
            readNotCreated = true,
            previousStability = GroupIdentityStability.UNPROVEN,
        )
        assertEquals(GroupIdentityStability.UNPROVEN, verdict.stability)
        assertNull(verdict.remember)
    }

    @Test
    fun `the same sighting created rather than read still grades stable`() {
        // The complement, so the arm cannot be read as disabling the measurement altogether.
        val group = ObservedP2pGroup("DIRECT-hu", "00:27:15:43:06:6A")
        val verdict = GroupIdentityStabilityPolicy.assess(
            keepIdentity = true,
            requestedName = null,
            ssid = group.ssid,
            bssid = group.bssid,
            bssidUsable = true,
            staticOverride = false,
            previous = group,
            readNotCreated = false,
        )
        assertEquals(GroupIdentityStability.STABLE, verdict.stability)
        assertEquals(group, verdict.remember)
    }

    @Test
    fun `an address the platform generated still earns stable by coming back`() {
        // The access point is graded through this too. Soft AP randomisation is persistent per
        // configuration since Android 11, so generated says the platform made it up, not that it
        // moves, and grading on the bit alone denied every such unit the endpoint for good.
        val seen = ObservedP2pGroup("headunit-ap", "26:E2:6D:4E:57:18")
        assertEquals(MacAddressOrigin.GENERATED, MacAddressPolicy.origin(seen.bssid))
        val verdict = GroupIdentityStabilityPolicy.assess(
            keepIdentity = true,
            requestedName = null,
            ssid = seen.ssid,
            bssid = seen.bssid,
            bssidUsable = true,
            staticOverride = false,
            previous = seen,
        )
        assertEquals(GroupIdentityStability.STABLE, verdict.stability)
    }

    @Test
    fun `an address that moved between bring-ups is changed however it was made`() {
        val verdict = GroupIdentityStabilityPolicy.assess(
            keepIdentity = true,
            requestedName = null,
            ssid = "headunit-ap",
            bssid = "26:E2:6D:4E:57:18",
            bssidUsable = true,
            staticOverride = false,
            previous = ObservedP2pGroup("headunit-ap", "E6:50:68:13:92:11"),
        )
        assertEquals(GroupIdentityStability.CHANGED, verdict.stability)
    }

    @Test
    fun `below the naming API a measured rename is not retired by the same group seen again`() {
        // Measured on an API 19 tablet: the bring-up adopts the group the last run left up, so the
        // name and the address both repeat without a create. Graded stable, that unit is handed the
        // TCP endpoint and STATIC credentials for a network its next create renames.
        val v = assess(
            requested = null, bssid = a, previous = ObservedP2pGroup(name, a),
            appNamesGroup = false,
            nameChangesSoFar = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED,
        )
        assertEquals(GroupIdentityStability.RENAMED, v.stability)
        assertEquals(GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED, v.nameChanges)
        assertTrue(v.reason.contains("that group seen again"))
    }

    @Test
    fun `below the naming API a repeat before the rename is measured is still stable`() {
        // A pre-Q unit whose platform does keep the profile never reaches the cap, so the endpoint
        // it earns is untouched by the rule above.
        val v = assess(
            requested = null, bssid = a, previous = ObservedP2pGroup(name, a),
            appNamesGroup = false,
            nameChangesSoFar = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED - 1,
        )
        assertEquals(GroupIdentityStability.STABLE, v.stability)
    }

    @Test
    fun `where the app names the group a repeat is the lever working, whatever the count`() {
        val v = assess(
            bssid = a, previous = ObservedP2pGroup(name, a), appNamesGroup = true,
            nameChangesSoFar = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED + 2,
        )
        assertEquals(GroupIdentityStability.STABLE, v.stability)
        assertEquals(0, v.nameChanges)
    }
    @Test
    fun `a read does not hand back a stable stored before the rename was measured`() {
        // The stored verdict outlives the build that wrote it, and a group that survives every
        // relaunch is never created again to correct it, so the read branch has to ask too.
        val group = ObservedP2pGroup(name, a)
        val v = GroupIdentityStabilityPolicy.assess(
            keepIdentity = true,
            requestedName = null,
            ssid = group.ssid,
            bssid = group.bssid,
            bssidUsable = true,
            staticOverride = false,
            previous = group,
            appNamesGroup = false,
            nameChangesSoFar = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED,
            readNotCreated = true,
            previousStability = GroupIdentityStability.STABLE,
        )
        assertEquals(GroupIdentityStability.RENAMED, v.stability)
        assertNull("a read still teaches nothing, whatever it answers", v.remember)
    }

    @Test
    fun `a read below the rename threshold still hands the stored verdict back`() {
        // The boundary: a pre-Q unit whose platform keeps the profile never reaches the cap, and
        // what its creates earned is not taken away from it by a bring-up that only looked.
        val group = ObservedP2pGroup(name, a)
        val v = GroupIdentityStabilityPolicy.assess(
            keepIdentity = true,
            requestedName = null,
            ssid = group.ssid,
            bssid = group.bssid,
            bssidUsable = true,
            staticOverride = false,
            previous = group,
            appNamesGroup = false,
            nameChangesSoFar = GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED - 1,
            readNotCreated = true,
            previousStability = GroupIdentityStability.STABLE,
        )
        assertEquals(GroupIdentityStability.STABLE, v.stability)
    }
}
