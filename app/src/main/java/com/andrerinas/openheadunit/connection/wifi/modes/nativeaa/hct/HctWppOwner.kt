package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
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
    private var channel: HctRetainedChannel? = null
    private var carrierJob: Job? = null
    private var retainedAfterExit = false

    fun attach(
        openingRequest: () -> Wireless.WifiVersionRequest,
        serve: suspend (HandshakeLink) -> Unit,
        mayServe: () -> Boolean,
        acceptPeer: (String) -> Boolean,
        onPhoneEvidence: () -> Unit,
        onUnavailable: (HctWakePolicy.Result?) -> Unit,
        maySelect: () -> Boolean = mayServe
    ): Client = synchronized(lock) {
        client?.close()
        Client(openingRequest, serve, mayServe, acceptPeer, onPhoneEvidence, onUnavailable, maySelect)
            .also { client = it }
    }

    private fun start(requester: Client): Unit = synchronized(lock) {
        if (client !== requester || carrier != null || service.isStopping) return
        lateinit var physical: HctAaCarrier
        physical = HctAaCarrier(service, {
            client?.openingRequest?.invoke() ?: throw java.io.IOException("HCT projection owner detached")
        },
            serve = { link ->
                val owned = HctRetainedChannel(link, service.serviceScope)
                synchronized(lock) {
                    if (carrier !== physical || client == null) { owned.close(); return@HctAaCarrier }
                    channel = owned
                    if (client?.let { launchProjection(it, owned) } != true) owned.close()
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
            })
        carrier = physical
        carrierJob = service.serviceScope.launch(Dispatchers.IO) {
            try { physical.run() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { AppLog.w("NativeAA: [HCT] owner stopped: ${error.message}") }
            finally {
                physical.close()
                synchronized(lock) {
                    if (carrier === physical) {
                        carrier = null
                        channel?.close()
                        channel = null
                        client?.ended?.complete(Unit)
                    }
                }
            }
        }
    }

    /** Retired native workers and already queued UI notifications cannot address a new client. */
    private fun deliver(physical: HctAaCarrier, action: (Client) -> Unit) {
        val recipient = synchronized(lock) { if (carrier === physical) client else null } ?: return
        service.serviceScope.launch(notifications) {
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
        return startProjection(requester, lease)
    }

    private fun startProjection(requester: Client, lease: HandshakeLink): Boolean {
        retainedAfterExit = false
        requester.projection = service.serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try { requester.serve(lease) } finally { lease.close() }
        }.also { it.start() }
        return true
    }

    /** Intentional Exit revokes delayed Start/Info work before the transport teardown awaits. */
    fun park(onlyCompleted: Boolean = false): Unit = synchronized(lock) {
        if (onlyCompleted && channel?.canRetain != true) return
        retainedAfterExit = channel?.canRetain == true
        channel?.park()
        client?.projection?.cancel()
        client?.projection = null
    }

    /**
     * Settings and explicit wireless cancellation revoke an idle retained channel even after
     * Client.close detached its launcher. Ordinary projection Exit still keeps it for reuse;
     * opening settings during a live session must not disconnect that session.
     */
    fun releaseParked(): Unit = synchronized(lock) {
        if (retainedAfterExit && channel?.isParked == true) close()
    }

    fun close(): Unit = synchronized(lock) {
        client?.ended?.complete(Unit)
        client?.projection?.cancel()
        client = null
        retainedAfterExit = false
        channel?.close()
        channel = null
        carrier?.close()
        carrier = null
        carrierJob?.cancel()
        carrierJob = null
    }

    inner class Client internal constructor(
        internal val openingRequest: () -> Wireless.WifiVersionRequest,
        internal val serve: suspend (HandshakeLink) -> Unit,
        internal val mayServe: () -> Boolean,
        internal val acceptPeer: (String) -> Boolean,
        internal val onPhoneEvidence: () -> Unit,
        internal val onUnavailable: (HctWakePolicy.Result?) -> Unit,
        internal val maySelect: () -> Boolean
    ) : ExternalModuleCarrier {
        internal val ended = CompletableDeferred<Unit>()
        internal var projection: Job? = null
        override val sendsWake = false
        override fun requestWake(userAsked: Boolean) {
            AppLog.i("NativeAA: [HCT] waiting for a fresh handoff or explicit reuse of the owned channel")
        }
        suspend fun run() { start(this); ended.await() }

        fun requestWakeFor(mac: String, allowReplacePending: Boolean = false, selected: () -> Unit = {}): Boolean {
            val target = BluetoothPeerInventory.normalizeAddress(mac) ?: return false
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
                        return startProjection(this, lease)
                    }
                    // A different explicit phone choice releases our old consumer before a
                    // new carrier observes its own fresh AA event. This is not socket reclaim.
                    retainedAfterExit = false
                    owned.close()
                    channel = null
                    carrier?.close()
                    carrier = null
                    carrierJob?.cancel()
                    carrierJob = null
                    start(this)
                }
                // Selection can run on Main before Client.run is dispatched on IO. Prepare
                // the carrier/queue here as well; its observer still starts asynchronously.
                start(this)
                return carrier?.requestWakeFor(target, allowReplacePending, selected) ?: false
            }
        }

        override fun close(): Unit = synchronized(lock) {
            ended.complete(Unit)
            projection?.cancel()
            projection = null
            if (client !== this) return
            client = null
            if (!service.isStopping && retainedAfterExit &&
                !service.wirelessPausedForSettings && channel?.canRetain == true) {
                channel?.park()
                AppLog.i("NativeAA: [HCT] retaining the owned WPP channel after projection Exit")
            } else {
                channel?.close()
                channel = null
                carrier?.close()
                carrier = null
                carrierJob?.cancel()
                carrierJob = null
            }
        }
    }
}
