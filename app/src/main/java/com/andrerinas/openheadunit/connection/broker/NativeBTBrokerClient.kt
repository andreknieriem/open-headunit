package com.andrerinas.openheadunit.connection.broker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE client for the NativeBTBroker. The session stays open after the display snapshot is sent
 * so later RFCOMM bytes can cross the same link.
 */
class NativeBTBrokerClient(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private var attPayload = 20
    private var appSeq = 1
    private var transportSeq = 1
    private var writeBusy = false
    private var writeWithoutResponse = false
    private var activeResult: ((Result<Unit>) -> Unit)? = null
    private val pending = ArrayDeque<PendingWrite>()
    private val controlAssembly = Assembly()
    private val dataAssembly = Assembly()

    /** Reassembled frames from the broker, delivered on the main thread. */
    var onFrame: ((NativeBTBrokerProtocol.Frame) -> Unit)? = null

    /** The link dropped on its own. Not called when this client closed the socket. */
    var onLinkLost: (() -> Unit)? = null

    /**
     * A GATT write for RFCOMM bytes was rejected or reported failed after retries.
     * Delivery is uncertain or lost — the handshake stream should end.
     */
    var onTransportError: ((String) -> Unit)? = null

    private var linkGeneration = 0
    private var linkUp = false
    private var inFlight: PendingWrite? = null
    private var writeRetries = 0

    fun isReady(): Boolean = adapter?.isEnabled == true

    /**
     * Tells an already-open broker session that the phone's projection socket came up or went away.
     * [onRecorded] runs once, on the main thread, when that notice is written or cannot be written.
     */
    fun notifyProjection(up: Boolean, onRecorded: ((Boolean) -> Unit)? = null) {
        val link = gatt
        if (link == null) {
            if (onRecorded != null) main.post { onRecorded(false) }
            return
        }
        queueFrame(
            NativeBTBrokerProtocol.CMD_PROJECTION_UP,
            NativeBTBrokerProtocol.CH_CONTROL,
            byteArrayOf(if (up) 1 else 0),
            onRecorded = onRecorded,
        )
        writeNext(link)
    }

    /** Explicit HFP SLC. Does not change AA relay or PROJECTION_UP. */
    fun setHfp(on: Boolean, onRecorded: ((Boolean) -> Unit)? = null) {
        val link = gatt
        if (link == null) {
            if (onRecorded != null) main.post { onRecorded(false) }
            return
        }
        queueFrame(
            if (on) NativeBTBrokerProtocol.CMD_HFP_ON else NativeBTBrokerProtocol.CMD_HFP_OFF,
            NativeBTBrokerProtocol.CH_CONTROL,
            ByteArray(0),
            onRecorded = onRecorded,
        )
        writeNext(link)
    }

    /** Stay-alive probe: CMD_GET_STATUS write success means the BLE link still accepts commands. */
    fun ping(onRecorded: ((Boolean) -> Unit)? = null) {
        val link = gatt
        if (link == null) {
            if (onRecorded != null) main.post { onRecorded(false) }
            return
        }
        queueFrame(
            NativeBTBrokerProtocol.CMD_GET_STATUS,
            NativeBTBrokerProtocol.CH_CONTROL,
            ByteArray(0),
            onRecorded = onRecorded,
        )
        writeNext(link)
    }

    @SuppressLint("MissingPermission")
    fun scan(onDevice: (BluetoothDevice, String?) -> Unit, onFinished: () -> Unit) {
        stopScan()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onFinished()
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = advertisedName(result)
                main.post { onDevice(result.device, name) }
            }
        }
        scanCallback = callback
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        main.postDelayed({
            stopScan()
            onFinished()
        }, SCAN_MS)
    }

    @SuppressLint("MissingPermission")
    private fun advertisedName(result: ScanResult): String? {
        val record = result.scanRecord
        record?.deviceName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        parseLocalName(record?.bytes)?.let { return it }
        return try {
            result.device.name?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: SecurityException) {
            null
        }
    }

    /** Complete Local Name (0x09), then Shortened Local Name (0x08), from the raw advertisement. */
    private fun parseLocalName(bytes: ByteArray?): String? {
        if (bytes == null) return null
        var index = 0
        var shortened: String? = null
        while (index < bytes.size) {
            val length = bytes[index].toInt() and 0xFF
            if (length == 0 || index + length >= bytes.size) break
            val type = bytes[index + 1].toInt() and 0xFF
            if ((type == 0x09 || type == 0x08) && length > 1) {
                val name = String(bytes, index + 2, length - 1, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                if (type == 0x09 && name.isNotEmpty()) return name
                if (name.isNotEmpty()) shortened = name
            }
            index += length + 1
        }
        return shortened
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
    }

    /** Clear phone bonds on the broker and open Classic inquiry for 120 seconds. */
    fun allowPhonePairing(address: String, onResult: (Result<Unit>) -> Unit) {
        prepare(
            address,
            NativeBTBrokerProtocol.emptyDisplayInfo(),
            restartProjection = false,
            bondMode = NativeBTBrokerProtocol.BOND_MODE_NEW_PHONE,
            onResult = onResult,
        )
    }

    @SuppressLint("MissingPermission")
    fun prepare(
        address: String,
        info: NativeBTBrokerProtocol.DisplayInfo,
        restartProjection: Boolean = true,
        confirmProjection: Boolean = false,
        bondMode: Int? = null,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            onResult(Result.failure(IllegalStateException("invalid address")))
            return
        }
        if (linkUp && gatt != null) {
            main.post { reuse(info, restartProjection, confirmProjection, bondMode, onResult) }
            return
        }
        close()
        pending.clear()
        val generation = linkGeneration
        var delivered = false
        val report: (Result<Unit>) -> Unit = { result ->
            if (!delivered) {
                delivered = true
                main.post { onResult(result) }
            }
        }
        gatt = device.connectGatt(app, false, object : BluetoothGattCallback() {
            private fun live(callbackGatt: BluetoothGatt): Boolean =
                generation == linkGeneration && callbackGatt === this@NativeBTBrokerClient.gatt

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!live(gatt)) return
                    if (device.bondState == BluetoothDevice.BOND_NONE) {
                        device.createBond()
                    }
                    gatt.requestMtu(247)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Check generation before touching shared state so a stale disconnect cannot
                    // clear a newer connection's linkUp.
                    if (!live(gatt)) return
                    linkUp = false
                    linkGeneration++
                    report(Result.failure(IllegalStateException("disconnected")))
                    main.post { onLinkLost?.invoke() }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (!live(gatt)) return
                attPayload = (if (mtu >= 23) mtu else 23) - 3
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (!live(gatt)) return
                val service = gatt.getService(NativeBTBrokerProtocol.SERVICE_UUID)
                val command = service?.getCharacteristic(NativeBTBrokerProtocol.COMMAND_UUID)
                val event = service?.getCharacteristic(NativeBTBrokerProtocol.EVENT_UUID)
                val dataRx = service?.getCharacteristic(NativeBTBrokerProtocol.DATA_RX_UUID)
                val dataTx = service?.getCharacteristic(NativeBTBrokerProtocol.DATA_TX_UUID)
                if (command == null || event == null || dataRx == null || dataTx == null) {
                    fail(report, IllegalStateException("broker service missing"))
                    return
                }
                linkUp = true
                queueSetup(info, restartProjection, confirmProjection, bondMode)
                subscribe(gatt, event)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (!live(gatt)) return
                inbound(characteristic, value)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (!live(gatt)) return
                inbound(characteristic, characteristic.value ?: return)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (!live(gatt)) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(report, IllegalStateException("notify subscribe $status"))
                    return
                }
                val service = gatt.getService(NativeBTBrokerProtocol.SERVICE_UUID)
                val dataTx = service?.getCharacteristic(NativeBTBrokerProtocol.DATA_TX_UUID)
                if (descriptor.characteristic.uuid == NativeBTBrokerProtocol.EVENT_UUID && dataTx != null) {
                    subscribe(gatt, dataTx)
                    return
                }
                activeResult = report
                writeNext(gatt)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                main.post {
                    if (!live(gatt)) return@post
                    // WRITE_NR has no reliable completion; pacing already advanced after accept.
                    if (writeWithoutResponse) return@post
                    writeBusy = false
                    val flight = inFlight
                    inFlight = null
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (flight?.onRecorded != null) {
                            flight.onRecorded.invoke(false)
                            writeNext(gatt)
                            return@post
                        }
                        onTransportError?.invoke("write status $status")
                        fail(report, IllegalStateException("write status $status"))
                        return@post
                    }
                    flight?.onRecorded?.invoke(true)
                    writeNext(gatt)
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    fun sendRfcomm(bytes: ByteArray): Boolean {
        val link = gatt ?: return false
        if (!linkUp) return false
        queueFrame(NativeBTBrokerProtocol.DATA_RFCOMM_TX, NativeBTBrokerProtocol.CH_RFCOMM, bytes)
        writeNext(link)
        return true
    }

    fun sendCredit(extra: Int) {
        val link = gatt ?: return
        queueFrame(NativeBTBrokerProtocol.EVT_CREDIT, NativeBTBrokerProtocol.CH_CONTROL, NativeBTBrokerProtocol.creditPayload(extra))
        writeNext(link)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        linkGeneration++
        linkUp = false
        stopScan()
        val abandoned = mutableListOf<(Boolean) -> Unit>()
        inFlight?.onRecorded?.let { abandoned.add(it) }
        inFlight = null
        pending.mapNotNullTo(abandoned) { it.onRecorded }
        pending.clear()
        writeBusy = false
        writeWithoutResponse = false
        writeRetries = 0
        activeResult = null
        controlAssembly.reset()
        dataAssembly.reset()
        gatt?.close()
        gatt = null
        abandoned.forEach { it(false) }
    }

    private fun inbound(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val assembly = if (characteristic.uuid == NativeBTBrokerProtocol.DATA_TX_UUID) dataAssembly else controlAssembly
        val frame = assembly.push(value) ?: return
        val decoded = try {
            NativeBTBrokerProtocol.decode(frame)
        } catch (_: Exception) {
            return
        }
        main.post { onFrame?.invoke(decoded) }
    }

    private fun reuse(
        info: NativeBTBrokerProtocol.DisplayInfo,
        restartProjection: Boolean,
        confirmProjection: Boolean,
        bondMode: Int?,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val link = gatt
        if (!linkUp || link == null) {
            onResult(Result.failure(IllegalStateException("disconnected")))
            return
        }
        queueSetup(info, restartProjection, confirmProjection, bondMode)
        val report: (Result<Unit>) -> Unit = { result -> onResult(result) }
        val previous = activeResult
        activeResult = if (previous == null) {
            report
        } else {
            { result ->
                previous(result)
                report(result)
            }
        }
        writeNext(link)
    }

    /**
     * [restartProjection] sends HFP_ON then AA_ENABLE (apply_hfp). A main-firmware
     * broker ignores HFP_ON and still starts HFP from AA_ENABLE.
     * [confirmProjection] is PROJECTION_UP only — HFP_OFF is sent separately.
     * [bondMode] is only for the Pair action; ordinary connect must not open Classic inquiry.
     */
    private fun queueSetup(
        info: NativeBTBrokerProtocol.DisplayInfo,
        restartProjection: Boolean,
        confirmProjection: Boolean,
        bondMode: Int? = null,
    ) {
        queueFrame(NativeBTBrokerProtocol.CMD_HELLO, NativeBTBrokerProtocol.CH_CONTROL, ByteArray(0))
        if (info.ssid.isNotEmpty() || info.ipAddress.isNotEmpty()) {
            queueFrame(
                NativeBTBrokerProtocol.CMD_SET_NETWORK,
                NativeBTBrokerProtocol.CH_CONTROL,
                NativeBTBrokerProtocol.networkPayload(info),
            )
        }
        if (restartProjection) {
            queueFrame(NativeBTBrokerProtocol.CMD_HFP_ON, NativeBTBrokerProtocol.CH_CONTROL, ByteArray(0))
            queueFrame(NativeBTBrokerProtocol.CMD_AA_ENABLE, NativeBTBrokerProtocol.CH_CONTROL, ByteArray(0))
        } else if (confirmProjection) {
            queueFrame(NativeBTBrokerProtocol.CMD_PROJECTION_UP, NativeBTBrokerProtocol.CH_CONTROL, byteArrayOf(1))
        }
        if (bondMode != null) {
            // Clear/open the pairing window first, then arm HFP (do not dial old bonds).
            queueFrame(
                NativeBTBrokerProtocol.CMD_SET_BOND_MODE,
                NativeBTBrokerProtocol.CH_CONTROL,
                NativeBTBrokerProtocol.bondModePayload(bondMode),
            )
            if (bondMode != NativeBTBrokerProtocol.BOND_MODE_OFF) {
                queueFrame(NativeBTBrokerProtocol.CMD_HFP_ON, NativeBTBrokerProtocol.CH_CONTROL, ByteArray(0))
            }
        }
        queueFrame(NativeBTBrokerProtocol.CMD_GET_STATUS, NativeBTBrokerProtocol.CH_CONTROL, ByteArray(0))
    }

    private fun queueFrame(
        type: Int,
        channel: Int,
        payload: ByteArray,
        onRecorded: ((Boolean) -> Unit)? = null,
    ) {
        val frame = NativeBTBrokerProtocol.encode(type, channel, appSeq++, payload)
        val uuid = if (channel == NativeBTBrokerProtocol.CH_RFCOMM) {
            NativeBTBrokerProtocol.DATA_RX_UUID
        } else {
            NativeBTBrokerProtocol.COMMAND_UUID
        }
        val writeType = if (channel == NativeBTBrokerProtocol.CH_RFCOMM) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val parts = NativeBTBrokerProtocol.fragment(frame, attPayload, transportSeq++)
        parts.forEachIndexed { index, bytes ->
            pending.add(
                PendingWrite(
                    uuid,
                    writeType,
                    bytes,
                    onRecorded = if (index == parts.lastIndex) onRecorded else null,
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD)
        if (descriptor == null) {
            return
        }
        // Broker indicates RFCOMM bytes; indication ACKs keep the BLINK-style relay ordered.
        val enable = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, enable)
        } else {
            descriptor.value = enable
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNext(gatt: BluetoothGatt) {
        if (writeBusy) return
        if (gatt !== this.gatt) return
        val next = pending.poll()
        if (next == null) {
            val done = activeResult
            activeResult = null
            if (done != null) main.post { done(Result.success(Unit)) }
            return
        }
        val service = gatt.getService(NativeBTBrokerProtocol.SERVICE_UUID)
        val characteristic = service?.getCharacteristic(next.uuid)
        if (characteristic == null) {
            writeBusy = false
            inFlight = null
            if (next.onRecorded != null) {
                next.onRecorded.invoke(false)
                writeNext(gatt)
                return
            }
            // RFCOMM chunk already left the queue — fail the stream rather than drop silently.
            onTransportError?.invoke("RFCOMM characteristic missing")
            return
        }
        writeBusy = true
        inFlight = next
        writeWithoutResponse = next.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, next.bytes, next.writeType) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = next.writeType
            characteristic.value = next.bytes
            gatt.writeCharacteristic(characteristic)
        }
        if (!ok) {
            writeBusy = false
            inFlight = null
            writeRetries++
            if (writeRetries <= MAX_WRITE_RETRIES) {
                // Keep the same chunk until the stack accepts it (congestion / transient busy).
                pending.addFirst(next)
                main.postDelayed({ writeNext(gatt) }, WRITE_RETRY_MS)
                return
            }
            writeRetries = 0
            if (next.onRecorded != null) {
                next.onRecorded.invoke(false)
                writeNext(gatt)
                return
            }
            onTransportError?.invoke("write rejected")
            val done = activeResult
            activeResult = null
            if (done != null) fail(done, IllegalStateException("write rejected"))
            return
        }
        writeRetries = 0
        if (writeWithoutResponse) {
            // Stack accepted the buffer; WRITE_NR has no reliable completion callback.
            writeBusy = false
            inFlight = null
            main.postDelayed({ writeNext(gatt) }, WRITE_PACE_MS)
        }
    }

    private fun fail(onResult: (Result<Unit>) -> Unit, error: Exception) {
        main.post {
            close()
            onResult(Result.failure(error))
        }
    }

    companion object {
        private const val SCAN_MS = 8_000L
        private const val WRITE_PACE_MS = 15L
        private const val WRITE_RETRY_MS = 20L
        private const val MAX_WRITE_RETRIES = 8
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private data class PendingWrite(
        val uuid: UUID,
        val writeType: Int,
        val bytes: ByteArray,
        val onRecorded: ((Boolean) -> Unit)? = null,
    )

    private class Assembly {
        private var seq = -1
        private var count = 0
        private var expect = 0
        private var body = ByteArray(0)

        fun reset() {
            seq = -1
            count = 0
            expect = 0
            body = ByteArray(0)
        }

        fun push(piece: ByteArray): ByteArray? {
            if (piece.size < 4) return null
            val transport = (piece[0].toInt() and 0xFF) or (piece[1].toInt() and 0xFF shl 8)
            val index = piece[2].toInt() and 0xFF
            val total = piece[3].toInt() and 0xFF
            if (total == 0 || index >= total) return null
            if (seq != transport || index == 0) {
                seq = transport
                count = total
                expect = 0
                body = ByteArray(0)
            }
            if (index != expect || total != count) {
                reset()
                return null
            }
            body += piece.copyOfRange(4, piece.size)
            expect++
            if (expect != count) return null
            val frame = body
            reset()
            return frame
        }
    }
}
