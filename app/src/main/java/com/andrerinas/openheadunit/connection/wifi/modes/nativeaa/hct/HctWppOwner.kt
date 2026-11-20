package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory
import kotlinx.coroutines.*

/**
 * Service-owned HCT transport, independent of a Native launcher and its Wi-Fi credentials.
 * Only ordinary projection Exit can park a verified connection; service destruction releases
 * it. The next explicit same-phone selection creates a new projection lease on that channel.
 * A parked owner answers Ping but sends no Start or credentials by itself.
 */
@RequiresApi(21)
internal class HctWppOwner(
    private val service: AapService,
    private val notifications: CoroutineDispatcher = Dispatchers.Main
) {
    private val lock = Any()
    @Volatile private var client: Client? = null
    private var carrier: HctAaCarrier? = null
    private var carrierAdmission: ConnectionAdmission? = null
    private var channel: HctRetainedChannel? = null
    private var carrierJob: Job? = null
    // The projection that granted retention owns the parked resource. A later lease can
    // reuse the same physical channel, so channel identity alone cannot retire that lease.
    private var retainedProjection: Job? = null

    fun attach(
        openingRequest: () -> Wireless.WifiVersionRequest,
        serve: suspend (HandshakeLink) -> Unit,
        mayServe: () -> Boolean,
        acceptPeer: (String) -> Boolean,
        onPhoneEvidence: () -> Unit,
        onUnavailable: (HctWakePolicy.Result?) -> Unit,
        admission: ConnectionAdmission,
        maySelect: () -> Boolean = mayServe
    ): Client {
        val candidate = Client(openingRequest, serve, mayServe, acceptPeer, onPhoneEvidence, onUnavailable, admission, maySelect)
        if (!admission.run {}) return candidate.also { it.ended.complete(Unit) }
        val previous = synchronized(lock) { client to carrierAdmission }
        // A retained reader can survive ordinary projection Exit. Stop or a withdrawn Save
        // cannot lend that reader a fresh arm's authority. Check outside the owner monitor.
        val stale = (previous.first?.admission ?: previous.second)?.run {} == false
        var cleanup: (() -> Unit)? = null
        synchronized(lock) {
            if (stale && client === previous.first && carrierAdmission === previous.second) cleanup = detachCurrent()
            client?.close()
            client = candidate
        }
        cleanup?.invoke()
        return candidate
    }

    private fun start(requester: Client) {
        if (!requester.admission.run {}) return
        synchronized(lock) {
            if (client !== requester || carrier != null || service.isStopping) return
            lateinit var physical: HctAaCarrier
            physical = HctAaCarrier(service, {
                client?.openingRequest?.invoke() ?: throw java.io.IOException("HCT projection owner detached")
            },
                serve = { link ->
                    val owned = HctRetainedChannel(link, service.serviceScope)
                    val recipient = synchronized(lock) { if (carrier === physical) client else null }
                    if (recipient == null || !recipient.admission.run {}) { owned.close(); return@HctAaCarrier }
                    synchronized(lock) {
                        if (carrier !== physical || client !== recipient) { owned.close(); return@HctAaCarrier }
                        channel = owned
                        if (!launchProjection(recipient, owned)) owned.close()
                    }
                    try { owned.run() } finally {
                        synchronized(lock) { if (channel === owned) channel = null }
                    }
                },
                mayServe = { client?.mayServe?.invoke() == true },
                acceptPeer = { peer -> client?.acceptPeer?.invoke(peer) == true },
                onPhoneEvidence = { deliver(physical) { it.onPhoneEvidence() } },
                onUnavailable = { result, stillCurrent ->
                    deliver(physical) { if (stillCurrent()) it.onUnavailable(result) }
                }, admission = requester.admission)
            carrier = physical
            carrierAdmission = requester.admission
            carrierJob = service.serviceScope.launch(Dispatchers.IO) {
                try { physical.run() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { AppLog.w("NativeAA: [HCT] owner stopped: ${error.message}") }
                finally {
                    physical.close()
                    synchronized(lock) {
                        if (carrier === physical) {
                            carrier = null
                            carrierAdmission = null
                            channel?.close()
                            channel = null
                            client?.ended?.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    /** Retired native workers and already queued UI notifications cannot address a new client. */
    private fun deliver(physical: HctAaCarrier, action: (Client) -> Unit) {
        val recipient = synchronized(lock) { if (carrier === physical) client else null } ?: return
        service.serviceScope.launch(notifications) {
            if (!recipient.admission.run {}) return@launch
            synchronized(lock) {
                if (carrier === physical && client === recipient && !service.isStopping) action(recipient)
            }
        }
    }

    /** lock held; lease creation and publication precede any coroutine execution. */
    private fun launchProjection(requester: Client, owned: HctRetainedChannel): Boolean {
        if (client !== requester || !requester.mayServe() ||
            owned.peer?.let(requester.acceptPeer) != true) return false
        val lease = owned.lease() ?: return false
        return startProjection(requester, lease, requester.admission)
    }

    private fun startProjection(requester: Client, lease: HandshakeLink, admission: ConnectionAdmission): Boolean {
        retainedProjection = null
        requester.projection = service.serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try { if (requester.admission.run {} && admission.run {}) requester.serve(lease) } finally { lease.close() }
        }.also { it.start() }
        return true
    }

    /** Intentional Exit revokes delayed Start/Info work before the transport teardown awaits. */
    fun park(onlyCompleted: Boolean = false): Unit = synchronized(lock) {
        if (onlyCompleted && channel?.canRetain != true) return
        retainedProjection = if (channel?.canRetain == true) client?.projection ?: retainedProjection else null
        channel?.park()
        client?.projection?.cancel()
        client?.projection = null
    }

    /**
     * Settings and explicit wireless cancellation revoke an idle retained channel even after
     * Client.close detached its launcher. Ordinary projection Exit still keeps it for reuse;
     * opening settings during a live session must not disconnect that session.
     */
    fun releaseParked() {
        captureParkedRelease()?.invoke()
    }

    /** Capture outside the connection lifecycle lock; cleanup cannot address a later lease. */
    fun captureParkedRelease(): (() -> Unit)? {
        val (projection, ownedChannel, recipient) = synchronized(lock) {
            val projection = retainedProjection ?: return null
            val ownedChannel = channel?.takeIf { it.isParked } ?: return null
            Triple(projection, ownedChannel, client)
        }
        return {
            val cleanup = synchronized(lock) {
                if (retainedProjection !== projection || channel !== ownedChannel || client !== recipient || !ownedChannel.isParked) null
                else detachCurrent()
            }
            cleanup?.invoke()
        }
    }

    fun close() {
        val cleanup = synchronized(lock) { detachCurrent() }
        cleanup()
    }

    /** lock held; external closure and coroutine completion run only after detachment. */
    private fun detachCurrent(): () -> Unit {
        val detachedClient = client
        val detachedChannel = channel
        val detachedCarrier = carrier
        val detachedJob = carrierJob
        client = null
        retainedProjection = null
        channel = null
        carrier = null
        carrierAdmission = null
        carrierJob = null
        return {
            detachedClient?.ended?.complete(Unit)
            detachedClient?.projection?.cancel()
            detachedChannel?.close()
            detachedCarrier?.close()
            detachedJob?.cancel()
        }
    }

    inner class Client internal constructor(
        internal val openingRequest: () -> Wireless.WifiVersionRequest,
        internal val serve: suspend (HandshakeLink) -> Unit,
        internal val mayServe: () -> Boolean,
        internal val acceptPeer: (String) -> Boolean,
        internal val onPhoneEvidence: () -> Unit,
        internal val onUnavailable: (HctWakePolicy.Result?) -> Unit,
        internal val admission: ConnectionAdmission,
        internal val maySelect: () -> Boolean
    ) : ExternalModuleCarrier {
        internal val ended = CompletableDeferred<Unit>()
        internal var projection: Job? = null
        override val sendsWake = false
        override fun requestWake(userAsked: Boolean, admission: ConnectionAdmission) {
            if (!this.admission.run {} || !admission.run {}) return
            AppLog.i("NativeAA: [HCT] waiting for a fresh handoff or explicit reuse of the owned channel")
        }
        suspend fun run() {
            if (!admission.run {}) { ended.complete(Unit); return }
            start(this)
            ended.await()
        }

        fun requestWakeFor(mac: String, allowReplacePending: Boolean = false, admission: ConnectionAdmission, selected: () -> Unit = {}): Boolean {
            val target = BluetoothPeerInventory.normalizeAddress(mac) ?: return false
            if (!this.admission.run {} || !admission.run {}) return false
            synchronized(lock) {
                if (client !== this || service.isStopping) return false
                val owned = channel
                if (owned?.isOpen == true) {
                    // A different peer or a live attempt cannot inherit this channel. Do not
                    // turn a failed reuse into a second HFP reconnect or shared-socket attach.
                    if (!owned.isParked) return false
                    if (owned.peer == target) {
                        // Explicit selection may clear a picker/cancel/backoff hold, but it
                        // cannot replace a running projection or an in-flight handshake.
                        if (!maySelect()) return false
                        val lease = owned.lease(selected) ?: return false
                        return startProjection(this, lease, admission)
                    }
                    // A different explicit phone choice releases our old consumer before a
                    // new carrier observes its own fresh AA event. This is not socket reclaim.
                    retainedProjection = null
                    owned.close()
                    channel = null
                    carrier?.close()
                    carrier = null
                    carrierAdmission = null
                    carrierJob?.cancel()
                    carrierJob = null
                }
            }
            // Selection can run before Client.run reaches IO. Prepare its carrier outside
            // the owner monitor so admission never takes the connection lock in reverse.
            start(this)
            val physical = synchronized(lock) { if (client === this) carrier else null }
            return physical?.requestWakeFor(target, allowReplacePending, admission, selected) ?: false
        }

        override fun close(): Unit = synchronized(lock) {
            ended.complete(Unit)
            projection?.cancel()
            projection = null
            if (client !== this) return
            client = null
            if (!service.isStopping && retainedProjection != null &&
                !service.wirelessPausedForSettings && channel?.canRetain == true) {
                channel?.park()
                AppLog.i("NativeAA: [HCT] retaining the owned WPP channel after projection Exit")
            } else {
                channel?.close()
                channel = null
                carrier?.close()
                carrier = null
                carrierAdmission = null
                carrierJob?.cancel()
                carrierJob = null
            }
        }
    }
}
