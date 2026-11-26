package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationUpdateSharePolicyTest {
    private val own = "com.andrerinas.headunitrevived"

    @Test
    fun `nothing chosen sends no addressed copy`() {
        assertEquals(emptyList<String>(), NavigationUpdateSharePolicy.targetPackages(true, emptySet(), own))
    }

    @Test
    fun `each chosen app gets its own copy in a stable order`() {
        assertEquals(
            listOf("club.x70.polosa", "com.example.dash"),
            NavigationUpdateSharePolicy.targetPackages(true, setOf("com.example.dash", "club.x70.polosa"), own)
        )
    }

    @Test
    fun `the playstore flavor shares with nobody even when a package is stored`() {
        assertEquals(
            emptyList<String>(),
            NavigationUpdateSharePolicy.targetPackages(false, setOf("club.x70.polosa"), own)
        )
    }

    @Test
    fun `this app is not sent a second copy`() {
        assertEquals(
            listOf("club.x70.polosa"),
            NavigationUpdateSharePolicy.targetPackages(true, setOf(own, "club.x70.polosa"), own)
        )
    }

    @Test
    fun `blank or malformed names never become a target`() {
        assertEquals(
            listOf("club.x70.polosa"),
            NavigationUpdateSharePolicy.targetPackages(
                true,
                setOf("", "   ", "*", "polosa", ".club.x70", "club..x70", "1club.x70", "club.x70.polosa"),
                own
            )
        )
    }

    @Test
    fun `surrounding spaces are trimmed and duplicates collapse`() {
        assertEquals(
            listOf("club.x70.polosa"),
            NavigationUpdateSharePolicy.targetPackages(true, setOf(" club.x70.polosa", "club.x70.polosa "), own)
        )
    }

    @Test
    fun `package name check follows the application id rules`() {
        assertTrue(NavigationUpdateSharePolicy.isPackageName("club.x70.polosa"))
        assertTrue(NavigationUpdateSharePolicy.isPackageName("com.example_app.Dash2"))
        assertFalse(NavigationUpdateSharePolicy.isPackageName("polosa"))
        assertFalse(NavigationUpdateSharePolicy.isPackageName("club.70x"))
        assertFalse(NavigationUpdateSharePolicy.isPackageName("club.x70."))
    }
}
