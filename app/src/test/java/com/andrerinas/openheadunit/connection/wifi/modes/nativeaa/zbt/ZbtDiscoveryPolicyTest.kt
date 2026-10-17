package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy
import com.andrerinas.openheadunit.utils.ExternalBtPolicy
import org.junit.Assert.*
import org.junit.Test

class ZbtDiscoveryPolicyTest {
    private val noNodes: (String) -> Boolean = { false }
    private val noProps: (String) -> String? = { null }
    private fun nodes(vararg present: String): (String) -> Boolean = { it in present }

    // Candidate predicates are checked through the same entry used in production.
    private fun detectCandidate(
        nodeExists: (String) -> Boolean,
        hctServiceAvailable: () -> Boolean = { false }
    ): String? = ZbtDiscoveryPolicy.detect({ null }, nodeExists, hctServiceAvailable).evidence

    private fun detectDecision(marker: String?, candidate: String?): ZbtDiscoveryPolicy.Decision =
        ZbtDiscoveryPolicy.detect({ marker }, when (candidate) {
            "HCT" -> nodes("/system/bin/fscsdkbw123", "/dev/goc_serial")
            "QF" -> nodes("/system/bin/gocsdk_zj")
            else -> noNodes
        })

    @Test
    fun `HCT needs both a goc relay and the compatible daemon file`() {
        assertNull(detectCandidate(noNodes))
        assertNull(detectCandidate(nodes("/dev/goc_serial")))
        assertNull(detectCandidate(nodes("/system/bin/fscsdkbw123")))
        assertEquals("HCT Bluetooth bridge with fscsdkbw123", detectCandidate(
            nodes("/dev/goc_serial", "/system/bin/fscsdkbw123")
        ))
        // A candidate must not become the definitive external-radio refusal signal.
        assertNull(ExternalBtPolicy.detect(nodes("/dev/goc_serial", "/system/bin/fscsdkbw123"), noProps))
    }

    @Test
    fun `HCT service substitutes for a relay hidden by SELinux`() {
        assertEquals("HCT Bluetooth bridge with fscsdkbw123", detectCandidate(
            nodes("/system/bin/fscsdkbw123"), hctServiceAvailable = { true }
        ))
        assertNull(detectCandidate(noNodes, hctServiceAvailable = { true }))
    }

    @Test
    fun `QF loader is a candidate without a visible library or relay`() {
        val daemon = "/system/bin/gocsdk_zj"
        val evidence = "QF gocsdk_zj loader"
        // The loader can use an app-private or OEM copy that OHU cannot inspect.
        for (files in listOf(
            arrayOf(daemon), arrayOf(daemon, "/system/lib/libzbt-main.so"),
            arrayOf(daemon, "/oem/lib/libzbt-main.so"),
            arrayOf(daemon, "/data/data/com.zjinnova.zlink/lib/libzbt-main.so"),
            arrayOf(daemon, "/dev/goc_serial", "/system/bin/gocsdk_lt")
        )) {
            assertEquals(files.contentToString(), evidence, detectCandidate(nodes(*files)))
        }
        // Installed firmware is never the legacy marker that can block Android Bluetooth.
        assertNull(ExternalBtPolicy.detect(nodes(daemon, "/dev/goc_serial"), noProps))
    }

    @Test
    fun `other GOC daemons or a library alone do not authorize a ZBT probe`() {
        for (files in listOf(
            emptyArray(), arrayOf("/system/lib/libzbt-main.so"),
            arrayOf("/oem/lib/libzbt-main.so"), arrayOf("/dev/goc_serial"),
            arrayOf("/system/bin/gocsdk_lt", "/system/lib/libzbt-main.so", "/dev/goc_serial"),
            arrayOf("/system/bin/gocsdk_wl", "/dev/goc_serial")
        )) {
            assertNull(files.contentToString(), detectCandidate(nodes(*files)))
        }
    }

    @Test
    fun `QF candidate inspection does not read library paths`() {
        val result = ZbtDiscoveryPolicy.detect({ null }, { path ->
            when (path) {
                "/system/bin/fscsdkbw123" -> false
                "/system/bin/gocsdk_zj" -> true
                else -> error("Library and relay metadata must not be required: $path")
            }
        })
        assertEquals(ZbtDiscoveryPolicy.Mode.IDENTITY, result.mode)
        // No response and a failed response both keep Android Bluetooth as the route.
        for (confirmed in listOf(null, false, true)) {
            val snapshot = ZbtDiscoveryPolicy.Snapshot(result, confirmed)
            assertEquals(if (confirmed == true) ExternalBtTransportPolicy.Route.ZBT
                else ExternalBtTransportPolicy.Route.NORMAL,
                ExternalBtTransportPolicy.route(snapshot, false, false))
        }
    }

