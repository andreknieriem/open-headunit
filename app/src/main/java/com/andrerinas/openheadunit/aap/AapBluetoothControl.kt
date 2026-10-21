package com.andrerinas.openheadunit.aap

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.bluetooth.BluetoothPairingRequests
import com.andrerinas.openheadunit.aap.bluetooth.BluetoothPairingWire
import com.andrerinas.openheadunit.aap.bluetooth.BluetoothPairingSession
import com.andrerinas.openheadunit.aap.bluetooth.BluetoothPairingSession.Bond
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.BluetoothAnnouncePolicy
import com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth
import com.andrerinas.openheadunit.aap.protocol.proto.Common.MessageStatus
import com.andrerinas.openheadunit.aap.protocol.proto.Control.BluetoothPairingMethod
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** AAP pairing preparation and authentication, not a Bluetooth profile connection manager. */
@SuppressLint("MissingPermission")
internal class AapBluetoothControl(
    private val transport: AapTransport,
    settings: Settings,
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : AapControl {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val workerThread = lazy { HandlerThread("AAP-Bluetooth").apply { start() } }
    private val worker by workerThread
    private val handler by lazy { Handler(worker.looper) }
    private val queued = AtomicInteger(0)
    private val overflowLogged = AtomicBoolean(false)
    val announcement = BluetoothAnnouncePolicy.decide(settings.bluetoothAnnounceMode,
        settings.bluetoothAddress, com.andrerinas.openheadunit.App.provide(context).commManager.isUsbSession)
    // Disabled by default: merely replying SUCCESS advances AA's phone-side pairing/HFP FSM.
    // USB has a separate legacy phone-side unpair/re-pair recovery, so leave that path alone.
    private val enabled = settings.bluetoothPairingAssist &&
        com.andrerinas.openheadunit.App.provide(context).commManager.isWirelessSession &&
        !com.andrerinas.openheadunit.App.provide(context).commManager.isLoopbackSession
    private val carAddress = announcement.carAddress
    private val managerServiceName = settings.bluetoothManagerServiceName
    private val adapter: BluetoothAdapter? = if (enabled) resolveAdapter() else null
    private var registered = false
    private val session = BluetoothPairingSession(object : BluetoothPairingSession.Port {
        override fun bond(address: String) = readBond(address)
        override fun reply(status: MessageStatus, alreadyPaired: Boolean) {
            if (closed.get()) return
            AppLog.i("AAP Bluetooth pairing response: ${status.name}, alreadyPaired=$alreadyPaired")
            transport.send(AapMessage(Channel.ID_BTH, PAIRING_RESPONSE,
                Bluetooth.PairingResponse.newBuilder().setStatus(status.number)
                    .setAlreadyPaired(alreadyPaired).build()))
        }
        override fun authentication(value: String) {
            if (!closed.get()) transport.send(AapMessage(Channel.ID_BTH, AUTHENTICATION_DATA,
                Bluetooth.AuthenticationData.newBuilder().setAuthData(value).build()))
        }
        override fun now() = SystemClock.elapsedRealtime()
    })
    private val requests = BluetoothPairingRequests(
        now = { SystemClock.elapsedRealtime() },
        post = { task -> synchronized(lifecycleLock) { !closed.get() && handler.post { task() } } },
        consume = { request, current -> session.request(request.address, request.method, request.receivedAt, current) },
    )
    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (closed.get()) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            try {
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                        if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION) {
                            val address = device.address
                            val key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                            enqueueSystemEvent { session.comparison(address, key) }
                        }
                        // Never abort this protected system broadcast or confirm on the user's
                        // behalf. The head unit still displays Android's pairing confirmation.
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val current = bondValue(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) ?: return
                        val previous = bondValue(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1))
                        val address = device.address
                        enqueueSystemEvent { session.bondChanged(address, current, previous) }
                    }
                }
            } catch (_: SecurityException) {
                AppLog.w("AAP Bluetooth: permission revoked during pairing")
            }
        }
    }

    init {
        if (adapter != null) {
            try {
                // Bluetooth broadcasts may come from the privileged Bluetooth UID, not system.
                // These two actions are protected broadcasts; arbitrary apps cannot forge them.
                // Receive on Main, copy the event and return immediately. A blocked Binder query
                // on our worker must not delay the ordered broadcast reaching Android's pairing UI.
                ContextCompat.registerReceiver(this.context, receiver, IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }, null, null, ContextCompat.RECEIVER_EXPORTED)
                registered = true
            } catch (e: RuntimeException) {
                AppLog.w("AAP Bluetooth: pairing observer unavailable (${e.javaClass.simpleName})")
            }
        }
    }

    /** Kept in one instance with the request handler so discovery cannot promise another backend. */
    fun pairingMethods(): List<BluetoothPairingMethod> = if (registered) {
        // AA chooses PIN if both methods are offered. We can forward Android's numeric
        // comparison key, but cannot obtain a vendor module's private PIN from this adapter.
        listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON)
    } else {
        // Retain the existing announcement for external modules: deleting its methods makes
        // AA reject that service, including already-working independent HFP connections.
        // Retain historical no-response behavior too. Even an error response is a protocol
        // change; do not infer a module's bonds from Android's separate store.
        listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON,
            BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN)
    }

    override fun execute(message: AapMessage): Int {
        if (closed.get() || !registered) return 0
        when (val input = BluetoothPairingWire.decode(message)) {
            is BluetoothPairingWire.Input.Request -> requests.offer(input.address, input.method)
            is BluetoothPairingWire.Input.Result -> enqueue {
                if (!closed.get()) {
                    AppLog.i("AAP Bluetooth authentication result: ${input.status}")
                    session.authenticationResult(input.status)
                }
            }
            BluetoothPairingWire.Input.Malformed -> AppLog.w("AAP Bluetooth: malformed or oversized message ${message.type}")
            BluetoothPairingWire.Input.Unsupported -> AppLog.w("AAP Bluetooth: unsupported message ${message.type}")
        }
        return 0
    }

    override fun close() = synchronized(lifecycleLock) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        requests.close()
        if (registered) try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        if (workerThread.isInitialized()) {
            handler.removeCallbacksAndMessages(null)
            handler.post { session.close(); worker.quit() }
        }
    }

    private fun enqueueSystemEvent(action: () -> Unit) = synchronized(lifecycleLock) {
        // Protected platform events are not retryable AAP packets. In particular, a full
        // peer retry queue must not discard the one numeric challenge or final bond event.
        // These handlers only update session state/send data; they never query Bluetooth Binder.
        if (!closed.get()) handler.post { if (!closed.get()) action() }
    }

    private fun enqueue(action: () -> Unit) = synchronized(lifecycleLock) {
        // Bound peer authentication-result traffic independently of protected platform events.
        // Pairing requests have their own latest-retry slot, not this FIFO.
        if (closed.get()) return@synchronized
        if (queued.incrementAndGet() > 16) {
            queued.decrementAndGet()
            if (overflowLogged.compareAndSet(false, true)) AppLog.w("AAP Bluetooth: pairing queue full; dropping retry")
            return@synchronized
        }
        if (!handler.post {
                try { if (!closed.get()) action() }
                finally { queued.decrementAndGet() }
            }) queued.decrementAndGet()
    }

    private fun resolveAdapter(): BluetoothAdapter? {
        fun unavailable(reason: String): BluetoothAdapter? {
            if (carAddress != null) AppLog.i("AAP Bluetooth pairing backend unavailable: $reason")
            return null
        }
        return try {
            when {
                sdkInt < 19 -> return unavailable("requires Android 4.4 pairing broadcasts")
                carAddress == null -> return null
                carAddress == BluetoothAnnouncePolicy.SKIP_VALUE -> return unavailable("USB skip announcement")
                managerServiceName !in listOf("", "bluetooth_manager") -> return unavailable("custom Bluetooth manager")
                BluetoothHelper.externalBtEvidence != null || BluetoothHelper.fytModuleEvidence != null ->
                    return unavailable("external module has a separate bond store")
                sdkInt >= 31 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ->
                    return unavailable("Nearby devices permission not granted")
            }
            // BluetoothDevice.bondState and broadcasts use the default Android stack, even if
            // getBluetoothAdapter() could construct a custom vendor manager. Accept only default.
            val candidate = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return unavailable("no default adapter")
            val actual = BluetoothPairingSession.normalize(candidate.address)
                ?: return unavailable("adapter address is hidden or invalid")
            if (actual != BluetoothPairingSession.normalize(carAddress)) return unavailable("announced address differs from adapter")
            candidate
        } catch (_: SecurityException) { unavailable("Bluetooth permission denied") }
        catch (e: RuntimeException) { unavailable("adapter query failed: ${e.javaClass.simpleName}") }
    }

    private fun bondValue(value: Int): Bond? = when (value) {
        BluetoothDevice.BOND_NONE -> Bond.NONE
        BluetoothDevice.BOND_BONDING -> Bond.BONDING
        BluetoothDevice.BOND_BONDED -> Bond.BONDED
        else -> null
    }

    private fun readBond(address: String): Bond = try {
        if (!registered || adapter?.isEnabled != true) Bond.UNAVAILABLE
        else when (adapter.getRemoteDevice(address).bondState) {
            // getBondState() can return BOND_NONE on a Binder/service error. A negative
            // observation is not proof the phone should discard an existing link key.
            BluetoothDevice.BOND_NONE -> Bond.NONE
            BluetoothDevice.BOND_BONDING -> Bond.BONDING
            BluetoothDevice.BOND_BONDED -> Bond.BONDED
            else -> Bond.UNAVAILABLE
        }
    } catch (_: RuntimeException) { Bond.UNAVAILABLE }

    companion object {
        const val PAIRING_REQUEST = 0x8001
        const val PAIRING_RESPONSE = 0x8002
        const val AUTHENTICATION_DATA = 0x8003
        const val AUTHENTICATION_RESULT = 0x8004
    }
}
