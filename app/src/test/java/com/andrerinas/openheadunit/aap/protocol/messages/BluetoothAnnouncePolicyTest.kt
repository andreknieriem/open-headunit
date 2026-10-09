package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.utils.SettingsBackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAnnouncePolicyTest {
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val real = BluetoothAnnouncePolicy.Mode.REAL
    private val skip = BluetoothAnnouncePolicy.Mode.SKIP

    @Test
    fun `nothing stored on USB announces the real address`() {
        val a = BluetoothAnnouncePolicy.decide(null, mac, true)
        assertEquals(real, a.mode)
        assertEquals(mac, a.carAddress)
    }

    @Test
    fun `nothing stored and no address omits the service`() {
        val a = BluetoothAnnouncePolicy.decide(null, "", true)
        assertEquals(real, a.mode)
        assertNull(a.carAddress)
    }

    @Test
    fun `real on USB announces the address`() {
        assertEquals(mac, BluetoothAnnouncePolicy.decide("real", mac, true).carAddress)
    }

    @Test
    fun `skip on USB announces the skip value`() {
        val a = BluetoothAnnouncePolicy.decide("skip", mac, true)
        assertEquals(skip, a.mode)
        assertEquals("SKIP_THIS_BLUETOOTH", a.carAddress)
        assertFalse(a.skipNotApplied)
    }

    @Test
    fun `skip off USB announces the real address and says so`() {
        val a = BluetoothAnnouncePolicy.decide("skip", mac, false)
        assertEquals(real, a.mode)
        assertEquals(mac, a.carAddress)
        assertTrue(a.skipNotApplied)
    }

    @Test
    fun `real off USB does not raise the flag`() {
        assertFalse(BluetoothAnnouncePolicy.decide("real", mac, false).skipNotApplied)
    }

    @Test
    fun `skip on USB with no address omits the service`() {
        assertNull(BluetoothAnnouncePolicy.decide("skip", "", true).carAddress)
    }

    @Test
    fun `a blank value from the probe reads as real`() {
        val a = BluetoothAnnouncePolicy.decide("blank", mac, true)
        assertEquals(real, a.mode)
        assertEquals(mac, a.carAddress)
    }

    @Test
    fun `the stored value ignores case and spaces`() {
        assertEquals(skip, BluetoothAnnouncePolicy.decide(" Skip ", mac, true).mode)
    }

    @Test
    fun `an unknown value and an empty string read as real`() {
        assertEquals(real, BluetoothAnnouncePolicy.decide("forbid", mac, true).mode)
        assertEquals(real, BluetoothAnnouncePolicy.decide("", mac, true).mode)
    }

    @Test
    fun announceKeyIsRegisteredInBackupManager() {
        val type = SettingsBackupManager.backupKeys["bt-announce"]
        assertNotNull("bt-announce must be registered in SettingsBackupManager", type)
        assertEquals(SettingsBackupManager.ValueType.STRING, type)
    }

    @Test
    fun `the announce key does not restart the projection`() {
        assertFalse(SettingsBackupManager.requiresProjectionRestart(setOf("bt-announce")))
    }

    @Test
    fun `storedValue maps the toggle to the two values`() {
        assertEquals("skip", BluetoothAnnouncePolicy.storedValue(true))
        assertEquals("real", BluetoothAnnouncePolicy.storedValue(false))
    }

    @Test
    fun `isSkip round trips storedValue`() {
        assertTrue(BluetoothAnnouncePolicy.isSkip(BluetoothAnnouncePolicy.storedValue(true)))
        assertFalse(BluetoothAnnouncePolicy.isSkip(BluetoothAnnouncePolicy.storedValue(false)))
    }

    @Test
    fun `isSkip is false when nothing is stored`() {
        assertFalse(BluetoothAnnouncePolicy.isSkip(null))
    }
}
