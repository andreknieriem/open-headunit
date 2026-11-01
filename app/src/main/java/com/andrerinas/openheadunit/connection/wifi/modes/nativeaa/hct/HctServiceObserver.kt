package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerDiscovery
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Observes the running HCT system service without starting its module.
 *
 * BTServiceInf getter IDs below match the inspected AVIN A10 and related HCT A13 ABI.
 * Transaction 1 is init(), not a capability probe. Do not call it or scan transaction IDs.
 * Broadcasts are accepted only from holders of SERIAL_PORT; cached Binder UUIDs are used
 * to revalidate a fresh event, never to manufacture an AA-ready event.
 */
internal class HctServiceObserver(
    context: Context,
    private val onAaReport: () -> Unit,
    private val onInvalidated: () -> Unit,
    private val effectAdmission: ConnectionAdmission,
    private val interfaceAvailable: (Context) -> Boolean = Companion::available
) : Closeable {
    private val context = context.applicationContext
    val admission = HctAdmission()
    val arrivals = Channel<HctAdmission.Ticket>(Channel.CONFLATED)
    private val connected = CompletableDeferred<Unit>()
    private val lifecycle = Any()
    private val statusCalls = Mutex()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binder: IBinder? = null
    @Volatile private var closed = false
    private var receiverNeedsCleanup = false
    private var bindingNeedsCleanup = false
    @Volatile private var address: String? = null
    @Volatile private var reportedPeer: String? = null
    @Volatile private var addressAck: CompletableDeferred<Unit>? = null

    // Each report is partial: absent fields are not zero and are not evidence of disconnect.
    // HFP and AA are separate firmware state machines. Keep the actual fields together with
    // their report age rather than merging an old HFP state into a newer peer/UUID report.
    @Volatile private var lastReport = "no manufacturer report"
    @Volatile private var lastReportAt: Long? = null

    fun observationSummary(): String {
        val age = lastReportAt?.let { SystemClock.elapsedRealtime() - it }
        return "lastReportAgeMs=${age ?: "none"}; $lastReport"
    }

    private fun recordReport(intent: Intent) {
        fun field(key: String) = if (intent.hasExtra(key)) intent.getIntExtra(key, -1).toString() else "missing"
        val aa = if (intent.hasExtra("phone_uuids"))
            (intent.getStringArrayListExtra("phone_uuids")?.contains(HctAdmission.AA_MARKER) == true).toString()
            else "missing"
        val report = "hfp=${field("connect_state")}, spp=${field("spp_state")}, aa=$aa, " +
            "phoneField=${intent.hasExtra("phone_mac")}, connectedField=${intent.hasExtra("connected_mac")}, " +
            "standalone=${intent.extras?.keySet() == setOf("phone_uuids")}"
        if (report != lastReport) AppLog.i("NativeAA: [HCT] manufacturer report: $report")
        lastReport = report
        lastReportAt = SystemClock.elapsedRealtime()
    }

    private fun invalidate() = synchronized(lifecycle) {
        admission.invalidate()
        address = null
        reportedPeer = null
        addressAck?.cancel()
        addressAck = null
        onInvalidated()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lifecycle) {
                if (closed) return
                if (name != COMPONENT) {
                    connected.completeExceptionally(IOException("HCT: unsupported Bluetooth status interface"))
                    invalidate()
                    return
                }
                if (binder != null && binder !== service) invalidate()
                binder = service
                connected.complete(Unit)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lifecycle) {
                if (closed) return
                binder = null
                connected.completeExceptionally(IOException("HCT: Bluetooth status service disconnected"))
                invalidate()
            }
        }

        override fun onBindingDied(name: ComponentName) {
            bindingUnavailable("HCT: Bluetooth status binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            bindingUnavailable("HCT: Bluetooth status service returned no interface")
        }

        private fun bindingUnavailable(message: String) {
            synchronized(lifecycle) {
                if (closed) return
                binder = null
                val failure = IOException(message)
                connected.completeExceptionally(failure)
                invalidate()
                // These callbacks cannot recover through this binding. End the carrier's
                // report loop even if awaitService already completed, allowing a fresh bind
                // on the next user attempt. Ordinary disconnection may reconnect in place.
                arrivals.close(failure)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(lifecycle) {
                if (closed || intent.action != REPORT) return
                runCatching {
                    if (listOf("connect_state", "spp_state", "phone_mac", "connected_mac", "phone_uuids")
                            .any(intent::hasExtra)) admission.noteStatusReport()
                    recordReport(intent)
                    if (intent.hasExtra("phone_mac")) {
                        val current = HctAdmission.address(intent.getLongExtra("phone_mac", 0))
                        val previous = reportedPeer
                        if (current == null || (previous != null && previous != current)) invalidate()
                        reportedPeer = current
                    }
                    val peer = address
                    if (peer != null &&
                        ((intent.hasExtra("phone_mac") &&
                            HctAdmission.address(intent.getLongExtra("phone_mac", 0)) != peer) ||
                         (intent.hasExtra("connected_mac") &&
                            !HctAdmission.matchesAddress(intent.getStringExtra("connected_mac"), peer)))) {
                        invalidate()
                        return
                    }
                    val uuids = if (intent.hasExtra("phone_uuids")) {
                        intent.getStringArrayListExtra("phone_uuids") ?: emptyList()
                    } else null
                    val state = if (intent.hasExtra("connect_state")) intent.getIntExtra("connect_state", -1) else null
                    val spp = if (intent.hasExtra("spp_state")) intent.getIntExtra("spp_state", -1) else null
                    if ((state != null && state !in 1..5) || (spp != null && spp != 2) ||
                        (uuids != null && HctAdmission.AA_MARKER !in uuids)) {
                        invalidate()
                        return
                    }
                    val standalone = intent.extras?.keySet() == setOf("phone_uuids")
                    admission.report(uuids, state, spp, SystemClock.elapsedRealtime(), standalone)?.let {
                        // Serialize fresh-event publication with final wake admission. Invalidate
                        // an unsent selection before the carrier starts its asynchronous Binder reads.
                        onAaReport()
                        AppLog.i("NativeAA: [HCT] fresh AA report admitted")
                        arrivals.trySend(it)
                    }
                    // SPC2 is also emitted for iAP. It is only an address-consumption hint; the
                    // carrier must separately require a valid WPP frame and matching Binder peer.
                    if (spp == 2) addressAck?.complete(Unit)
                }.onFailure { invalidate() }
            }
        }
    }

    /** Registration and binding share main-thread ownership; no BIND_AUTO_CREATE side effects. */
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "HCT observer must start on main" }
        if (!effectAdmission.run {}) throw CancellationException("HCT connection authority withdrawn before binding")
        synchronized(lifecycle) {
            // A stop can win after the carrier's main-dispatch check but before this lock.
            // Report cancellation rather than a manufacturer-interface failure in that race.
            if (closed) throw CancellationException("HCT observer closed before start")
            check(!receiverNeedsCleanup && !bindingNeedsCleanup)
            if (!interfaceAvailable(context)) throw IOException("HCT: manufacturer interface is unavailable")
            // Related HCT builds select different FSC handlers with these properties. Record
            // them for diagnosis only; neither value proves a command ABI or reconnect outcome.
            AppLog.i("NativeAA: [HCT] interface: ${interfaceSummary(context)}; module hints: gocType=" +
                com.andrerinas.openheadunit.utils.SystemProperties.get("bt.goc.type", "unavailable") +
                ", bluewareSoc=" + com.andrerinas.openheadunit.utils.SystemProperties.get("vendor.blueware.soc.type", "unavailable") +
                ", wirelessAa=" + com.andrerinas.openheadunit.utils.SystemProperties.get("rw.support.wireless.androidauto", "unavailable"))
            try {
                // ReceiverDispatcher is also registered locally before the system call.
                // A registration exception still requires best-effort unregister below.
                receiverNeedsCleanup = true
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(REPORT), SENDER_PERMISSION, null,
                    ContextCompat.RECEIVER_EXPORTED
                )
                // ContextImpl registers a ServiceDispatcher before asking the system to bind
                // (checked in AOSP Android 5, 13 and 16). Even false or SecurityException can
                // leave that registration behind; Context.bindService documents unbind on false.
                // Track the attempt, not acceptance, and retain the same ServiceConnection.
                bindingNeedsCleanup = true
                val accepted = context.bindService(Intent().setComponent(COMPONENT), connection, 0)
                if (!accepted) throw IOException("HCT: manufacturer Bluetooth service is not running")
            } catch (error: Exception) {
                close()
                throw error
            }
        }
    }

    suspend fun awaitService() = connected.await()

    private fun requireWorker() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "HCT Binder reads must not block main" }
    }

    private fun <T> read(
        gate: HctCallGate, remote: IBinder, code: Int, minimumBytes: Int = 4, decode: (Parcel) -> T
    ): T {
        requireWorker()
        if (!effectAdmission.run {}) throw IOException("HCT connection authority withdrawn before status read")
        gate.checkActive()
        if (closed || binder !== remote) throw IOException("HCT: Bluetooth status service changed")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!remote.transact(code, data, reply, 0)) throw IOException("HCT: unsupported status getter")
            gate.checkActive()
            if (closed || binder !== remote) throw IOException("HCT: Bluetooth status service changed")
            if (reply.dataAvail() < 4) throw IOException("HCT: empty status reply")
            reply.readException()
            if (reply.dataAvail() < minimumBytes) throw IOException("HCT: truncated status reply")
            val value = decode(reply)
            if (reply.dataAvail() != 0) throw IOException("HCT: unexpected status reply layout")
            return value
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun remote(gate: HctCallGate): IBinder {
        if (!effectAdmission.run {}) throw IOException("HCT connection authority withdrawn before Binder access")
        gate.checkActive()
        if (closed) throw IOException("HCT: observer is closed")
        val remote = binder ?: throw IOException("HCT: Bluetooth status service is not connected")
        // interfaceDescriptor is itself a synchronous Binder transaction. Validate it in
        // the bounded worker, never from ServiceConnection's main-thread callback.
        if (remote.interfaceDescriptor != DESCRIPTOR) {
            throw IOException("HCT: unsupported Bluetooth status interface")
        }
        gate.checkActive()
        return remote
    }

    private fun snapshot(gate: HctCallGate, remote: IBinder, stateLast: Boolean = false): HctWakePolicy.Snapshot {
        // Bracket a wake decision with HFP state reads: the second state is read after its
        // address, so a peer/status update during pairing lookup cannot be silently reused.
        return if (stateLast) {
            val peer = read(gate, remote, 11, 8) { it.readLong() }
            HctWakePolicy.Snapshot(read(gate, remote, 2) { it.readByte().toInt() }, peer)
        } else {
            HctWakePolicy.Snapshot(read(gate, remote, 2) { it.readByte().toInt() }, read(gate, remote, 11, 8) { it.readLong() })
        }
    }

    private fun readUuids(gate: HctCallGate, remote: IBinder): List<String> = read(gate, remote, 13) { reply ->
        val size = reply.readInt()
        if (size == -1) return@read emptyList()
        if (size !in 0..64) throw IOException("HCT: unsupported UUID list")
        List(size) {
            if (reply.dataAvail() < 4) throw IOException("HCT: truncated UUID list")
            val value = reply.readString() ?: throw IOException("HCT: invalid UUID entry")
            if (value.length > 128) throw IOException("HCT: oversized UUID entry")
            value
        }
    }

    /** A positive address and HFP state must remain stable across the cached UUID read. */
    suspend fun phone(ticket: HctAdmission.Ticket): String = statusCalls.withLock {
        HctBinderCalls.call { gate ->
            if (!admission.current(ticket)) throw IOException("HCT: AA report was withdrawn")
            val remote = remote(gate)
            val before = snapshot(gate, remote)
            val mac = HctAdmission.address(before.peer) ?: throw IOException("HCT: invalid phone address")
            if (before.hfp !in 1..5) throw IOException("HCT: phone disconnected")
            val uuids = readUuids(gate, remote)
            val after = snapshot(gate, remote, stateLast = true)
            AppLog.i("NativeAA: [HCT] handoff status: hfp=${before.hfp}->${after.hfp}, " +
                "peerStable=${before.peer == after.peer}, cachedAa=${HctAdmission.AA_MARKER in uuids}, " +
                "eventCurrent=${admission.current(ticket)}")
            if (HctAdmission.AA_MARKER !in uuids || after.peer != before.peer ||
                after.hfp !in 1..5 || (reportedPeer != null && reportedPeer != mac) ||
                !admission.current(ticket)) {
                throw IOException("HCT: AA peer changed during status read")
            }
            mac
        }
    }

    fun watchAddress(mac: String) = synchronized(lifecycle) {
        check(!closed)
        val normalized = BluetoothPeerInventory.normalizeAddress(mac)
            ?: throw IOException("HCT: invalid watched address")
        val previous = address
        if (previous != null && previous != normalized) invalidate()
        address = normalized
    }

    fun expectAddressAck(): CompletableDeferred<Unit> = synchronized(lifecycle) {
        check(!closed)
        addressAck?.cancel()
        CompletableDeferred<Unit>().also { addressAck = it }
    }

    /**
     * User-directed wake only. A new HFP connection can trigger the phone's AA SDP discovery.
     * AT-CC disconnects an existing HFP connection, even to the same phone. An explicit
     * selection may allow one such reconnect while HFP is idle; calls and unknown/changing
     * states refuse it. Sending the request does not mean AA has connected.
     */
    suspend fun requestWake(
        targetMac: String?,
        allowReconnect: Boolean = false,
        commandAdmission: ConnectionAdmission,
        mayWake: () -> HctWakePolicy.Result = { HctWakePolicy.Result.REQUESTED }
    ): HctWakePolicy.Result {
        if (!effectAdmission.run {} || !commandAdmission.run {}) return HctWakePolicy.Result.STATE_CHANGED
        if (closed) return HctWakePolicy.Result.CLOSED
        val target = targetMac?.let { BluetoothPeerInventory.normalizeAddress(it) }
            ?: return HctWakePolicy.Result.NO_TARGET
        if (binder == null) return HctWakePolicy.Result.SERVICE_UNAVAILABLE
        val submitted = AtomicBoolean(false)
        return try {
            statusCalls.withLock {
                HctBinderCalls.call { gate ->
                    val epoch = admission.epoch()
                    val remote = remote(gate)
                    val before = snapshot(gate, remote)
                    val paired = BluetoothPeerDiscovery.manufacturerInventory(context).manufacturerPairing(target)
                    val after = snapshot(gate, remote, stateLast = true)
                    val result = HctWakePolicy.decide(target, paired, before, after, epoch == admission.epoch(), allowReconnect)
                    AppLog.i("NativeAA: [HCT] wake status: hfp=${before.hfp}->${after.hfp}, " +
                        "peerStable=${before.peer == after.peer}, targetIsPeer=${HctAdmission.address(after.peer) == target}, " +
                        "decision=$result")
                    if (result != HctWakePolicy.Result.REQUESTED) return@call result
                    if (!effectAdmission.run {} || !commandAdmission.run {}) return@call HctWakePolicy.Result.STATE_CHANGED
                    synchronized(lifecycle) {
                        if (closed) return@call HctWakePolicy.Result.CLOSED
                        if (epoch != admission.epoch() || binder !== remote) return@call HctWakePolicy.Result.STATE_CHANGED
                        var localDecision = HctWakePolicy.Result.STATE_CHANGED
                        if (!gate.claim {
                            localDecision = mayWake()
                            (localDecision == HctWakePolicy.Result.REQUESTED).also {
                                if (it) submitted.set(true)
                            }
                        }) return@call localDecision
                    }
                    // Close/cancel before admission prevents this request. Once admitted, IPC
                    // cannot be recalled; keep it outside lifecycle so close never waits on it.
                    if (!effectAdmission.run {} || !commandAdmission.run {}) return@call HctWakePolicy.Result.STATE_CHANGED
                    AppLog.i("NativeAA: [HCT] submitting one settingsaccess=5 HFP reconnect")
                    context.sendBroadcast(Intent(SETTINGS_ACCESS).setPackage(PACKAGE)
                        .putExtra("accesstype", 5).putExtra("accesscommand", target.replace(":", "")))
                    HctWakePolicy.Result.REQUESTED
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w("NativeAA: [HCT] wake result uncertain: ${error.message}")
            // Submission is not an OEM completion ACK. Keep this stable even if a
            // matching WPP has already reconciled the process-wide pending record.
            if (submitted.get()) HctWakePolicy.Result.SUBMISSION_UNCONFIRMED
            else HctWakePolicy.Result.UNKNOWN_STATE
        }
    }

    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            binder = null
            connected.cancel()
            invalidate()
            arrivals.close()
        }
        val cleanup = Runnable {
            synchronized(lifecycle) {
                if (receiverNeedsCleanup) {
                    receiverNeedsCleanup = false
                    runCatching { context.unregisterReceiver(receiver) }
                }
                if (bindingNeedsCleanup) {
                    bindingNeedsCleanup = false
                    // Some implementations fail before registration. Their unbind can throw;
                    // cleanup must not mask the original bind failure or be attempted twice.
                    runCatching { context.unbindService(connection) }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else main.post(cleanup)
    }

    companion object {
        private const val PACKAGE = "android.microntek.mtcser"
        private val COMPONENT = ComponentName(PACKAGE, "$PACKAGE.BlueToothService")
        private const val DESCRIPTOR = "$PACKAGE.BTServiceInf"
        private const val REPORT = "com.microntek.bt.report"
        private const val SETTINGS_ACCESS = "com.microntek.settingsaccess"
        private const val SENDER_PERMISSION = "android.permission.SERIAL_PORT"

        /** Metadata only: opening the shared native socket as a probe can evict another client. */
        fun available(context: Context): Boolean = Build.VERSION.SDK_INT >= 21 && runCatching {
            @Suppress("DEPRECATION")
            val service = context.packageManager.getServiceInfo(COMPONENT, 0)
            val method = BluetoothSocket::class.java.getMethod("connectRfcom")
            method.returnType == Void.TYPE && canBind(context, service)
        }.getOrDefault(false)

        internal fun canBind(context: Context, service: ServiceInfo): Boolean =
            service.enabled && service.exported && service.applicationInfo.enabled &&
                service.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                context.packageManager.checkPermission(SENDER_PERMISSION, PACKAGE) == PackageManager.PERMISSION_GRANTED &&
                // SERIAL_PORT authenticates the OEM broadcast sender. A service's own bind
                // permission is a separate requirement on OHU's UID. This preflight cannot
                // replace handling SecurityException if the service changes before bind.
                (service.permission.isNullOrEmpty() ||
                    context.checkPermission(service.permission, android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED)

        private fun interfaceSummary(context: Context): String = runCatching {
            @Suppress("DEPRECATION")
            val service = context.packageManager.getServiceInfo(COMPONENT, 0)
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo(PACKAGE, 0).versionName
            "sdk=${Build.VERSION.SDK_INT}, serviceVersion=$version, " +
                "serviceTargetSdk=${service.applicationInfo.targetSdkVersion}, bindPermission=${service.permission ?: "none"}"
        }.getOrElse { "sdk=${Build.VERSION.SDK_INT}, serviceMetadata=unavailable" }
    }
}
