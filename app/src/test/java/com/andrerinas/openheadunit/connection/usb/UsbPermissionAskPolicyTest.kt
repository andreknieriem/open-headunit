package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Interface
import com.andrerinas.openheadunit.connection.usb.UsbPermissionAskPolicy.Action
import com.andrerinas.openheadunit.connection.usb.UsbPermissionAskPolicy.Inputs
import com.andrerinas.openheadunit.connection.usb.UsbPermissionAskPolicy.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionAskPolicyTest {

    /** Identity A just attached on a unit where Android shows its own dialog and the route is known. */
    private fun inputs(
        accessory: Boolean = false,
        mtp: Boolean = false,
        filterMatches: Boolean = true,
        activityEnabled: Boolean = true,
        automotive: Boolean = false,
        sdkInt: Int = 35,
        listenForUsb: Boolean = true,
        attachAgeMs: Long? = 1_000L,
        ownSwitchAgeMs: Long? = null,
        alreadyAsked: Boolean = false,
        source: Source = Source.AUTOMATIC,
    ) = Inputs(
        accessory, mtp, filterMatches, activityEnabled, automotive, sdkInt,
        listenForUsb, attachAgeMs, ownSwitchAgeMs, alreadyAsked, source,
    )

    private fun decide(i: Inputs) = UsbPermissionAskPolicy.decide(i).action

    @Test
    fun `listen off on attach holds instead of asking`() {
        assertEquals(Action.HOLD, decide(inputs(listenForUsb = false, activityEnabled = false)))
    }

    @Test
    fun `listen on with Android's route known defers to Android`() {
        assertEquals(Action.DEFER, decide(inputs(accessory = true)))
    }

    @Test
    fun `isMtp reads the PTP triple and the named vendor triple`() {
        fun device(vararg i: Interface) = Device(0x22B8, 0x2E82, 0, i.toList())
        assertTrue(UsbPermissionAskPolicy.isMtp(device(Interface(6, 1, 1))))
        assertTrue(UsbPermissionAskPolicy.isMtp(device(Interface(6, 1, 1, "anything"))))
        assertTrue(UsbPermissionAskPolicy.isMtp(device(Interface(255, 255, 0, "MTP"))))
        assertFalse(UsbPermissionAskPolicy.isMtp(device(Interface(255, 255, 0, "Android Accessory Interface"))))
        assertFalse(UsbPermissionAskPolicy.isMtp(device(Interface(255, 255, 0))))
        assertFalse(UsbPermissionAskPolicy.isMtp(device(Interface(255, 66, 1, "ADB Interface"))))
    }

    @Test
    fun `Android shows no dialog when our activity is off or the filter misses`() {
        assertFalse(UsbPermissionAskPolicy.defersToAndroid(inputs(activityEnabled = false)))
        assertFalse(UsbPermissionAskPolicy.defersToAndroid(inputs(filterMatches = false)))
    }

    @Test
    fun `an MTP phone gets Android's notification instead of a dialog from API 24`() {
        assertTrue(UsbPermissionAskPolicy.defersToAndroid(inputs(mtp = true, sdkInt = 23)))
        for (api in listOf(24, 25, 26, 35)) {
            assertFalse("API $api", UsbPermissionAskPolicy.defersToAndroid(inputs(mtp = true, sdkInt = api)))
        }
        assertTrue(UsbPermissionAskPolicy.defersToAndroid(inputs(mtp = true, automotive = true)))
    }

    @Test
    fun `an accessory and a phone with ADB only get Android's dialog`() {
        assertTrue(UsbPermissionAskPolicy.defersToAndroid(inputs(accessory = true, sdkInt = 17)))
        assertTrue(UsbPermissionAskPolicy.defersToAndroid(inputs(accessory = true, sdkInt = 35)))
        assertTrue(UsbPermissionAskPolicy.defersToAndroid(inputs(mtp = false, sdkInt = 35)))
    }

    @Test
    fun `a path already asked is not asked again automatically`() {
        for (source in listOf(Source.AUTOMATIC, Source.RECOVERY)) {
            assertEquals(Action.ASKED, decide(inputs(alreadyAsked = true, source = source)))
        }
    }

    @Test
    fun `a user press asks again over an open entry`() {
        assertEquals(Action.ASK, decide(inputs(alreadyAsked = true, source = Source.USER)))
        assertEquals(Action.ASK, decide(inputs(alreadyAsked = true, listenForUsb = false, source = Source.USER)))
    }

    @Test
    fun `a raise defers to Android only while the device has no permission`() {
        assertTrue(UsbPermissionAskPolicy.raiseDefersToAndroid(false, inputs()))
        assertTrue(UsbPermissionAskPolicy.raiseDefersToAndroid(false, inputs(accessory = true)))
        assertTrue(UsbPermissionAskPolicy.raiseDefersToAndroid(false, inputs()))
        assertFalse(UsbPermissionAskPolicy.raiseDefersToAndroid(true, inputs()))
    }

    @Test
    fun `a raise covers nothing where Android shows no dialog`() {
        assertFalse(UsbPermissionAskPolicy.raiseDefersToAndroid(false, inputs(activityEnabled = false, listenForUsb = false)))
        assertFalse(UsbPermissionAskPolicy.raiseDefersToAndroid(false, inputs(filterMatches = false)))
        assertFalse(UsbPermissionAskPolicy.raiseDefersToAndroid(false, inputs(mtp = true, sdkInt = 24)))
    }

    @Test
    fun `a deferred ask without permission hints at the USB button, and nothing else does`() {
        assertTrue(UsbPermissionAskPolicy.hintsUserAsk(false, Action.DEFER))
        assertFalse(UsbPermissionAskPolicy.hintsUserAsk(true, Action.DEFER))
        for (action in listOf(Action.ASK, Action.ASKED, Action.HOLD)) {
            assertFalse(action.name, UsbPermissionAskPolicy.hintsUserAsk(false, action))
        }
    }

    @Test
    fun `a user ask passes an open entry only after the repeat window`() {
        assertFalse(UsbPermissionAskPolicy.userPassesOpenEntry(51))
        assertFalse(UsbPermissionAskPolicy.userPassesOpenEntry(UsbPermissionAskPolicy.USER_REPEAT_MS - 1))
        assertTrue(UsbPermissionAskPolicy.userPassesOpenEntry(UsbPermissionAskPolicy.USER_REPEAT_MS))
    }

    @Test
    fun `a plug this process saw defers to Android however long ago it came`() {
        assertEquals(Action.DEFER, decide(inputs(attachAgeMs = 0)))
        assertEquals(Action.DEFER, decide(inputs(attachAgeMs = 10 * 60_000L)))
    }

    @Test
    fun `a cancelled Android dialog stays cancelled for automatic scans, and the button still asks`() {
        assertEquals(Action.DEFER, decide(inputs(attachAgeMs = 90_000L)))
        assertEquals(Action.ASK, decide(inputs(attachAgeMs = 90_000L, source = Source.USER)))
    }

    @Test
    fun `boot scan with no attach behind it asks`() {
        assertEquals(Action.ASK, decide(inputs(attachAgeMs = null)))
    }

    @Test
    fun `Save scan is the user's, asks with listen off and long after the attach`() {
        val source = UsbPermissionAskPolicy.sourceFor(userTier = false, settingsRestart = true)
        assertEquals(Source.USER, source)
        assertEquals(Action.ASK, decide(inputs(listenForUsb = false, source = source)))
        assertEquals(Action.ASK, decide(inputs(attachAgeMs = 120_000, source = source)))
    }

    @Test
    fun `an ordinary scan stays automatic and a button press is the user's`() {
        assertEquals(Source.AUTOMATIC, UsbPermissionAskPolicy.sourceFor(userTier = false, settingsRestart = false))
        assertEquals(Source.USER, UsbPermissionAskPolicy.sourceFor(userTier = true, settingsRestart = false))
    }

    @Test
    fun `a user ask goes through with listen off`() {
        assertEquals(Action.ASK, decide(inputs(listenForUsb = false, source = Source.USER)))
    }

    @Test
    fun `recovery asks with listen off`() {
        assertEquals(Action.ASK, decide(inputs(listenForUsb = false, source = Source.RECOVERY)))
    }

    @Test
    fun `listen off asks for the accessory only just after our own switch`() {
        fun b(age: Long?) = inputs(accessory = true, listenForUsb = false, ownSwitchAgeMs = age)
        assertEquals(Action.ASK, decide(b(2_000)))
        assertEquals(Action.HOLD, decide(b(UsbPermissionAskPolicy.OWN_SWITCH_WINDOW_MS)))
        assertEquals(Action.HOLD, decide(b(null)))
    }

    @Test
    fun `listen off holds the normal identity whatever the switch age`() {
        for (age in listOf<Long?>(null, 0L, 2_000L)) {
            assertEquals(Action.HOLD, decide(inputs(listenForUsb = false, ownSwitchAgeMs = age)))
        }
    }

    @Test
    fun `listen on defers the accessory to Android and asks for a plug this process never saw`() {
        assertEquals(Action.DEFER, decide(inputs(accessory = true)))
        assertEquals(Action.ASK, decide(inputs(accessory = true, attachAgeMs = null)))
    }

    @Test
    fun `listen on asks for an MTP phone where Android shows no dialog`() {
        assertEquals(Action.ASK, decide(inputs(mtp = true)))
    }

    @Test
    fun `listen on asks for the audio accessory the filter does not list`() {
        assertEquals(Action.ASK, decide(inputs(accessory = true, filterMatches = false)))
    }

    @Test
    fun `the decision line names every input and the action`() {
        assertEquals(
            "identity=accessory mtp=no filter=match activity=on listen=on attach=1000ms " +
                "source=automatic action=defer",
            UsbPermissionAskPolicy.decide(inputs(accessory = true)).toString(),
        )
        assertEquals(
            "identity=normal mtp=yes filter=none activity=off listen=off attach=none " +
                "source=automatic action=hold",
            UsbPermissionAskPolicy.decide(
                inputs(
                    mtp = true, filterMatches = false, activityEnabled = false, listenForUsb = false,
                    attachAgeMs = null,
                )
            ).toString(),
        )
    }
}
