package com.andrerinas.openheadunit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartFixPolicyTest {

    private val pkg = "com.andrerinas.headunitrevived"

    @Test
    fun `duraspeed counts when either property is 1`() {
        assertTrue(AutoStartFixPolicy.duraSpeedSupported(listOf("1\n", "")))
        assertTrue(AutoStartFixPolicy.duraSpeedSupported(listOf("", "1")))
        assertFalse(AutoStartFixPolicy.duraSpeedSupported(listOf("0", "")))
        assertFalse(AutoStartFixPolicy.duraSpeedSupported(emptyList()))
    }

    @Test
    fun `duraspeed is toggled through 1 to 0 in both tables`() {
        assertEquals(
            "settings put global setting.duraspeed.enabled 1; sleep 1; settings put global setting.duraspeed.enabled 0; " +
                "settings put system setting.duraspeed.enabled 1; sleep 1; settings put system setting.duraspeed.enabled 0",
            AutoStartFixPolicy.duraSpeedOffCommand(),
        )
    }

    @Test
    fun `nothing to exempt from before Android 6`() {
        assertFalse(AutoStartFixPolicy.exemptionsApply(22))
        assertEquals(emptyList<String>(), AutoStartFixPolicy.exemptionCommands(22, pkg, grant = true))
        assertTrue(AutoStartFixPolicy.exemptionsApply(23))
    }

    @Test
    fun `each android version gets the exemptions it has`() {
        assertEquals(listOf("dumpsys deviceidle whitelist +$pkg"), AutoStartFixPolicy.exemptionCommands(23, pkg, true))
        assertEquals(2, AutoStartFixPolicy.exemptionCommands(26, pkg, true).size)
        assertEquals(
            listOf(
                "dumpsys deviceidle whitelist +$pkg",
                "appops set $pkg RUN_IN_BACKGROUND allow",
                "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            ),
            AutoStartFixPolicy.exemptionCommands(28, pkg, true),
        )
    }

    @Test
    fun `turning them off hands them back to the system`() {
        assertEquals(
            listOf(
                "dumpsys deviceidle whitelist -$pkg",
                "appops set $pkg RUN_IN_BACKGROUND default",
                "appops set $pkg RUN_ANY_IN_BACKGROUND default",
            ),
            AutoStartFixPolicy.exemptionCommands(34, pkg, false),
        )
    }
}
