package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.Route
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.WifiButton
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDiscoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole truth table, because four call sites read this decision and the only thing keeping them
 * from disagreeing is that they all read it from here.
 */
class ExternalBtTransportPolicyTest {
    private fun legacy(evidence: String?, answer: Boolean?): ZbtDiscoveryPolicy.Snapshot {
        val mode = if (evidence == null) ZbtDiscoveryPolicy.Mode.NONE else ZbtDiscoveryPolicy.Mode.REACHABILITY
        return ZbtDiscoveryPolicy.Snapshot(ZbtDiscoveryPolicy.Decision(mode, evidence), answer)
    }

    private fun candidate(evidence: String, answer: Boolean?) =
        ZbtDiscoveryPolicy.Snapshot(ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.IDENTITY, evidence), answer)

    private val evidence = "/dev/rf_serial exists"

    private fun route(
        externalBtEvidence: String? = null,
        zbt: Boolean = false,
        ignore: Boolean = false,
        daemonReachable: Boolean? = null
    ) = ExternalBtTransportPolicy.route(legacy(externalBtEvidence, daemonReachable), zbt, ignore)

    private fun needsMeasurement(
        externalBtEvidence: String? = null,
        zbt: Boolean = false,
        ignore: Boolean = false,
        cached: Boolean? = null
    ) = ExternalBtTransportPolicy.needsDaemonMeasurement(legacy(externalBtEvidence, cached), zbt, ignore)

    private fun refuses(
        externalBtEvidence: String? = null,
        zbt: Boolean = false,
        ignore: Boolean = false,
        cached: Boolean? = null
    ) = ExternalBtTransportPolicy.refusesBringUp(legacy(externalBtEvidence, cached), zbt, ignore)

    @Test
    fun `a unit with no external-BT markers is normal, whatever the settings say`() {
        // Both settings are only ever offered on detected units, but nothing stops one surviving in
        // prefs after a restore or a firmware change. Neither must divert a working unit.
        assertEquals(Route.NORMAL, route())
        assertEquals(Route.NORMAL, route(zbt = true))
        assertEquals(Route.NORMAL, route(ignore = true))
        assertEquals(Route.NORMAL, route(zbt = true, ignore = true))
    }

    @Test
    fun `external Bluetooth with both switches off is refused, as it is today`() {
        assertEquals(Route.BLOCKED, route(evidence))
    }

    @Test
    fun `the opt-in takes the module route`() {
        assertEquals(Route.ZBT, route(evidence, zbt = true))
    }

    @Test
    fun `detection alone never takes the module route`() {
        // ExternalBtPolicy identifies a class of hardware; some of that class reaches its module
        // over Binder and has nothing on the daemon's port at all. Routing on detection would send
        // those units down a transport that cannot exist for them.
        assertEquals(Route.BLOCKED, route(evidence, zbt = false))
    }

    @Test
    fun `the compatibility override still forces this unit's own radio`() {
        // nativeAaIgnoreExternalBt is a shipped, translated setting. The module transport must add
        // a route, not remove the one users already have.
        assertEquals(Route.NORMAL, route(evidence, ignore = true))
    }

    @Test
    fun `the module route wins when both escapes are on`() {
        // They answer the same detection differently: one uses a radio the phone is not bonded to,
        // the other the chip it is. Only the second can carry bytes.
        assertEquals(Route.ZBT, route(evidence, zbt = true, ignore = true))
    }

    @Test
    fun `with the module transport off, nothing about the old behaviour changed`() {
        // The regression fence. On every unit that has never opted in, this must reduce exactly to
        // what externalBtOverridden decided before the module route existed.
        assertEquals(Route.NORMAL, route(null, zbt = false, ignore = false))
        assertEquals(Route.NORMAL, route(null, zbt = false, ignore = true))
        assertEquals(Route.BLOCKED, route(evidence, zbt = false, ignore = false))
        assertEquals(Route.NORMAL, route(evidence, zbt = false, ignore = true))
    }

    // ---------------------------------------------------------------------------------------------
    // Reachability, once it is measured rather than predicted
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a daemon that answers takes the module route with no setting at all`() {
        assertEquals(Route.ZBT, route(evidence, daemonReachable = true))
    }

    @Test
    fun `a daemon that refuses is blocked, exactly as before`() {
        assertEquals(Route.BLOCKED, route(evidence, daemonReachable = false))
    }

    @Test
    fun `a unit that has not been asked is blocked, not assumed`() {
        assertEquals(Route.BLOCKED, route(evidence, daemonReachable = null))
    }

    @Test
    fun `a setting still outranks what the daemon said`() {
        // The toggle is a manual override for a daemon that is slow or intermittent, so it wins
        // over a refusal; and a user asking for their own radio wins over an answer.
        assertEquals(Route.ZBT, route(evidence, zbt = true, daemonReachable = false))
        assertEquals(Route.NORMAL, route(evidence, ignore = true, daemonReachable = true))
    }

    @Test
    fun `no markers means normal whatever the daemon said`() {
        assertEquals(Route.NORMAL, route(null, daemonReachable = true))
        assertEquals(Route.NORMAL, route(null, daemonReachable = false))
    }

    @Test
    fun `ordinary hardware is never dialled`() {
        // The ANR fence. A unit with no markers must never pay for a socket connect.
        assertFalse(needsMeasurement(null))
        assertFalse(needsMeasurement(null, zbt = true))
    }

    @Test
    fun `nothing is dialled once a setting or an answer has decided`() {
        assertFalse(needsMeasurement(evidence, zbt = true))
        assertFalse(needsMeasurement(evidence, ignore = true))
        assertFalse(needsMeasurement(evidence, cached = true))
        assertFalse(needsMeasurement(evidence, cached = false))
    }

    @Test
    fun `a flagged unit with nothing decided is the one case that dials`() {
        assertTrue(needsMeasurement(evidence))
    }

    @Test
    fun `a daemon nobody has asked yet is not a refusal`() {
        // The measurement lives behind the bring-up this answer gates, so answering "refused" here
        // meant the dial never ran and the route could never open. Measured on two units in #978.
        assertEquals(Route.BLOCKED, route(evidence))
        assertFalse(refuses(evidence))
    }

    @Test
    fun `a daemon that was asked and said no is a refusal`() {
        assertTrue(refuses(evidence, cached = false))
    }

    @Test
    fun `a route that is not blocked never refuses`() {
        assertFalse(refuses(null))
        assertFalse(refuses(evidence, zbt = true))
        assertFalse(refuses(evidence, ignore = true))
        assertFalse(refuses(evidence, cached = true))
    }

    @Test
    fun `a connecting ZJ socket takes the route when the ZBT daemon was never asked`() {
        val snapshot = legacy(evidence, null)
        assertEquals(Route.ZJ_SOCKET, ExternalBtTransportPolicy.route(snapshot, false, false, zjSocketConnects = true))
        assertFalse(ExternalBtTransportPolicy.refusesBringUp(snapshot, false, false, zjSocketConnects = true))
        assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(snapshot, false, false, zjSocketConnects = true))
    }

    private val socketAnswers = listOf<Boolean?>(null, false, true)

    @Test
    fun `a ZBT daemon that answers keeps the route over a connecting socket`() {
        assertEquals(Route.ZBT, ExternalBtTransportPolicy.route(legacy(evidence, true), false, false, zjSocketConnects = true))
    }

    @Test
    fun `a refused ZBT daemon and a connecting socket take the socket`() {
        val snapshot = legacy(evidence, false)
        assertEquals(Route.ZJ_SOCKET, ExternalBtTransportPolicy.route(snapshot, false, false, zjSocketConnects = true))
        assertFalse(ExternalBtTransportPolicy.refusesBringUp(snapshot, false, false, zjSocketConnects = true))
    }

    @Test
    fun `an HCT service beats a connecting socket`() {
        assertEquals(
            Route.HCT,
            ExternalBtTransportPolicy.route(legacy(evidence, false), false, false, hctServiceAvailable = true, zjSocketConnects = true)
        )
    }

    @Test
    fun `BLINK and FYT routes ignore the socket answer`() {
        for (blink in listOf(false, true))
            for (fyt in listOf(null, "FYT"))
                for (zbt in listOf(false, true))
                    for (ignore in listOf(false, true))
                        for (cached in socketAnswers) {
                            val snapshot = legacy(evidence, cached)
                            val without = ExternalBtTransportPolicy.route(snapshot, zbt, ignore, blink, fyt)
                            val with = ExternalBtTransportPolicy.route(snapshot, zbt, ignore, blink, fyt, zjSocketConnects = true)
                            if (blink || fyt != null) assertEquals("$blink/$fyt/$zbt/$ignore/$cached", without, with)
                        }
    }

    @Test
    fun `the socket toggle takes the route whatever the socket answer is`() {
        for (answer in socketAnswers)
            assertEquals(Route.ZJ_SOCKET, ExternalBtTransportPolicy.route(legacy(null, null), false, false, zjSocketEnabled = true, zjSocketConnects = answer))
    }

    @Test
    fun `the ZBT toggle wins over the socket toggle on a marked unit`() {
        assertEquals(Route.ZBT, ExternalBtTransportPolicy.route(legacy(evidence, null), true, false, zjSocketEnabled = true))
    }

    @Test
    fun `a refused daemon and a refused socket stay blocked`() {
        val snapshot = legacy(evidence, false)
        assertEquals(Route.BLOCKED, ExternalBtTransportPolicy.route(snapshot, false, false, zjSocketConnects = false))
        assertTrue(ExternalBtTransportPolicy.refusesBringUp(snapshot, false, false, zjSocketConnects = false))
    }

    @Test
    fun `a refused daemon and an unmeasured socket ask the socket before refusing`() {
        val snapshot = legacy(evidence, false)
        assertTrue(ExternalBtTransportPolicy.needsDaemonMeasurement(snapshot, false, false, zjSocketConnects = null))
        assertFalse(ExternalBtTransportPolicy.refusesBringUp(snapshot, false, false, zjSocketConnects = null))
    }

    @Test
    fun `a connecting socket stops a re-dial of the ZBT daemon`() {
        assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(legacy(evidence, null), false, false, zjSocketConnects = true))
    }

    @Test
    fun `the socket needs markers or its toggle`() {
        assertEquals(Route.NORMAL, ExternalBtTransportPolicy.route(legacy(null, null), false, false, zjSocketConnects = true))
    }

    @Test
    fun `the socket toggle needs no measurement`() {
        assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(legacy(evidence, false), false, false, zjSocketEnabled = true, zjSocketConnects = null))
    }

    @Test
    fun `a unit whose socket connects sends the button to the module without a re-ask`() {
        val snapshot = legacy(evidence, false)
        assertEquals(WifiButton.MODULE, ExternalBtTransportPolicy.wifiButton(snapshot, false, false, zjSocketConnects = true))
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(snapshot, false, false, zjSocketConnects = true))
    }

    @Test
    fun `a press re-asks when both the daemon and the socket refused`() {
        assertTrue(ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidence, false), false, false, zjSocketConnects = false))
    }

    @Test
    fun `the radio override shows beside a connecting socket only while the user has not chosen it`() {
        val snapshot = legacy(evidence, false)
        assertFalse(ExternalBtTransportPolicy.showsAndroidRadioOverride(snapshot, false, false, zjSocketEnabled = true))
        assertTrue(ExternalBtTransportPolicy.showsAndroidRadioOverride(snapshot, false, true, zjSocketEnabled = true))
        assertTrue(ExternalBtTransportPolicy.showsAndroidRadioOverride(legacy(null, null), false, false, zjSocketConnects = true))
    }

    @Test
    fun `the ZJ socket is an external module that rearms without the Android radio`() {
        assertTrue(ExternalBtTransportPolicy.usesExternalModule(Route.ZJ_SOCKET))
        assertTrue(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.ZJ_SOCKET))
    }

    @Test
    fun `refusal and measurement stay complements over the socket inputs`() {
        for (cached in listOf<Boolean?>(null, true, false))
            for (zbt in listOf(false, true))
                for (ignore in listOf(false, true))
                    for (enabled in listOf(false, true))
                        for (answer in socketAnswers) {
                            val snapshot = legacy(evidence, cached)
                            val route = ExternalBtTransportPolicy.route(snapshot, zbt, ignore, zjSocketEnabled = enabled, zjSocketConnects = answer)
                            val refused = ExternalBtTransportPolicy.refusesBringUp(snapshot, zbt, ignore, zjSocketEnabled = enabled, zjSocketConnects = answer)
                            val asking = ExternalBtTransportPolicy.needsDaemonMeasurement(snapshot, zbt, ignore, zjSocketEnabled = enabled, zjSocketConnects = answer)
                            val key = "$cached/$zbt/$ignore/$enabled/$answer"
                            assertFalse(key, refused && asking)
                            if (route == Route.BLOCKED) assertTrue(key, refused || asking)
                            if (refused) assertEquals(key, Route.BLOCKED, route)
                        }
    }

    @Test
    fun `refusal and measurement are exclusive and cover every blocked route in all discovery modes`() {
        for (mode in ZbtDiscoveryPolicy.Mode.entries)
            for (cached in listOf<Boolean?>(null, true, false))
                for (zbt in listOf(false, true))
                    for (ignore in listOf(false, true))
                        for (blink in listOf(false, true))
                            for (fyt in listOf(null, "FYT")) {
                                val decision = ZbtDiscoveryPolicy.Decision(mode,
                                    if (mode == ZbtDiscoveryPolicy.Mode.NONE) null else "module")
                                val snapshot = ZbtDiscoveryPolicy.Snapshot(decision, cached)
                                val route = ExternalBtTransportPolicy.route(snapshot, zbt, ignore, blink, fyt)
                                val refused = ExternalBtTransportPolicy.refusesBringUp(snapshot, zbt, ignore, blink, fyt)
                                val asking = ExternalBtTransportPolicy.needsDaemonMeasurement(snapshot, zbt, ignore, blink, fyt)
                                assertFalse("$mode/$cached/$zbt/$ignore/$blink/$fyt", refused && asking)
                                if (route == Route.BLOCKED) assertTrue(refused || asking)
                                if (refused) assertEquals(Route.BLOCKED, route)
                            }
        // Unknown identity is intentionally both eligible for measurement and safe to fall back.
        val unknown = candidate("HCT", null)
        assertEquals(Route.NORMAL, ExternalBtTransportPolicy.route(unknown, false, false))
        assertTrue(ExternalBtTransportPolicy.needsDaemonMeasurement(unknown, false, false))
        assertFalse(ExternalBtTransportPolicy.refusesBringUp(unknown, false, false))
    }

    @Test
    fun `the WiFi button takes the module route exactly where the stack would`() {
        val cases = listOf<Boolean?>(null, true, false)
        for (evidenceValue in listOf(null, evidence))
            for (zbt in listOf(false, true))
                for (ignore in listOf(false, true))
                    for (cached in cases) {
                        val button = ExternalBtTransportPolicy.wifiButton(legacy(evidenceValue, cached), zbt, ignore)
                        val route = ExternalBtTransportPolicy.route(legacy(evidenceValue, cached), zbt, ignore)
                        val remeasures = ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidenceValue, cached), zbt, ignore)
                        assertEquals(refuses(evidenceValue, zbt, ignore, cached), button == WifiButton.REFUSED || remeasures)
                        assertFalse(button == WifiButton.REFUSED && remeasures)
                        assertEquals(route == Route.NORMAL, button == WifiButton.ANDROID_RADIO)
                    }
    }

    @Test
    fun `an unmeasured daemon sends the button to the module rather than refusing it`() {
        assertEquals(WifiButton.MODULE, ExternalBtTransportPolicy.wifiButton(legacy(evidence, null), false, false))
        // A refusal already measured is asked again by the press, so it is no longer refused here.
        assertEquals(WifiButton.MODULE, ExternalBtTransportPolicy.wifiButton(legacy(evidence, false), false, false))
    }

    @Test
    fun `a press re-asks a measured ZLink refusal and nothing else`() {
        assertTrue(ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidence, false), false, false))
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidence, true), false, false))
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidence, null), false, false))
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidence, false), true, false))
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidence, false), false, true))
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidence, false), false, false, blinkTransportEnabled = true))
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(legacy(null, false), false, false))
    }

    @Test
    fun `a press never invents a ZLink measurement on FYT hardware`() {
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(legacy(evidence, false), false, false, blinkTransportEnabled = false, fytModuleEvidence = "sys.fyt.bluetooth_type=2"))
        assertEquals(
            WifiButton.REFUSED,
            ExternalBtTransportPolicy.wifiButton(legacy(evidence, false), false, false, blinkTransportEnabled = false, fytModuleEvidence = "sys.fyt.bluetooth_type=2")
        )
    }

    @Test
    fun `the BLINK transport wins whenever it is on, evidence or not`() {
        assertEquals(Route.BLINK, ExternalBtTransportPolicy.route(legacy(null, null), false, false, blinkTransportEnabled = true))
        assertEquals(Route.BLINK, ExternalBtTransportPolicy.route(legacy(evidence, false), false, false, blinkTransportEnabled = true))
        assertEquals(Route.BLINK, ExternalBtTransportPolicy.route(legacy(evidence, true), true, true, blinkTransportEnabled = true))
    }

    @Test
    fun `the BLINK transport never refuses bring-up or waits on the ZJ daemon`() {
        assertFalse(ExternalBtTransportPolicy.refusesBringUp(legacy(evidence, false), false, false, blinkTransportEnabled = true))
        assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(legacy(evidence, null), false, false, blinkTransportEnabled = true))
        assertEquals(WifiButton.MODULE, ExternalBtTransportPolicy.wifiButton(legacy(evidence, false), false, false, blinkTransportEnabled = true))
    }

    @Test
    fun `leaving the BLINK transport off changes nothing`() {
        assertEquals(Route.BLOCKED, ExternalBtTransportPolicy.route(legacy(evidence, false), false, false, blinkTransportEnabled = false))
        assertEquals(Route.NORMAL, ExternalBtTransportPolicy.route(legacy(null, null), false, false, blinkTransportEnabled = false))
    }

    @Test
    fun `an enabled BLINK transport remains visible when detection stops seeing the module`() {
        assertTrue(ExternalBtTransportPolicy.showBlinkToggle(fytModuleEvidence = null, enabled = true))
        assertTrue(ExternalBtTransportPolicy.showBlinkToggle(fytModuleEvidence = fyt, enabled = false))
        assertFalse(ExternalBtTransportPolicy.showBlinkToggle(fytModuleEvidence = null, enabled = false))
    }

    private val fyt = "sys.fyt.bluetooth_type=2"

    @Test
    fun `an FYT module unit with the toggle off is refused without measuring a daemon`() {
        assertEquals(Route.BLOCKED, ExternalBtTransportPolicy.route(legacy(null, null), false, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        assertEquals(Route.BLOCKED, ExternalBtTransportPolicy.route(legacy(evidence, null), false, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(legacy(evidence, null), false, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        assertTrue(ExternalBtTransportPolicy.refusesBringUp(legacy(null, null), false, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        assertEquals(WifiButton.REFUSED, ExternalBtTransportPolicy.wifiButton(legacy(null, null), false, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
    }

    @Test
    fun `on an FYT module unit the toggle and the existing overrides still decide`() {
        assertEquals(Route.BLINK, ExternalBtTransportPolicy.route(legacy(null, null), false, false, blinkTransportEnabled = true, fytModuleEvidence = fyt))
        assertEquals(Route.NORMAL, ExternalBtTransportPolicy.route(legacy(null, null), false, true, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        assertEquals(WifiButton.MODULE, ExternalBtTransportPolicy.wifiButton(legacy(null, null), false, false, blinkTransportEnabled = true, fytModuleEvidence = fyt))
    }

    @Test
    fun `an FYT module unit never takes the ZLink daemon route`() {
        assertEquals(Route.BLOCKED, ExternalBtTransportPolicy.route(legacy(null, null), true, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        assertEquals(Route.BLOCKED, ExternalBtTransportPolicy.route(legacy(evidence, true), true, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        assertTrue(ExternalBtTransportPolicy.refusesBringUp(legacy(evidence, true), true, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        assertEquals(WifiButton.REFUSED, ExternalBtTransportPolicy.wifiButton(legacy(null, null), true, false, blinkTransportEnabled = false, fytModuleEvidence = fyt))
        // The FYT toggle still wins over a leftover ZBT toggle.
        assertEquals(Route.BLINK, ExternalBtTransportPolicy.route(legacy(null, null), true, false, blinkTransportEnabled = true, fytModuleEvidence = fyt))
        assertEquals(WifiButton.MODULE, ExternalBtTransportPolicy.wifiButton(legacy(null, null), true, false, blinkTransportEnabled = true, fytModuleEvidence = fyt))
    }

    @Test
    fun `only BLINK re-arms without Android Bluetooth listeners`() {
        assertFalse(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.NORMAL))
        assertFalse(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.BLOCKED))
        assertFalse(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.ZBT))
        assertTrue(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.ZJ_SOCKET))
        assertTrue(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.BLINK))
    }

    @Test
    fun `only module routes suppress Android Bluetooth driver controls`() {
        assertFalse(ExternalBtTransportPolicy.usesExternalModule(Route.NORMAL))
        assertFalse(ExternalBtTransportPolicy.usesExternalModule(Route.BLOCKED))
        assertTrue(ExternalBtTransportPolicy.usesExternalModule(Route.ZBT))
        assertTrue(ExternalBtTransportPolicy.usesExternalModule(Route.BLINK))
        assertTrue(ExternalBtTransportPolicy.usesExternalModule(Route.ZJ_SOCKET))
    }
    @Test
    fun `HCT candidate is measured without refusing its Android radio`() {
        for (answer in listOf(null, false, true)) {
            assertEquals(if (answer == true) ExternalBtTransportPolicy.Route.ZBT else ExternalBtTransportPolicy.Route.NORMAL,
                ExternalBtTransportPolicy.route(candidate("HCT", answer), false, false))
            assertEquals(answer == null, ExternalBtTransportPolicy.needsDaemonMeasurement(candidate("HCT", answer), false, false))
            assertFalse(ExternalBtTransportPolicy.refusesBringUp(candidate("HCT", answer), false, false))
        }
        assertEquals(ExternalBtTransportPolicy.WifiButton.MODULE,
            ExternalBtTransportPolicy.wifiButton(candidate("HCT", null), false, false))
        assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(candidate("HCT", false), false, false))
        assertEquals(ExternalBtTransportPolicy.WifiButton.ANDROID_RADIO,
            ExternalBtTransportPolicy.wifiButton(candidate("HCT", false), false, false))
    }

    @Test
    fun `HCT detection preserves FYT and explicit override priorities`() {
        assertEquals(ExternalBtTransportPolicy.Route.BLOCKED, ExternalBtTransportPolicy.route(candidate("HCT", true), false, false, fytModuleEvidence = "FYT"))
        assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(candidate("HCT", null), false, false, fytModuleEvidence = "FYT"))
        assertEquals(ExternalBtTransportPolicy.Route.NORMAL, ExternalBtTransportPolicy.route(candidate("HCT", true), false, true))
        assertEquals(ExternalBtTransportPolicy.Route.BLINK, ExternalBtTransportPolicy.route(candidate("HCT", true), false, false, blinkTransportEnabled = true))
    }

    @Test
    fun `QF candidates cannot override FYT or disable the Android picker after a failed probe`() {
        val decision = ZbtDiscoveryPolicy.detect({ null }, { it == "/system/bin/gocsdk_zj" })
        fun loaderCandidate(answer: Boolean?) = ZbtDiscoveryPolicy.Snapshot(decision, answer)
        for (answer in listOf(null, false, true)) {
            assertEquals(ExternalBtTransportPolicy.Route.BLOCKED,
                ExternalBtTransportPolicy.route(loaderCandidate(answer), false, false, fytModuleEvidence = "FYT"))
            assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(loaderCandidate(answer), false, false, fytModuleEvidence = "FYT"))
            assertEquals(ExternalBtTransportPolicy.Route.BLINK,
                ExternalBtTransportPolicy.route(loaderCandidate(answer), false, false, blinkTransportEnabled = true))
            assertEquals(ExternalBtTransportPolicy.Route.NORMAL,
                ExternalBtTransportPolicy.route(loaderCandidate(answer), false, true))
            assertEquals(ExternalBtTransportPolicy.Route.ZBT,
                ExternalBtTransportPolicy.route(loaderCandidate(answer), true, false))
            assertEquals(if (answer == true) ExternalBtTransportPolicy.Route.ZBT else ExternalBtTransportPolicy.Route.NORMAL,
                ExternalBtTransportPolicy.route(loaderCandidate(answer), false, false))
            assertEquals(answer == null,
                ExternalBtTransportPolicy.needsDaemonMeasurement(loaderCandidate(answer), false, false))
        }
        assertEquals(ExternalBtTransportPolicy.WifiButton.ANDROID_RADIO,
            ExternalBtTransportPolicy.wifiButton(loaderCandidate(false), false, false))
    }
}
