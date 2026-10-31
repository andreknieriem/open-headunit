package com.andrerinas.openheadunit.connection.projection

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbNative
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class LibusbProjectionConnection(usbMgr: UsbManager,
                                 device: UsbDevice,
                                 private val admission: ConnectionAdmission
) : AbstractUsbProjectionConnection(usbMgr, device) {

    @Volatile
    override var isConnected = false
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    @Volatile private var endpointOut: UsbEndpoint? = null
    private var usbNative: UsbNative? = null

    // Direct ByteBuffer for JNI and tracking leftover state
    private val readBuffer = ByteBuffer.allocateDirect(16384)
    private var leftoverSize = 0
    private var leftoverPos = 0

    private val activeTransfers = AtomicInteger(0)

    override val type = ProjectionConnection.Type.USB

    override val outMaxPacketSize: Int
        get() = endpointOut?.maxPacketSize ?: 0

    override val isSingleMessage: Boolean
        get() = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val owner = OpenAttempt()
        var reserved = false
        admission.run {
            synchronized(stateLock) {
                if (!isConnected && opening == null && usbDeviceConnection == null) {
                    opening = owner
                    reserved = true
                }
            }
        }
        if (!reserved) return@withContext false
        var connection: UsbDeviceConnection? = null
        var iface: UsbInterface? = null
        var claimed = false
        var native: UsbNative? = null
        var published = false
        try {
            if (!mayOpen(owner) || !usbMgr.hasPermission(device)) return@withContext false
            for (i in 0 until 3) {
                if (!mayOpen(owner)) return@withContext false
                try {
                    connection = usbMgr.openDevice(device)
                    if (connection != null) break
                } catch (failure: Throwable) {
                    AppLog.w("LibusbAccessoryConnection: Attempt ${i + 1} to openDevice failed: ${failure.message}")
                }
                if (i < 2) repeat(10) {
                    if (!mayOpen(owner)) return@withContext false
                    try { Thread.sleep(100) } catch (_: Exception) {}
                }
            }
            val candidate = connection ?: return@withContext false
            if (!mayOpen(owner) || device.interfaceCount <= 0) return@withContext false
            iface = device.getInterface(0)
            if (!mayOpen(owner) || !candidate.claimInterface(iface, true)) return@withContext false
            claimed = true
            val (input, output) = UsbDeviceCompat.selectEndpoints(checkNotNull(iface))
            if (input == null || output == null || !mayOpen(owner)) return@withContext false
            val wrapper = UsbNative()
            native = wrapper
            if (!mayOpen(owner)) return@withContext false
            if (!wrapper.wrap(candidate, input.address, output.address)) return@withContext false
            admission.run {
                synchronized(stateLock) {
                    if (opening === owner) {
                        usbDeviceConnection = candidate
                        usbInterface = iface
                        endpointIn = input
                        endpointOut = output
                        usbNative = wrapper
                        isConnected = true
                        opening = null
                        published = true
                    }
                }
            }
            if (published) AppLog.i("LibusbAccessoryConnection: Successfully connected via JNI Libusb")
            published
        } catch (failure: Exception) {
            AppLog.e("LibusbAccessoryConnection: Error during connect: ${failure.message}")
            false
        } finally {
            try {
                if (!published) {
                    try { native?.close() } catch (_: Exception) {}
                    if (claimed) try { connection?.releaseInterface(iface) } catch (_: Exception) {}
                    try { connection?.close() } catch (_: Exception) {}
                }
            } finally {
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
        val (retired, pendingOpen) = synchronized(stateLock) {
            val pendingOpen = opening
            opening = null
            isConnected = false
            val retired = Triple(usbNative, usbDeviceConnection, usbInterface)
            usbNative = null
            usbDeviceConnection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
            leftoverSize = 0
            leftoverPos = 0
            retired to pendingOpen
        }
        pendingOpen?.awaitFinished()
        // Retired resources are local: an old cleanup never reads or clears a successor's fields.
        val start = SystemClock.elapsedRealtime()
        while (activeTransfers.get() > 0 && SystemClock.elapsedRealtime() - start < 1500) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        try { retired.first?.close() } catch (failure: Exception) {
            AppLog.e("LibusbAccessoryConnection: Error closing native: ${failure.message}")
        }
        if (retired.second != null && retired.third != null) {
            try { retired.second?.releaseInterface(retired.third) } catch (_: Exception) {}
        }
        try { retired.second?.close() } catch (_: Exception) {}
    }

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        if (!isConnected) return -1
        val native = usbNative ?: return -1
        activeTransfers.incrementAndGet()
        try {
            if (!isConnected) return -1
            return native.write(buf, length, timeout)
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        if (!isConnected) return -1
        val native = usbNative ?: return -1
        activeTransfers.incrementAndGet()
        try {
            if (!isConnected) return -1
            var totalReturned = 0
            val overallStart = SystemClock.elapsedRealtime()

            while (totalReturned < length && isConnected) {
                if (leftoverSize > 0) {
                    val available = leftoverSize - leftoverPos
                    val toCopy = minOf(length - totalReturned, available)

                    readBuffer.position(leftoverPos)
                    readBuffer.get(buf, totalReturned, toCopy)

                    leftoverPos += toCopy
                    totalReturned += toCopy

                    if (leftoverPos >= leftoverSize) {
                        leftoverSize = 0
                        leftoverPos = 0
                    }

                    if (totalReturned >= length || !readFully) break
                    continue
                }

                if (!isConnected) break

                val jniTimeout = if (timeout <= 0) {
                    1000
                } else {
                    val elapsed = SystemClock.elapsedRealtime() - overallStart
                    val remaining = timeout - elapsed
                    if (remaining <= 0) break
                    minOf(remaining.toInt(), 1000)
                }

                readBuffer.clear()
                val transferred = native.read(readBuffer, jniTimeout)
                if (transferred < 0) {
                    isConnected = false
                    return if (totalReturned > 0) totalReturned else -1
                }
                if (transferred == 0) {
                    if (timeout > 0) {
                        val elapsed = SystemClock.elapsedRealtime() - overallStart
                        if (elapsed >= timeout) {
                            break
                        }
                    }
                    continue
                }

                leftoverSize = transferred
                leftoverPos = 0
            }

            return totalReturned
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    companion object {
    }
}
