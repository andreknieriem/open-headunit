package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth
import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** The GAL pairing service reports existing OS bonds; it never tears down or fakes a bond. */
internal class AapControlBluetooth(
    context: Context,
    private val send: (AapMessage, () -> Boolean) -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "GalBluetooth") },
    private val handsFree: GalHandsFreeLink = GalHandsFreeLink(),
    private val lookup: (Bluetooth.BluetoothPairingRequest, String?) -> GalBluetoothPairing.Result = { request, address ->
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        // Only this adapter's address ties its bond database to the advertised radio.
        // Global vendor properties may describe a separate module.
        val local = adapter?.address
        val enabled = adapter?.isEnabled == true
        val bonded = adapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
        val matchingRadio = enabled && GalBluetoothPairing.validAddress(local) &&
            local.equals(address, ignoreCase = true)
        val peers = if (matchingRadio && bonded.any { it.equals(request.phoneAddress, true) })
            handsFree.connectedPhones(context, checkNotNull(adapter)) else null
        GalBluetoothPairing.response(request.phoneAddress, request.pairingMethod, address,
            local, enabled, bonded, peers)
    }
) : AapControl {
    @Volatile private var advertisedAddress: String? = null
    private val pending = AtomicBoolean()
    private val generation = AtomicLong()

    fun advertise(address: String?) { advertisedAddress = address }
    fun closeChannel() { generation.incrementAndGet() }
    fun shutdown() { closeChannel(); worker.shutdownNow() }

    override fun execute(message: AapMessage): Int {
        if (advertisedAddress == null || message.size > 1024) return 0
        when (message.type) {
            0x8001 -> {
                val request = try { message.parse(Bluetooth.BluetoothPairingRequest.newBuilder()).build() }
                catch (e: Exception) { AppLog.w("Invalid GAL Bluetooth pairing request"); return 0 }
                // The sender waits for this response; repeated requests must not build an
                // unbounded Binder work queue while a vendor Bluetooth service is stalled.
                if (!pending.compareAndSet(false, true)) return 0
                val owner = generation.get()
                val address = advertisedAddress
                try {
                    worker.execute {
                        try {
                            if (generation.get() != owner || worker.isShutdown) return@execute
                            // Bluetooth Binder calls stay off the shared audio/ACK/Ping receive path.
                            val result = try { lookup(request, address) }
                            catch (e: Exception) {
                                // Includes Android 12+ BLUETOOTH_CONNECT denial and vendor failures.
                                GalBluetoothPairing.Result(Common.MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE)
                            }
                            if (generation.get() == owner && !worker.isShutdown) {
                                send(AapMessage(Channel.ID_BTH, 0x8002,
                                    Bluetooth.BluetoothPairingResponse.newBuilder().setStatus(result.status)
                                        .setAlreadyPaired(result.alreadyPaired).build())) {
                                    generation.get() == owner && !worker.isShutdown
                                }
                            }
                        } finally { pending.set(false) }
                    }
                } catch (_: RejectedExecutionException) { pending.set(false) }
            }
            0x8004 -> {
                val result = try { message.parse(Bluetooth.BluetoothAuthenticationResult.newBuilder()).build() }
                catch (e: Exception) { AppLog.w("Invalid GAL Bluetooth authentication result"); return 0 }
                // No authentication data is issued by this service: normal Android pairing
                // owns confirmation/PIN dialogs. This notification cannot grant a bond or HFP.
                AppLog.d("GAL Bluetooth authentication result: ${result.status}")
            }
        }
        return 0
    }
}
