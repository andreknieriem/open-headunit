package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

class UsbRecoveryPermissionTest {
    @Test fun recoveryResultsNeverFallThroughToTheOrdinaryPermissionHandler() {
        val old = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        try {
            val listener = mock(UsbReceiver.Listener::class.java)
            val device = mock(UsbDevice::class.java)
            val intent = mock(Intent::class.java)
            val data = mock(Uri::class.java)
            `when`(intent.action).thenReturn(UsbReceiver.ACTION_USB_DEVICE_PERMISSION)
            `when`(intent.data).thenReturn(data)
            `when`(data.scheme).thenReturn("ohu-usb-recovery")
            `when`(data.schemeSpecificPart).thenReturn("expired-token")
            @Suppress("DEPRECATION")
            `when`(intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)).thenReturn(device)
            `when`(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)).thenReturn(true)
            UsbReceiver(listener).onReceive(mock(Context::class.java), intent)
            verify(listener).onUsbRecoveryPermission("expired-token", true, device)
            verifyNoMoreInteractions(listener)
        } finally { AppLog.LOGGER = old }
    }

    private fun quietly(block: () -> Unit) {
        val old = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        try { block() } finally { AppLog.LOGGER = old }
    }

    private fun answer(scheme: String?, token: String, granted: Boolean, device: UsbDevice): Intent {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(UsbReceiver.ACTION_USB_DEVICE_PERMISSION)
        if (scheme != null) {
            val data = mock(Uri::class.java)
            `when`(intent.data).thenReturn(data)
            `when`(data.scheme).thenReturn(scheme)
            `when`(data.schemeSpecificPart).thenReturn(token)
        }
        @Suppress("DEPRECATION")
        `when`(intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)).thenReturn(device)
        `when`(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)).thenReturn(granted)
        return intent
    }

    @Test fun anOrdinaryAnswerReachesThePermissionHandlerAndOneWithNoTokenIsDropped() = quietly {
        val listener = mock(UsbReceiver.Listener::class.java)
        val device = mock(UsbDevice::class.java)
        UsbReceiver(listener).onReceive(mock(Context::class.java), answer(UsbReceiver.ASK_PERMISSION_SCHEME, "t", true, device))
        verify(listener).onUsbPermission(true, false, device)
        UsbReceiver(listener).onReceive(mock(Context::class.java), answer(null, "", true, device))
        verifyNoMoreInteractions(listener)
    }

    @Test fun theResultReceiverEndsOnlyItsOwnEntryAndOnlyOnAGrant() = quietly {
        UsbPermissionAsks.resetForTest()
        val device = mock(UsbDevice::class.java)
        val path = "/dev/bus/usb/001/004"
        `when`(device.deviceName).thenReturn(path)
        UsbPermissionAsks.tryMark(path, listOf(path), "mine")
        val receiver = UsbPermissionResultReceiver()
        receiver.onReceive(mock(Context::class.java), answer(UsbReceiver.ASK_PERMISSION_SCHEME, "other", true, device))
        receiver.onReceive(mock(Context::class.java), answer(UsbReceiver.ASK_PERMISSION_SCHEME, "mine", false, device))
        assertTrue(UsbPermissionAsks.isAsked(path, listOf(path)))
        receiver.onReceive(mock(Context::class.java), answer(UsbReceiver.ASK_PERMISSION_SCHEME, "mine", true, device))
        assertFalse(UsbPermissionAsks.isAsked(path, listOf(path)))
    }

    @Test fun theManifestReceiverListensOnBothTokenSchemes() {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/AndroidManifest.xml"))
        val receivers = doc.getElementsByTagName("receiver")
        val schemes = HashSet<String>()
        val actions = HashSet<String>()
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i) as org.w3c.dom.Element
            if (!receiver.getAttribute("android:name").endsWith("UsbPermissionResultReceiver")) continue
            assertEquals("false", receiver.getAttribute("android:exported"))
            val data = receiver.getElementsByTagName("data")
            for (j in 0 until data.length) schemes.add((data.item(j) as org.w3c.dom.Element).getAttribute("android:scheme"))
            val action = receiver.getElementsByTagName("action")
            for (j in 0 until action.length) actions.add((action.item(j) as org.w3c.dom.Element).getAttribute("android:name"))
        }
        assertEquals(setOf(UsbReceiver.ASK_PERMISSION_SCHEME, UsbReceiver.RECOVERY_PERMISSION_SCHEME), schemes)
        assertEquals(setOf(UsbReceiver.ACTION_USB_DEVICE_PERMISSION), actions)
    }
}
