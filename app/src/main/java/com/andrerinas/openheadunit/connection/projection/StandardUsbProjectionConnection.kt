package com.andrerinas.openheadunit.connection.projection

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StandardUsbProjectionConnection(usbMgr: UsbManager, device: UsbDevice,
    private val admission: ConnectionAdmission) :
    AbstractUsbProjectionConnection(usbMgr, device) {

    // @Volatile so isConnected / isDeviceRunning see the latest value without a lock.
    @Volatile private var usbDeviceConnected: UsbDeviceCompat? = null
    private var usbInterface: UsbInterface? = null
    // @Volatile so sendBlocking / recvBlocking see connection publication and retirement
    // without holding sStateLock during the transfer.
    @Volatile private var endpointIn: UsbEndpoint? = null
    @Volatile private var endpointOut: UsbEndpoint? = null

    // Internal buffer — only ever accessed by the poll thread (recvBlocking); no lock needed.
    private val internalBuffer = ByteArray(16384)
    private var internalBufferPos = 0
    private var internalBufferAvailable = 0

    override val type = ProjectionConnection.Type.USB

    override val outMaxPacketSize: Int
        get() = endpointOut?.maxPacketSize ?: 0

    override val isConnected: Boolean
        get() = usbDeviceConnected != null

    override val isSingleMessage: Boolean
        get() = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val owner = OpenAttempt()
        var reserved = false
        admission.run {
            synchronized(stateLock) {
                if (opening == null && usbDeviceConnection == null) {
                    opening = owner
                    reserved = true
                }
            }
        }
        if (!reserved) return@withContext false
        var connection: UsbDeviceConnection? = null
        var iface: UsbInterface? = null
        var claimed = false
        var published = false
        try {
            var lastError: Throwable? = null
            for (i in 0 until 3) {
                if (!mayOpen(owner)) return@withContext false
                try {
                    if (!usbMgr.hasPermission(device)) {
                        AppLog.w("No permission for USB device ${UsbDeviceCompat.getUniqueName(device)}. Waiting for system popup...")
                    } else {
                        // hasPermission and the retry sleep are both external waits. The exact
                        // request must still own this open immediately before each USB effect.
                        if (!mayOpen(owner)) return@withContext false
                        connection = usbMgr.openDevice(device)
                        if (connection != null) break
                    }
                } catch (failure: Throwable) {
                    lastError = failure
                    AppLog.w("Attempt ${i + 1} to openDevice failed: ${failure.message}")
                }
                if (i < 2) try { Thread.sleep(1000) } catch (_: Exception) {}
            }
            val candidate = connection ?: throw UsbOpenException(lastError ?: Throwable("openDevice: connection is null (Permission missing?)"))
            if (!mayOpen(owner)) return@withContext false
            if (device.interfaceCount <= 0) throw UsbOpenException("No usb interfaces")
            iface = device.getInterface(0)
            if (!mayOpen(owner)) return@withContext false
            if (!candidate.claimInterface(iface, true)) throw UsbOpenException("Error claiming interface")
            claimed = true
            val (input, output) = UsbDeviceCompat.selectEndpoints(checkNotNull(iface))
            if (input == null || output == null) throw UsbOpenException("Unable to find an endpoint pair on the accessory interface")
            val connected = UsbDeviceCompat(device)
            // Only publication holds stateLock. USB/Binder operations and all waits above run
            // outside it; admission orders lifecycle -> arbiter -> driver state.
            admission.run {
                synchronized(stateLock) {
                    if (opening === owner) {
                        usbDeviceConnection = candidate
                        usbInterface = iface
                        endpointIn = input
                        endpointOut = output
                        usbDeviceConnected = connected
                        opening = null
                        published = true
                    }
                }
            }
            published
        } catch (failure: Exception) {
            AppLog.e(failure)
            false
        } finally {
            try {
                if (!published) {
                    if (claimed) try { connection?.releaseInterface(iface) } catch (_: Exception) {}
                    try { connection?.close() } catch (_: Exception) {}
                }
            } finally {
                // Keep the retiring open reachable until its local handle has actually closed.
                synchronized(stateLock) { if (opening === owner) opening = null }
                owner.finished.countDown()
            }
        }
    }

    private fun mayOpen(owner: OpenAttempt): Boolean {
        var current = false
        admission.run { synchronized(stateLock) { current = opening === owner } }
        return current
    }

    override fun disconnect() {
        val retired = synchronized(stateLock) {
            // A late open can close only its local handle; it can no longer publish here.
            val retired = Triple(usbDeviceConnection, usbInterface, opening)
            opening = null
            usbDeviceConnection = null
            usbInterface = null
            usbDeviceConnected = null
            endpointIn = null
            endpointOut = null
            internalBufferPos = 0
            internalBufferAvailable = 0
            retired
        }
        // close() aborts in-flight bulkTransfer without blocking any ownership monitor.
        retired.first?.close()
        if (retired.first != null && retired.second != null) {
            try { retired.first?.releaseInterface(retired.second) } catch (_: Exception) {}
        }
        // A successor waits for actual retirement, including a handle returning late from open.
        retired.third?.awaitFinished()
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
