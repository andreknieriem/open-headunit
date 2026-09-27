package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.Route
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.WifiButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole truth table, because four call sites read this decision and the only thing keeping them
 * from disagreeing is that they all read it from here.
 */
class ExternalBtTransportPolicyTest {

    private val evidence = "/dev/rf_serial exists"

    private fun route(
        externalBtEvidence: String? = null,
        zbt: Boolean = false,
        ignore: Boolean = false,
        daemonReachable: Boolean? = null
    ) = ExternalBtTransportPolicy.route(externalBtEvidence, zbt, ignore, daemonReachable)

    private fun needsMeasurement(
        externalBtEvidence: String? = null,
        zbt: Boolean = false,
        ignore: Boolean = false,
        cached: Boolean? = null
    ) = ExternalBtTransportPolicy.needsDaemonMeasurement(externalBtEvidence, zbt, ignore, cached)

    private fun refuses(
        externalBtEvidence: String? = null,
        zbt: Boolean = false,
        ignore: Boolean = false,
        cached: Boolean? = null
    ) = ExternalBtTransportPolicy.refusesBringUp(externalBtEvidence, zbt, ignore, cached)

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
    fun `refusing and still-worth-asking are exact complements of a blocked route`() {
        // Neither may be relaxed on its own: together they must cover BLOCKED exactly once.
        val cases = listOf<Boolean?>(null, true, false)
        for (evidenceValue in listOf(null, evidence))
            for (zbt in listOf(false, true))
                for (ignore in listOf(false, true))
                    for (cached in cases) {
                        val blocked = ExternalBtTransportPolicy
                            .route(evidenceValue, zbt, ignore, cached) == Route.BLOCKED
                        val refused = refuses(evidenceValue, zbt, ignore, cached)
                        val asking = needsMeasurement(evidenceValue, zbt, ignore, cached)
                        assertEquals(blocked, refused || asking)
                        assertFalse(refused && asking)
                    }
    }

    @Test
    fun `the WiFi button takes the module route exactly where the stack would`() {
        val cases = listOf<Boolean?>(null, true, false)
        for (evidenceValue in listOf(null, evidence))
            for (zbt in listOf(false, true))
                for (ignore in listOf(false, true))
                    for (cached in cases) {
                        val button = ExternalBtTransportPolicy.wifiButton(evidenceValue, zbt, ignore, cached)
                        val route = ExternalBtTransportPolicy.route(evidenceValue, zbt, ignore, cached)
                        assertEquals(refuses(evidenceValue, zbt, ignore, cached), button == WifiButton.REFUSED)
                        assertEquals(route == Route.NORMAL, button == WifiButton.ANDROID_RADIO)
                    }
    }

    @Test
    fun `an unmeasured daemon sends the button to the module rather than refusing it`() {
        assertEquals(WifiButton.MODULE, ExternalBtTransportPolicy.wifiButton(evidence, false, false, null))
        assertEquals(WifiButton.REFUSED, ExternalBtTransportPolicy.wifiButton(evidence, false, false, false))
    }
}
