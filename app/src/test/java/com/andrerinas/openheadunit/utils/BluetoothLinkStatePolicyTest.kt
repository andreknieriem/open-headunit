package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothLinkStatePolicyTest {

    private fun snapshot(vararg on: String, unreadable: Set<String> = emptySet(), audioMode: Int? = 0) =
        BluetoothLinkStatePolicy.Snapshot(
            adapterOn = true,
            states = BluetoothLinkStatePolicy.PROFILES.associate { (name, _) ->
                name to when (name) {
                    in unreadable -> null
                    in on -> 2
                    else -> 0
                }
            },
            audioMode = audioMode
        )

    @Test
    fun `a head unit holding calls and music reads every profile in order`() {
        assertEquals(
            "hfpClient=on a2dpSink=on avrcpCtl=on pbapClient=off mapClient=off headset=off a2dp=off audioMode=normal",
            BluetoothLinkStatePolicy.describe(snapshot("hfpClient", "a2dpSink", "avrcpCtl"))
        )
    }

    @Test
    fun `an unreadable role is a question mark, not off`() {
        val line = BluetoothLinkStatePolicy.describe(snapshot("hfpClient", unreadable = setOf("mapClient")))
        assertTrue(line.contains("mapClient=?"))
        assertTrue(line.contains("hfpClient=on"))
    }

    @Test
    fun `an adapter that is off or unresolved says so instead of listing profiles`() {
        assertEquals("adapter off", BluetoothLinkStatePolicy.describe(
            BluetoothLinkStatePolicy.Snapshot(false, emptyMap(), 0)))
        assertEquals("adapter unreadable", BluetoothLinkStatePolicy.describe(
            BluetoothLinkStatePolicy.Snapshot(null, emptyMap(), null)))
    }

    @Test
    fun `a call on the unit shows through the audio mode`() {
        assertTrue(BluetoothLinkStatePolicy.describe(snapshot("hfpClient", audioMode = 2)).endsWith("audioMode=inCall"))
        assertEquals("?", BluetoothLinkStatePolicy.audioModeLabel(null))
    }

    @Test
    fun `the first reading prints, a repeat does not, a moved profile does`() {
        val calls = snapshot("hfpClient")
        assertTrue(BluetoothLinkStatePolicy.changed(null, calls))
        assertFalse(BluetoothLinkStatePolicy.changed(calls, snapshot("hfpClient")))
        assertTrue(BluetoothLinkStatePolicy.changed(calls, snapshot("hfpClient", "pbapClient")))
        assertTrue(BluetoothLinkStatePolicy.changed(calls, snapshot("hfpClient", audioMode = 1)))
    }

    @Test
    fun `the hidden profile ints match the platform`() {
        assertEquals(16, BluetoothLinkStatePolicy.PROFILE_HEADSET_CLIENT)
        assertEquals(11, BluetoothLinkStatePolicy.PROFILE_A2DP_SINK)
        assertEquals(12, BluetoothLinkStatePolicy.PROFILE_AVRCP_CONTROLLER)
        assertEquals(17, BluetoothLinkStatePolicy.PROFILE_PBAP_CLIENT)
        assertEquals(18, BluetoothLinkStatePolicy.PROFILE_MAP_CLIENT)
    }
}
