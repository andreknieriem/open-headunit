package com.andrerinas.openheadunit.aap

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.VpnControl

/**
 * The CarPlay tunnel: `fe80::2/64` for this app only, nothing else routed. It holds no rule; `VpnControl` is the only caller.
 * The VPN slot is single, so it never runs beside [DummyVpnService]. Callers get the descriptor and close it to take the tunnel down.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `network/CarPlayVpnService.kt`.
 */
class CarPlayTunService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = takeForStart()
        if (request == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            // A live tunnel belongs to its caller: a second start fails and leaves that descriptor open.
            if (liveTun() != null) {
                request.onFailed("a CarPlay tunnel is already up")
                return START_NOT_STICKY
            }
            // The dummy VPN may have been asked for after this start was queued, and the VPN slot is single.
            if (VpnControl.isDummyVpnUp()) {
                AppLog.i("CarPlayNative: the dummy VPN holds the VPN slot; tunnel not established")
                request.onFailed("the dummy VPN holds the VPN slot")
                stopSelf()
                return START_NOT_STICKY
            }
            val established = Builder()
                .addAddress(TUN_ADDRESS, PREFIX)
                .addRoute(TUN_ROUTE, PREFIX)
                .setSession("Open Headunit CarPlay")
                .setMtu(MTU)
                .setBlocking(true)
                // Without an app list every UID would route through this tunnel.
                .addAllowedApplication(packageName)
                // A VPN with no IPv4 address blocks IPv4 for the allowed app unless the family is allowed.
                .allowFamily(OsConstants.AF_INET)
                .establish() ?: throw IllegalStateException("establish returned null")
            tun = established
            AppLog.i("CarPlayNative: tunnel established ($TUN_ADDRESS/$PREFIX)")
            try { request.onReady(established) } catch (e: RuntimeException) {
                AppLog.w("CarPlayNative: tunnel ready callback failed (${e.javaClass.simpleName})")
                closeTun()
                stopSelf()
            }
        } catch (e: Exception) {
            failStart(request, e)
        } catch (e: LinkageError) {
            // A Builder method the platform lacks raises an Error, not an Exception.
            failStart(request, e)
        } finally {
            synchronized(lock) { establishing = false }
        }
        return START_NOT_STICKY
    }

    private fun failStart(request: Pending, e: Throwable) {
        AppLog.w("CarPlayNative: tunnel could not be established (${e.javaClass.simpleName}: ${e.message})")
        request.onFailed("${e.javaClass.simpleName}: ${e.message}")
        stopSelf()
    }

    /** The descriptor while it is open. A caller that closed it took the tunnel down. */
    private fun liveTun(): ParcelFileDescriptor? {
        val open = tun ?: return null
        if (runCatching { open.fd }.isSuccess) return open
        tun = null
        return null
    }

    /** The user took the slot for another VPN app, or withdrew consent. The framework has already taken the tunnel down. */
    override fun onRevoke() {
        AppLog.i("CarPlayNative: tunnel revoked by the system")
        closeTun()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        closeTun()
        super.onDestroy()
    }

    private fun closeTun() {
        val open = tun
        tun = null
        open?.let { runCatching { it.close() } }
    }

    private class Pending(val onReady: (ParcelFileDescriptor) -> Unit, val onFailed: (String) -> Unit)

    companion object {
        private const val TUN_ADDRESS = "fe80::2"
        private const val TUN_ROUTE = "fe80::"
        private const val PREFIX = 64
        private const val MTU = 1500
        private val lock = Any()
        private var pending: Pending? = null
        // True from the take of a request until its establish ends, so the slot counts as taken.
        private var establishing = false

        @Volatile
        private var instance: CarPlayTunService? = null

        private fun takePending(): Pending? = synchronized(lock) { pending.also { pending = null } }

        private fun takeForStart(): Pending? = synchronized(lock) { pending.also { pending = null; establishing = it != null } }

        /** True while a start is pending or establishing, or the tunnel is up: the VPN slot is then taken. */
        fun isRunning(): Boolean = synchronized(lock) { pending != null || establishing } || instance?.liveTun() != null

        /** Callbacks run on the main thread. A second start before the first finished fails the first. */
        fun start(context: Context, onReady: (ParcelFileDescriptor) -> Unit, onFailed: (String) -> Unit) {
            val replaced = synchronized(lock) { pending.also { pending = Pending(onReady, onFailed) } }
            replaced?.onFailed("replaced by a newer start")
            try {
                context.startService(Intent(context, CarPlayTunService::class.java))
            } catch (e: Exception) {
                takePending()?.onFailed("could not start the tunnel service (${e.javaClass.simpleName})")
            }
        }

        /** Closes the descriptor, which is the teardown: stopService alone leaves a bound VPN up. */
        fun stop(context: Context) {
            takePending()?.onFailed("stopped before it was established")
            instance?.closeTun()
            try { context.stopService(Intent(context, CarPlayTunService::class.java)) } catch (e: Exception) {
                AppLog.w("CarPlayNative: tunnel service stop failed (${e.javaClass.simpleName})")
            }
        }
    }
}
