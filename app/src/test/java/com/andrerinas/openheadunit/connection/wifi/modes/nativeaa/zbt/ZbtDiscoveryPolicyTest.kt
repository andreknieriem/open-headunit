package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

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
    fun `all established external markers and firmware hints select a procedure in one policy`() {
        for (node in listOf("/dev/rf_serial", "/dev/zj_bt_serial")) {
            val marker = ExternalBtPolicy.detect(nodes(node), noProps)
            for (candidate in listOf(null, "HCT")) {
                val decision = detectDecision(marker, candidate)
                assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, decision.mode)
                assertEquals(marker, decision.evidence)
                assertFalse(decision.allowsAndroidFallback)
                assertTrue(decision.retriesOnPress)
            }
        }
        for (key in listOf("rw.zlink.bt.type", "rw.zj.bt.type")) {
            val marker = ExternalBtPolicy.detect(noNodes) { if (it == key) "extra" else null }
            assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, detectDecision(marker, "HCT").mode)
        }
        for (candidate in listOf("HCT")) {
            val decision = detectDecision(null, candidate)
            assertEquals(ZbtDiscoveryPolicy.Mode.IDENTITY, decision.mode)
            assertEquals("HCT Bluetooth bridge with fscsdkbw123", decision.evidence)
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
        candidate = "HCT"
        marker = null // Even a temporarily unavailable source cannot change this arming.
        assertEquals(original, latch.decision())
        assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, latch.decision().mode)
    }

    @Test
    fun `identity mode survives late markers until a fresh arming with no carrier owner`() {
        for (candidate in listOf("HCT")) {
            var marker: String? = null
            val latch = ZbtDiscoveryPolicy.Latch { detectDecision(marker, candidate) }
            val initial = latch.decision()
            assertEquals(ZbtDiscoveryPolicy.Mode.IDENTITY, initial.mode)
            marker = "rw.zlink.bt.type=extra"
            assertEquals(initial, latch.decision())
            latch.reconsiderForArming(carrierOwnsSlot = true)
            assertEquals(initial, latch.decision())
            latch.reconsiderForArming(carrierOwnsSlot = false)
            assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, latch.decision().mode)
            assertEquals(marker, latch.decision().evidence)
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

}
