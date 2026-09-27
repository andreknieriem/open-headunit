package com.andrerinas.openheadunit.automation

import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.contract.HeadUnitCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCommandPolicyTest {

    private val open = AutomationCommandPolicy.State(externalConfigAllowed = true)
    private val closed = AutomationCommandPolicy.State(externalConfigAllowed = false)

    private class Extras(private val values: Map<String, String> = emptyMap()) :
        AutomationCommandPolicy.Extras {
        override fun string(key: String): String? = values[key]
        override fun flag(key: String): Boolean = values[key]?.lowercase() == "true"
    }

    private fun effects(
        action: String?,
        extras: Map<String, String> = emptyMap(),
        state: AutomationCommandPolicy.State = closed
    ) = AutomationCommandPolicy.effectsFor(action, Extras(extras), state)

    private fun single(
        action: String?,
        extras: Map<String, String> = emptyMap(),
        state: AutomationCommandPolicy.State = closed
    ): AutomationCommandPolicy.Effect = effects(action, extras, state).single()

    // --- the gate -------------------------------------------------------------------------------

    /**
     * The whole point of the split: control is open, configuration is not. A caller that can start
     * a session must not also be able to silently rewrite the unit's setup.
     */
    @Test
    fun `configuring verbs are refused while external configuration is off`() {
        val configuring = listOf(
            HeadUnitCommand.ACTION_SET_SETTINGS,
            HeadUnitCommand.ACTION_GET_SETTINGS,
            HeadUnitCommand.ACTION_RESET_SETTINGS,
            HeadUnitCommand.ACTION_SET_LOG_LEVEL,
            HeadUnitCommand.ACTION_START_LOG_CAPTURE,
            HeadUnitCommand.ACTION_STOP_LOG_CAPTURE,
            HeadUnitCommand.ACTION_EXPORT_LOG
        )
        for (action in configuring) {
            assertTrue(
                "$action should be refused with the gate off",
                single(action, mapOf(HeadUnitCommand.EXTRA_TEXT to "x")) is AutomationCommandPolicy.Effect.Refuse
            )
        }
    }

    @Test
    fun `control verbs work with the gate off`() {
        assertEquals(
            AutomationCommandPolicy.Effect.StartService(AapService.ACTION_DISCONNECT),
            single(HeadUnitCommand.ACTION_DISCONNECT)
        )
        assertEquals(
            AutomationCommandPolicy.Effect.StartService(
                AapService.ACTION_START_WIRELESS,
                flagExtras = mapOf(AapService.EXTRA_NO_UI to false)
            ),
            single(HeadUnitCommand.ACTION_START_WIRELESS)
        )
    }

    @Test
    fun `the gate opens the configuring verbs`() {
        assertEquals(
            AutomationCommandPolicy.Effect.ResetSettings,
            single(HeadUnitCommand.ACTION_RESET_SETTINGS, state = open)
        )
    }

    // --- relays ---------------------------------------------------------------------------------

    /**
     * The public stop action is not the string the service uses internally, and sending either one
     * to the wrong side is a no-op, so the mapping is the whole of this command.
     */
    @Test
    fun `both stop aliases reach the service's own stop action`() {
        assertEquals(
            AutomationCommandPolicy.Effect.StartService(AapService.ACTION_STOP_SERVICE),
            single(HeadUnitCommand.ACTION_STOP_SERVICE)
        )
        assertEquals(
            AutomationCommandPolicy.Effect.StartService(AapService.ACTION_STOP_SERVICE),
            single(HeadUnitCommand.ACTION_EXIT)
        )
    }

    @Test
    fun `every relayed verb maps onto a real service action`() {
        val expected = mapOf(
            HeadUnitCommand.ACTION_DISCONNECT to AapService.ACTION_DISCONNECT,
            HeadUnitCommand.ACTION_STOP_WIRELESS to AapService.ACTION_STOP_WIRELESS,
            HeadUnitCommand.ACTION_CANCEL_WIRELESS to AapService.ACTION_CANCEL_WIRELESS,
            HeadUnitCommand.ACTION_START_WIRELESS_SCAN to AapService.ACTION_START_WIRELESS_SCAN,
            HeadUnitCommand.ACTION_END_SESSION_STAY_ARMED to AapService.ACTION_END_SESSION_STAY_ARMED,
            HeadUnitCommand.ACTION_REFRESH_SENSORS to AapService.ACTION_REFRESH_SENSORS,
            HeadUnitCommand.ACTION_RESTART_AUDIO to AapService.ACTION_RESTART_AUDIO,
            HeadUnitCommand.ACTION_RAISE_PROJECTION to AapService.ACTION_RAISE_PROJECTION
        )
        for ((command, serviceAction) in expected) {
            assertEquals(
                command,
                AutomationCommandPolicy.Effect.StartService(serviceAction),
                single(command)
            )
        }
    }

    private val userUsbCheck = AutomationCommandPolicy.Effect.StartService(
        AapService.ACTION_CHECK_USB,
        flagExtras = mapOf(AapService.EXTRA_USER_REQUESTED to true)
    )

    @Test
    fun `a USB check is asked for by hand, so it lifts the status pill's X`() {
        assertEquals(userUsbCheck, single(HeadUnitCommand.ACTION_CHECK_USB))
    }

    // --- connect --------------------------------------------------------------------------------

    @Test
    fun `connect without an address checks USB instead`() {
        assertEquals(userUsbCheck, single(HeadUnitCommand.ACTION_CONNECT))
        // A blank address is the same as none; a shortcut with an empty field must not try to
        // open a socket to "".
        assertEquals(
            userUsbCheck,
            single(HeadUnitCommand.ACTION_CONNECT, mapOf(HeadUnitCommand.EXTRA_IP to "  "))
        )
    }

    @Test
    fun `connect with an address opens the head unit server socket`() {
        val effects = effects(HeadUnitCommand.ACTION_CONNECT, mapOf(HeadUnitCommand.EXTRA_IP to "192.168.1.25"))
        assertEquals(
            AutomationCommandPolicy.Effect.ConnectSocket("192.168.1.25", AutomationCommandPolicy.HEADUNIT_SERVER_PORT),
            effects.last()
        )
        assertEquals(5277, AutomationCommandPolicy.HEADUNIT_SERVER_PORT)
    }

    /** #310: connect in the background without taking the screen. */
    @Test
    fun `no_ui rides along on the service intent`() {
        val start = effects(
            HeadUnitCommand.ACTION_CONNECT,
            mapOf(HeadUnitCommand.EXTRA_IP to "10.0.0.1", HeadUnitCommand.EXTRA_NO_UI to "true")
        ).first() as AutomationCommandPolicy.Effect.StartService
        assertEquals(true, start.flagExtras[AapService.EXTRA_NO_UI])

        val selfMode = single(
            HeadUnitCommand.ACTION_START_SELF_MODE,
            mapOf(HeadUnitCommand.EXTRA_NO_UI to "true")
        ) as AutomationCommandPolicy.Effect.StartService
        assertEquals(true, selfMode.flagExtras[AapService.EXTRA_NO_UI])

        // It was a plain relay, so the flag was dropped before the service ever saw it.
        val wireless = single(
            HeadUnitCommand.ACTION_START_WIRELESS,
            mapOf(HeadUnitCommand.EXTRA_NO_UI to "true")
        ) as AutomationCommandPolicy.Effect.StartService
        assertEquals(AapService.ACTION_START_WIRELESS, wireless.action)
        assertEquals(true, wireless.flagExtras[AapService.EXTRA_NO_UI])
    }

    @Test
    fun `starting wireless defaults to raising the screen`() {
        val wireless = single(HeadUnitCommand.ACTION_START_WIRELESS)
            as AutomationCommandPolicy.Effect.StartService
        assertEquals(false, wireless.flagExtras[AapService.EXTRA_NO_UI])
    }

    @Test
    fun `self mode defaults to raising the screen`() {
        val selfMode = single(HeadUnitCommand.ACTION_START_SELF_MODE)
            as AutomationCommandPolicy.Effect.StartService
        assertEquals(false, selfMode.flagExtras[AapService.EXTRA_NO_UI])
    }

    // --- required extras ------------------------------------------------------------------------

    @Test
    fun `a poke without a MAC is refused rather than sent`() {
        assertTrue(single(HeadUnitCommand.ACTION_NATIVE_AA_POKE) is AutomationCommandPolicy.Effect.Refuse)
        assertEquals(
            AutomationCommandPolicy.Effect.StartService(
                AapService.ACTION_NATIVE_AA_POKE,
                stringExtras = mapOf(AapService.EXTRA_MAC to "AA:BB:CC:DD:EE:FF")
            ),
            single(HeadUnitCommand.ACTION_NATIVE_AA_POKE, mapOf(HeadUnitCommand.EXTRA_MAC to "AA:BB:CC:DD:EE:FF"))
        )
    }

    @Test
    fun `a cancel poke is relayed with no extras required`() {
        assertEquals(
            AutomationCommandPolicy.Effect.StartService(
                HeadUnitCommand.ACTION_NATIVE_AA_CANCEL_POKE
            ),
            single(HeadUnitCommand.ACTION_NATIVE_AA_CANCEL_POKE)
        )
    }

    @Test
    fun `the cancel poke action is the one the service answers to`() {
        assertEquals(
            "com.andrerinas.openheadunit.ACTION_NATIVE_AA_CANCEL_POKE",
            HeadUnitCommand.ACTION_NATIVE_AA_CANCEL_POKE
        )
    }

    @Test
    fun `nearby connect needs an endpoint`() {
        assertTrue(single(HeadUnitCommand.ACTION_NEARBY_CONNECT) is AutomationCommandPolicy.Effect.Refuse)
        assertEquals(
            AutomationCommandPolicy.Effect.StartService(
                AapService.ACTION_NEARBY_CONNECT,
                stringExtras = mapOf(AapService.EXTRA_ENDPOINT_ID to "ABCD")
            ),
            single(HeadUnitCommand.ACTION_NEARBY_CONNECT, mapOf(HeadUnitCommand.EXTRA_ENDPOINT_ID to "ABCD"))
        )
    }

    @Test
    fun `settings import needs json or a path`() {
        assertTrue(
            single(HeadUnitCommand.ACTION_SET_SETTINGS, state = open) is AutomationCommandPolicy.Effect.Refuse
        )
        assertEquals(
            AutomationCommandPolicy.Effect.ImportSettings("{}", null),
            single(HeadUnitCommand.ACTION_SET_SETTINGS, mapOf(HeadUnitCommand.EXTRA_JSON to "{}"), open)
        )
        assertEquals(
            AutomationCommandPolicy.Effect.ImportSettings(null, "/sdcard/a.json"),
            single(HeadUnitCommand.ACTION_SET_SETTINGS, mapOf(HeadUnitCommand.EXTRA_PATH to "/sdcard/a.json"), open)
        )
    }

    // --- validated values -----------------------------------------------------------------------

    @Test
    fun `night mode accepts only the three modes, in any case`() {
        assertEquals(
            AutomationCommandPolicy.Effect.SetNightMode("night"),
            single(HeadUnitCommand.ACTION_SET_NIGHT_MODE, mapOf(HeadUnitCommand.EXTRA_STATE to "NIGHT"))
        )
        assertTrue(
            single(HeadUnitCommand.ACTION_SET_NIGHT_MODE, mapOf(HeadUnitCommand.EXTRA_STATE to "dusk"))
                is AutomationCommandPolicy.Effect.Refuse
        )
        assertTrue(
            single(HeadUnitCommand.ACTION_SET_NIGHT_MODE) is AutomationCommandPolicy.Effect.Refuse
        )
    }

    @Test
    fun `log level accepts only real levels`() {
        assertEquals(
            AutomationCommandPolicy.Effect.SetLogLevel("verbose"),
            single(HeadUnitCommand.ACTION_SET_LOG_LEVEL, mapOf(HeadUnitCommand.EXTRA_LEVEL to "Verbose"), open)
        )
        assertTrue(
            single(HeadUnitCommand.ACTION_SET_LOG_LEVEL, mapOf(HeadUnitCommand.EXTRA_LEVEL to "loud"), open)
                is AutomationCommandPolicy.Effect.Refuse
        )
    }

    // --- unknown --------------------------------------------------------------------------------

    @Test
    fun `an unknown or absent action is refused, not ignored`() {
        assertTrue(single(null) is AutomationCommandPolicy.Effect.Refuse)
        assertTrue(single("com.example.NOPE") is AutomationCommandPolicy.Effect.Refuse)
    }

    @Test
    fun `a log marker needs no configuration switch`() {
        val e = effects(HeadUnitCommand.ACTION_LOG_MARKER, mapOf(HeadUnitCommand.EXTRA_TEXT to "x"), closed)
        assertTrue(e.none { it is AutomationCommandPolicy.Effect.Refuse })
    }

    /**
     * Three verbs kept the internal action strings they had before this surface existed. Both
     * spellings answer, or a script written from the documented names silently does nothing.
     */
    @Test
    fun `the documented and the original spellings both answer`() {
        for (pair in listOf(
            HeadUnitCommand.ACTION_RAISE_PROJECTION to HeadUnitCommand.LEGACY_ACTION_RAISE_PROJECTION,
            HeadUnitCommand.ACTION_REFRESH_SENSORS to HeadUnitCommand.LEGACY_ACTION_REFRESH_SENSORS,
            HeadUnitCommand.ACTION_RESTART_AUDIO to HeadUnitCommand.LEGACY_ACTION_RESTART_AUDIO
        )) {
            assertEquals(
                "the two spellings of ${pair.first} must do the same thing",
                effects(pair.first, emptyMap(), open),
                effects(pair.second, emptyMap(), open)
            )
        }
    }

    /**
     * The manifest cannot reference a Kotlin constant, so its action list is a hand-copy. Anything
     * spelled differently there is unreachable and silently does nothing.
     */
    @Test
    fun `every command the manifest advertises is one the policy answers`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val advertised = Regex("""<action android:name="(com\.andrerinas\.openheadunit\.[^"]+)"""")
            .findAll(manifest.substringAfter("AutomationReceiver").substringBefore("</receiver>"))
            .map { it.groupValues[1] }
            .toList()

        assertTrue("no actions found for the receiver", advertised.isNotEmpty())
        for (action in advertised) {
            assertTrue(
                "$action is advertised in the manifest but the policy does not answer it",
                effects(action, mapOf(HeadUnitCommand.EXTRA_TEXT to "x"), open).none {
                    it is AutomationCommandPolicy.Effect.Refuse &&
                        it.reason.startsWith("unknown action")
                }
            )
        }

        // The other direction: an action the policy answers but the manifest never advertises is
        // unreachable by an implicit broadcast, which is how every automation app sends.
        for (action in ALL_COMMANDS) {
            assertTrue(
                "$action is answered by the policy but missing from the manifest",
                advertised.contains(action)
            )
        }
    }

    private val ALL_COMMANDS = listOf(
        HeadUnitCommand.ACTION_CONNECT, HeadUnitCommand.ACTION_DISCONNECT,
        HeadUnitCommand.ACTION_START_SELF_MODE, HeadUnitCommand.ACTION_STOP_SERVICE,
        HeadUnitCommand.ACTION_EXIT, HeadUnitCommand.ACTION_SET_NIGHT_MODE,
        HeadUnitCommand.ACTION_START_WIRELESS, HeadUnitCommand.ACTION_STOP_WIRELESS,
        HeadUnitCommand.ACTION_CANCEL_WIRELESS,
        HeadUnitCommand.ACTION_START_WIRELESS_SCAN, HeadUnitCommand.ACTION_NATIVE_AA_POKE,
        HeadUnitCommand.ACTION_END_SESSION_STAY_ARMED,
        HeadUnitCommand.ACTION_NATIVE_AA_CANCEL_POKE,
        HeadUnitCommand.ACTION_NEARBY_CONNECT, HeadUnitCommand.ACTION_CHECK_USB,
        HeadUnitCommand.ACTION_REFRESH_SENSORS, HeadUnitCommand.ACTION_RESTART_AUDIO,
        HeadUnitCommand.ACTION_RAISE_PROJECTION, HeadUnitCommand.LEGACY_ACTION_REFRESH_SENSORS,
        HeadUnitCommand.LEGACY_ACTION_RESTART_AUDIO, HeadUnitCommand.LEGACY_ACTION_RAISE_PROJECTION,
        HeadUnitCommand.ACTION_QUERY_STATE, HeadUnitCommand.ACTION_SET_SETTINGS,
        HeadUnitCommand.ACTION_GET_SETTINGS, HeadUnitCommand.ACTION_RESET_SETTINGS,
        HeadUnitCommand.ACTION_SET_LOG_LEVEL, HeadUnitCommand.ACTION_START_LOG_CAPTURE,
        HeadUnitCommand.ACTION_STOP_LOG_CAPTURE, HeadUnitCommand.ACTION_EXPORT_LOG,
        HeadUnitCommand.ACTION_LOG_MARKER
    )
}