    @Test
    fun `all established external markers and firmware hints select a procedure in one policy`() {
        for (node in listOf("/dev/rf_serial", "/dev/zj_bt_serial")) {
            val marker = ExternalBtPolicy.detect(nodes(node), noProps)
            for (candidate in listOf(null, "HCT", "QF")) {
                val decision = detectDecision(marker, candidate)
                assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, decision.mode)
                assertEquals(marker, decision.evidence)
                assertFalse(decision.allowsAndroidFallback)
                assertTrue(decision.retriesOnPress)
            }
        }
        for (key in listOf("rw.zlink.bt.type", "rw.zj.bt.type")) {
            val marker = ExternalBtPolicy.detect(noNodes) { if (it == key) "extra" else null }
            assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, detectDecision(marker, "QF").mode)
        }
        for (candidate in listOf("HCT", "QF")) {
            val decision = detectDecision(null, candidate)
            assertEquals(ZbtDiscoveryPolicy.Mode.IDENTITY, decision.mode)
            assertEquals(if (candidate == "HCT") "HCT Bluetooth bridge with fscsdkbw123"
                else "QF gocsdk_zj loader", decision.evidence)
            assertTrue(decision.requiresIdentity)
            assertTrue(decision.allowsAndroidFallback)
            assertFalse(decision.retriesOnPress)
        }
        val none = detectDecision(null, null)
        assertEquals(ZbtDiscoveryPolicy.Mode.NONE, none.mode)
        assertFalse(none.available)
    }

    @Test
    fun `legacy decision is held too and does not switch when candidate metadata appears`() {
        var marker: String? = "rw.zlink.bt.type=extra"
        var candidate: String? = null
        val latch = ZbtDiscoveryPolicy.Latch { detectDecision(marker, candidate) }
        val original = latch.decision()
        candidate = "QF"
        marker = null // Even a temporarily unavailable source cannot change this arming.
        assertEquals(original, latch.decision())
        assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, latch.decision().mode)
    }

    @Test
    fun `identity mode survives late markers until a fresh arming with no carrier owner`() {
        for (candidate in listOf("HCT", "QF")) {
            var marker: String? = null
            val latch = ZbtDiscoveryPolicy.Latch { detectDecision(marker, candidate) }
            val initial = latch.decision()
            assertEquals(ZbtDiscoveryPolicy.Mode.IDENTITY, initial.mode)
            marker = "rw.zlink.bt.type=extra"
            assertEquals(initial, latch.decision())
            // An unconfirmed strict probe leaves the phone picker available even after publication.
            val snapshot = ZbtDiscoveryPolicy.Snapshot(latch.decision(), false)
            assertEquals(ExternalBtTransportPolicy.Route.NORMAL,
                ExternalBtTransportPolicy.route(snapshot, false, false))
            assertEquals(ExternalBtTransportPolicy.WifiButton.ANDROID_RADIO,
                ExternalBtTransportPolicy.wifiButton(snapshot, false, false))
            assertTrue(snapshot.decision.allowsAndroidFallback)
            assertEquals(ExternalBtTransportPolicy.Route.ZBT,
                ExternalBtTransportPolicy.route(snapshot.copy(reachable = true), false, false))
            assertEquals(ExternalBtTransportPolicy.WifiButton.MODULE,
                ExternalBtTransportPolicy.wifiButton(snapshot.copy(reachable = true), false, false))
            latch.reconsiderForArming(carrierOwnsSlot = true)
            assertEquals(initial, latch.decision())
            latch.reconsiderForArming(carrierOwnsSlot = false)
            assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, latch.decision().mode)
            assertEquals(marker, latch.decision().evidence)
            // A captured async read keeps its evidence and mode when another arming begins.
            assertEquals(initial, snapshot.decision)
        }
    }

    @Test
    fun `late legacy markers and firmware hints are both detected after a negative read`() {
        for (hasMarker in listOf(false, true)) {
            var marker: String? = null
            var candidate: String? = null
            val latch = ZbtDiscoveryPolicy.Latch { detectDecision(marker, candidate) }
            assertEquals(ZbtDiscoveryPolicy.Mode.NONE, latch.decision().mode)
            if (hasMarker) marker = "rw.zj.bt.type=extra" else candidate = "HCT"
            assertEquals(if (hasMarker) ZbtDiscoveryPolicy.Mode.REACHABILITY else ZbtDiscoveryPolicy.Mode.IDENTITY,
                latch.decision().mode)
        }
    }

    @Test
    fun `a fresh arming still sees process-latched legacy evidence`() {
        var marker: String? = "rw.zlink.bt.type=extra"
        val hardware = ExternalBtPolicy.Latch { marker }
        val latch = ZbtDiscoveryPolicy.Latch { detectDecision(hardware.evidence(), null) }
        val first = latch.decision()
        marker = null
        latch.reconsiderForArming(carrierOwnsSlot = false)
        assertEquals(first, latch.decision())
    }

    @Test
    fun `existing node and property checks run first and skip all candidate inspection`() {
        val existingNodes = listOf("/dev/rf_serial", "/dev/zj_bt_serial")
        val existingProperties = listOf("rw.zlink.bt.type", "rw.zj.bt.type")
        for (signal in existingNodes + existingProperties) {
            var reads = 0
            val hardware = ExternalBtPolicy.Latch {
                reads++
                ExternalBtPolicy.detect(nodes(signal)) { if (it == signal) "  EXTRA  " else null }
            }
            val result = ZbtDiscoveryPolicy.detect(
                externalModuleEvidence = { hardware.evidence() },
                nodeExists = { error("Firmware read must not run after $signal: $it") },
                hctServiceAvailable = { error("Service query must not run after $signal") }
            )
            assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, result.mode)
            assertEquals(hardware.evidence(), result.evidence)
            assertEquals(1, reads)
        }
    }

    @Test
    fun `HCT wins over QF and a service alone cannot identify either bridge`() {
        val all = nodes("/system/bin/fscsdkbw123", "/dev/goc_serial", "/system/bin/gocsdk_zj")
        val result = ZbtDiscoveryPolicy.detect({ null }, all)
        assertEquals(ZbtDiscoveryPolicy.Mode.IDENTITY, result.mode)
        assertEquals("HCT Bluetooth bridge with fscsdkbw123", result.evidence)
        assertEquals(ZbtDiscoveryPolicy.Mode.NONE,
            ZbtDiscoveryPolicy.detect({ null }, noNodes, { true }).mode)
    }
}
