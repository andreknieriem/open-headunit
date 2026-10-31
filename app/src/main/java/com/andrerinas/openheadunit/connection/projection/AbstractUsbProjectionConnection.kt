package com.andrerinas.openheadunit.connection.projection

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat

abstract class AbstractUsbProjectionConnection(
    protected val usbMgr: UsbManager, protected val device: UsbDevice
) : ProjectionConnection {

    @Volatile protected var usbDeviceConnection: UsbDeviceConnection? = null
    protected val stateLock = Any()

    /** A local open can be revoked under stateLock, then joined outside every monitor. */
    protected class OpenAttempt {
        val finished = java.util.concurrent.CountDownLatch(1)
        fun awaitFinished() {
            var interrupted = false
            while (true) {
                try { finished.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    // Guarded by stateLock; never wait for it while that monitor is held.
    protected var opening: OpenAttempt? = null

    /** Actual bulk OUT endpoint capacity; zero means no endpoint is available. */
    abstract val outMaxPacketSize: Int

    /** The enumeration used by this attempt, even after disconnect has cleared the handle. */
    internal val accessoryDevice: UsbDevice get() = device

    fun isDeviceRunning(device: UsbDevice): Boolean {
        synchronized(stateLock) {
            if (!isConnected)
                return false

            return UsbDeviceCompat.getUniqueName(device) == UsbDeviceCompat.getUniqueName(this.device)
        }
    }
}
