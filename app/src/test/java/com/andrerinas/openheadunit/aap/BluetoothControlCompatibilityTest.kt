package com.andrerinas.openheadunit.aap

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth
import com.andrerinas.openheadunit.aap.protocol.proto.Control.BluetoothPairingMethod.*
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.argumentCaptor
import org.mockito.stubbing.Answer

/** Exercise the actual controller while only Android IPC/loopers are replaced. */
class BluetoothControlCompatibilityTest {
    private val address = "12:34:56:78:9A:BC"
    private val phone = "22:34:56:78:9A:BC"

    private fun checkMode(enabled: Boolean = true, wireless: Boolean = true, loopback: Boolean = false,
        managerName: String = "", actual: String = address, permission: Int = 0,
        registerFails: Boolean = false, active: Boolean = false, bond: Int = BluetoothDevice.BOND_NONE) {
        mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
        mockStatic(SystemClock::class.java).use {
        mockConstruction(IntentFilter::class.java).use {
        mockConstruction(HandlerThread::class.java).use {
            val tasks = ArrayDeque<Runnable>()
            mockConstruction(Handler::class.java) { handler, _ ->
                `when`(handler.post(org.mockito.kotlin.any())).thenAnswer { call ->
                    tasks.add(call.getArgument(0)); true
                }
            }.use {
                var receiver: BroadcastReceiver? = null
                mockStatic(ContextCompat::class.java, Answer { call ->
                    when (call.method.name) {
                        "checkSelfPermission" -> permission
                        "registerReceiver" -> {
                            if (registerFails) throw SecurityException("denied")
                            receiver = call.getArgument(1)
                            assertNull("Receiver must not share the Binder worker", call.getArgument<Handler?>(4))
                            null
                        }
                        else -> null
                    }
                }).use {
                    val context = mock(Context::class.java)
                    val app = mock(App::class.java)
                    val component = mock(AppComponent::class.java)
                    val comm = mock(CommManager::class.java)
                    `when`(context.applicationContext).thenReturn(app)
                    `when`(app.applicationContext).thenReturn(app)
                    App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
                    `when`(component.commManager).thenReturn(comm)
                    `when`(comm.isWirelessSession).thenReturn(wireless)
                    `when`(comm.isLoopbackSession).thenReturn(loopback)
                    `when`(comm.isUsbSession).thenReturn(!wireless)
                    val settings = mock(Settings::class.java)
                    `when`(settings.bluetoothAddress).thenReturn(address)
                    `when`(settings.bluetoothManagerServiceName).thenReturn(managerName)
                    `when`(settings.bluetoothPairingAssist).thenReturn(enabled)
                    val manager = mock(BluetoothManager::class.java)
                    val adapter = mock(BluetoothAdapter::class.java)
                    val device = mock(BluetoothDevice::class.java)
                    `when`(app.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(manager)
                    `when`(manager.adapter).thenReturn(adapter)
                    `when`(adapter.address).thenReturn(actual)
                    `when`(adapter.isEnabled).thenReturn(true)
                    `when`(adapter.getRemoteDevice(phone)).thenReturn(device)
                    `when`(device.address).thenReturn(phone)
                    // Android's public API uses this value both for absence and some IPC errors.
                    `when`(device.bondState).thenReturn(bond)
                    val transport = mock(AapTransport::class.java)
                    val control = AapBluetoothControl(transport, settings, context, 31)
                    val legacy = listOf(BLUETOOTH_PAIRING_NUMERIC_COMPARISON, BLUETOOTH_PAIRING_PIN)
                    assertEquals(if (active) legacy.take(1) else legacy, control.pairingMethods())
                    assertEquals(active, receiver != null)
                    val request = AapMessage(Channel.ID_BTH, AapBluetoothControl.PAIRING_REQUEST,
                        Bluetooth.PairingRequest.newBuilder().setPhoneAddress(phone).setPairingMethod(2).build())
                    control.execute(request)
                    if (!active) {
                        assertTrue(tasks.isEmpty()); verifyNoInteractions(transport)
                    } else {
                        // Query is queued: default UI delivery does not execute/get blocked by it.
                        verify(device, never()).bondState
                        val event = mock(Intent::class.java)
                        `when`(event.action).thenReturn(BluetoothDevice.ACTION_PAIRING_REQUEST)
                        `when`(event.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)).thenReturn(device)
                        `when`(event.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1))
                            .thenReturn(BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION)
                        `when`(event.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)).thenReturn(42)
                        repeat(17) {
                            control.execute(AapMessage(Channel.ID_BTH, AapBluetoothControl.AUTHENTICATION_RESULT,
                                Bluetooth.AuthenticationResult.newBuilder().setStatus(0).build()))
                        }
                        // A full peer-result quota must still preserve this non-retryable event.
                        receiver!!.onReceive(app, event)
                        verify(device, never()).bondState
                        while (tasks.isNotEmpty()) tasks.removeFirst().run()
                        val messages = argumentCaptor<AapMessage>()
                        val bonded = bond == BluetoothDevice.BOND_BONDED
                        verify(transport, times(if (bonded) 2 else 1)).send(messages.capture())
                        val response = messages.firstValue.parse(Bluetooth.PairingResponse.newBuilder()).build()
                        assertEquals(if (bonded) 0 else -11, response.status) // SUCCESS / BLUETOOTH_UNAVAILABLE
                        assertEquals(bonded, response.alreadyPaired)
                        if (bonded) {
                            val auth = messages.allValues[1]
                            assertEquals(AapBluetoothControl.AUTHENTICATION_DATA, auth.type)
                            assertEquals("000042", auth.parse(Bluetooth.AuthenticationData.newBuilder()).build().authData)
                        }
                    }
                    control.close()
                }
            }
        } } } }
    }

    @Test fun disabledAndUnsupportedModesPreserveLegacyAdvertisementAndSilence() {
        checkMode(enabled = false)
        checkMode(wireless = false)
        checkMode(loopback = true)
        checkMode(managerName = "vendor_bluetooth")
        checkMode(actual = "02:00:00:00:00:00")
        checkMode(actual = "32:34:56:78:9A:BC")
        checkMode(permission = -1)
        checkMode(registerFails = true)
    }

    @Test fun negativeAndroidReadCannotBecomeSuccessAndReceiverDoesNotRunBinderWork() {
        checkMode(active = true)
    }

    @Test fun confirmedBondForwardsNumericChallengeEvenWithFullPeerQueue() {
        checkMode(active = true, bond = BluetoothDevice.BOND_BONDED)
    }
}
