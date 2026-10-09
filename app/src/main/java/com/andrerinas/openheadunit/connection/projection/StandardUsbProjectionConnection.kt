package com.andrerinas.openheadunit.connection.projection

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StandardUsbProjectionConnection(usbMgr: UsbManager, device: UsbDevice) :
    AbstractUsbProjectionConnection(usbMgr, device) {

    // @Volatile so isConnected / isDeviceRunning see the latest value without a lock.
    @Volatile private var usbDeviceConnected: UsbDeviceCompat? = null
    private var usbInterface: UsbInterface? = null
    // @Volatile so sendBlocking / recvBlocking see updates from connect() / resetInterface()
    // without holding sStateLock during the transfer.
    @Volatile private var endpointIn: UsbEndpoint? = null
    @Volatile private var endpointOut: UsbEndpoint? = null

    // Internal buffer — only ever accessed by the poll thread (recvBlocking); no lock needed.
    private val internalBuffer = ByteArray(16384)
    private var internalBufferPos = 0
    private var internalBufferAvailable = 0

    override val type = ProjectionConnection.Type.USB

    override val isConnected: Boolean
        get() = usbDeviceConnected != null

    override val isSingleMessage: Boolean
        get() = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        return@withContext try {
            connect(device)
        } catch (e: UsbOpenException) {
            AppLog.e(e)
            false
        }
    }

    @Throws(UsbOpenException::class)
    private fun connect(device: UsbDevice): Boolean {
        if (usbDeviceConnection != null) {
            disconnect()
        }
        synchronized(stateLock) {
            try {
                usbOpen(device)
            } catch (e: UsbOpenException) {
                disconnect()
                throw e
            }

            val ret = initEndpoint()
            if (ret < 0) {
                disconnect()
                return false
            }

            usbDeviceConnected = UsbDeviceCompat(device)
            return true
        }
    }

    @Throws(UsbOpenException::class)
    private fun usbOpen(device: UsbDevice) {
        var connection: UsbDeviceConnection? = null
        var lastError: Throwable? = null

        for (i in 0 until 3) {
            try {
                if (!usbMgr.hasPermission(device)) {
                    AppLog.w("No permission for USB device ${UsbDeviceCompat.getUniqueName(device)}. Waiting for system popup...")
                    // We can't request permission here easily because we need a BroadcastReceiver.
                    // But we can wait and see if the user granted it via the system dialog.
                } else {
                    connection = usbMgr.openDevice(device)
                    if (connection != null) break
                }
            } catch (t: Throwable) {
                lastError = t
                AppLog.w("Attempt ${i+1} to openDevice failed: ${t.message}")
            }
            if (i < 2) try { Thread.sleep(1000) } catch (_: Exception) {}
        }

        usbDeviceConnection = connection ?: throw UsbOpenException(lastError ?: Throwable("openDevice: connection is null (Permission missing?)"))

        AppLog.i("Established connection: " + usbDeviceConnection!!)

        try {
            val interfaceCount = device.interfaceCount
            if (interfaceCount <= 0) {
                AppLog.e("interfaceCount: $interfaceCount")
                throw UsbOpenException("No usb interfaces")
            }
            AppLog.i("interfaceCount: $interfaceCount")
            usbInterface = device.getInterface(0)

            if (!usbDeviceConnection!!.claimInterface(usbInterface, true)) {
                throw UsbOpenException("Error claiming interface")
            }
        } catch (e: Throwable) {
            AppLog.e(e)
            throw UsbOpenException(e)
        }
    }

    private fun initEndpoint(): Int {
        AppLog.i("Check accessory endpoints")
        val (selectedIn, selectedOut) = UsbDeviceCompat.selectEndpoints(usbInterface!!)
        endpointIn = selectedIn
        endpointOut = selectedOut
        if (endpointIn == null || endpointOut == null) {
            AppLog.e("Unable to find an endpoint pair on the accessory interface")
            return -1
        }

        AppLog.i("Connected have EPs")
        return 0
    }

    private fun resetInterface() {
        if (usbDeviceConnection == null) return
        synchronized(stateLock) {
            val connection = usbDeviceConnection ?: return
            val iface = usbInterface ?: return
            AppLog.w("Attempting USB interface soft-reset...")
            try {
                connection.releaseInterface(iface)
                Thread.sleep(100)
                if (connection.claimInterface(iface, true)) {
                    AppLog.i("USB interface re-claimed successfully")
                    internalBufferPos = 0
                    internalBufferAvailable = 0
                    initEndpoint()
                } else {
                    AppLog.e("Failed to re-claim USB interface — disconnecting")
                    disconnect()
                }
            } catch (e: Exception) {
                AppLog.e("Error during USB reset: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        // close() is thread-safe and immediately aborts any in-flight bulkTransfer(),
        // so both sendBlocking and recvBlocking unblock within milliseconds.
        val earlyClosed = usbDeviceConnection
        earlyClosed?.close()

        synchronized(stateLock) {
            // openDevice can publish a handle while we wait for this lock. The early close
            // only interrupted the old handle; close a late one before dropping its reference.
            val retiring = usbDeviceConnection
            if (retiring !== earlyClosed) retiring?.close()
            if (usbDeviceConnected != null) {
                AppLog.i(usbDeviceConnected!!.toString())
            }
            endpointIn = null
            endpointOut = null

            if (usbDeviceConnection != null) {
                var bret = false
                if (usbInterface != null) {
                    // releaseInterface() may fail since close() was already called; log and continue.
                    bret = try { usbDeviceConnection!!.releaseInterface(usbInterface) } catch (_: Exception) { false }
                }
                if (bret) {
                    AppLog.i("OK releaseInterface()")
                } else {
                    AppLog.e("Error releaseInterface()")
                }
            }
            usbDeviceConnection = null
            usbInterface = null
            usbDeviceConnected = null
            internalBufferPos = 0
            internalBufferAvailable = 0
        }
    }

    // Read error tracking — only accessed by the poll thread; no lock needed.
    private var consecutiveReadErrors = 0
    private var firstErrorTimeMs = 0L
    private var lastSuccessTimeMs = SystemClock.elapsedRealtime()
    private val maxErrorDurationBeforeDisconnect = 60_000L  // 60s patience for dongle WiFi recovery
    /** Minimum gap (ms) between a successful read and a new error before treating it as a new burst. */
    private val errorBurstResetGapMs = 5_000L

    // Volatile reads capture the latest connection/endpoint references; bulkTransfer runs
    // entirely outside any lock. If disconnect() calls close() concurrently, bulkTransfer
    // returns -1 immediately — a safe, recoverable outcome.
    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val connection = usbDeviceConnection ?: return -1
        val ep = endpointOut ?: return -1
        return try {
            connection.bulkTransfer(ep, buf, length, timeout)
        } catch (e: Exception) {
            AppLog.e("USB Write Error: ${e.message}")
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val connection = usbDeviceConnection ?: return -1
        val ep = endpointIn ?: return -1

        return try {
            var totalReturned = 0

            while (totalReturned < length) {
                // 1. Serve from internal buffer if data is available
                if (internalBufferAvailable > 0) {
                    val toCopy = minOf(length - totalReturned, internalBufferAvailable)
                    System.arraycopy(internalBuffer, internalBufferPos, buf, totalReturned, toCopy)
                    internalBufferPos += toCopy
                    internalBufferAvailable -= toCopy
                    totalReturned += toCopy

                    if (totalReturned >= length || !readFully) break
                    continue
                }

                // 2. Internal buffer empty, read new 16KB chunk from USB
                val read = try {
                    connection.bulkTransfer(ep, internalBuffer, internalBuffer.size, timeout)
                } catch (e: Exception) {
                    AppLog.e("USB Read Error: ${e.message}")
                    -1
                }

                if (read < 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors == 1) {
                        // New error burst: if the last successful read was long enough ago,
                        // this is a fresh burst — reset the timer.
                        val gapSinceSuccess = SystemClock.elapsedRealtime() - lastSuccessTimeMs
                        if (gapSinceSuccess > errorBurstResetGapMs || firstErrorTimeMs == 0L) {
                            firstErrorTimeMs = SystemClock.elapsedRealtime()
                        }
                    }
                    val errorDurationMs = SystemClock.elapsedRealtime() - firstErrorTimeMs
                    if (errorDurationMs > maxErrorDurationBeforeDisconnect) {
                        AppLog.e("USB read errors persisting for ${errorDurationMs / 1000}s — disconnecting")
                        disconnect()
                        return -1
                    }
                    if (consecutiveReadErrors % 10 == 0) {
                        AppLog.w("USB read errors ($consecutiveReadErrors) for ${errorDurationMs / 1000}s — waiting for recovery...")
                    }
                    if (consecutiveReadErrors >= 5) {
                        try { Thread.sleep(200) } catch (_: InterruptedException) {}
                    }
                    return if (totalReturned > 0) totalReturned else -1
                }
                // If we reach here, read is 0 or positive, meaning no error.
                // Reset error counters if they were active.
                if (consecutiveReadErrors > 0) {
                    AppLog.i("USB reads recovered after $consecutiveReadErrors errors")
                    consecutiveReadErrors = 0
                    firstErrorTimeMs = 0
                }
                lastSuccessTimeMs = SystemClock.elapsedRealtime()

                if (read == 0) {
                    return totalReturned
                }

                internalBufferPos = 0
                internalBufferAvailable = read
                // Loop will continue and serve from the new internalBuffer data
            }

            totalReturned

        } catch (e: Exception) {
            AppLog.e("USB Read Error: ${e.message}")
            -1
        }
    }

    private class UsbOpenException : Exception {
        constructor(message: String) : super(message)
        constructor(tr: Throwable) : super(tr)
    }
}
