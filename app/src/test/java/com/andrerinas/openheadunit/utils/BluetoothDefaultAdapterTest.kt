package com.andrerinas.openheadunit.utils

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [23, 35])
class BluetoothDefaultAdapterTest {
    private fun defaultAdapter(service: Any?): BluetoothAdapter? {
        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(service)
        return BluetoothHelper::class.java.getDeclaredMethod("getDefaultAdapter", Context::class.java)
            .apply { isAccessible = true }.invoke(BluetoothHelper, context) as BluetoothAdapter?
    }

    @Test fun `missing system manager leaves Bluetooth unavailable instead of crashing startup`() {
        assertNull(defaultAdapter(null))
    }

    @Test fun `a manager without a radio leaves Bluetooth unavailable`() {
        val manager = mock(BluetoothManager::class.java)
        `when`(manager.adapter).thenReturn(null)
        assertNull(defaultAdapter(manager))
    }

    @Test fun `an available system radio remains the selected adapter`() {
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(manager.adapter).thenReturn(adapter)
        assertSame(adapter, defaultAdapter(manager))
    }
}
