package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.ProjectionExitMenuPolicy.Default
import com.andrerinas.openheadunit.aap.ProjectionExitMenuPolicy.Input
import com.andrerinas.openheadunit.aap.ProjectionExitMenuPolicy.Option
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionExitMenuPolicyTest {

    private fun input(
        isHomeApp: Boolean = false,
        pipWorks: Boolean = true,
        nativeWireless: Boolean = false,
        externalBtModule: Boolean = false,
        wifiDirectGroup: Boolean = false,
    ) = Input(isHomeApp, pipWorks, nativeWireless, externalBtModule, wifiDirectGroup)

    @Test
    fun `outside the home role the full menu is offered`() {
        assertEquals(
            listOf(Option.STOP, Option.PIP, Option.BACKGROUND, Option.SETTINGS),
            ProjectionExitMenuPolicy.options(input())
        )
    }

    @Test
    fun `as the home app the menu drops what the home reaches`() {
        assertEquals(listOf(Option.STOP, Option.BACKGROUND), ProjectionExitMenuPolicy.options(input(isHomeApp = true)))
    }

    @Test
    fun `stay ready is kept on the short menu over WiFi Direct`() {
        val options = ProjectionExitMenuPolicy.options(
            input(isHomeApp = true, nativeWireless = true, wifiDirectGroup = true)
        )
        assertEquals(listOf(Option.STOP, Option.END_STAY_ARMED, Option.BACKGROUND, Option.SWITCH_PHONE), options)
    }

    @Test
    fun `stay ready is only for a group this app created`() {
        val options = ProjectionExitMenuPolicy.options(input(nativeWireless = true, wifiDirectGroup = false))
        assertTrue(Option.END_STAY_ARMED !in options)
    }

    @Test
    fun `picture-in-picture is not offered where it would do nothing`() {
        assertTrue(Option.PIP !in ProjectionExitMenuPolicy.options(input(pipWorks = false)))
    }

    @Test
    fun `an external Bluetooth module has no phone switch`() {
        val options = ProjectionExitMenuPolicy.options(input(nativeWireless = true, externalBtModule = true))
        assertTrue(Option.SWITCH_PHONE !in options)
    }

    @Test
    fun `ask each time leaves the exit dialog`() {
        assertNull(ProjectionExitMenuPolicy.resolve(Default.ASK, input()))
    }

    @Test
    fun `a stored favourite applies when the menu offers it`() {
        assertEquals(Option.BACKGROUND, ProjectionExitMenuPolicy.resolve(Default.BACKGROUND, input()))
    }

    @Test
    fun `a favourite missing from this session falls back to asking`() {
        assertNull(ProjectionExitMenuPolicy.resolve(Default.PIP, input(isHomeApp = true)))
        assertNull(ProjectionExitMenuPolicy.resolve(Default.END_STAY_ARMED, input()))
    }
}
