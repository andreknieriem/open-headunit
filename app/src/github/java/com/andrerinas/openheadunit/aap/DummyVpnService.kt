package com.andrerinas.openheadunit.aap

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.andrerinas.openheadunit.utils.AppLog

/**
 * A VPN that carries no traffic. It exists only so `ConnectivityManager.activeNetwork` is non-null
 * on a head unit with no network, which is what offline Self Mode needs to hand Android Auto.
 *
 * It routes 0.0.0.0/0 into a descriptor nobody reads, so while it is up every app it applies to
 * has no IPv4. That is deliberate for Self Mode, where the unit is offline anyway.
 */
class DummyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    /** Which configuration [vpnInterface] was established with, or null when it is down. */
    private var establishedExcludingSelf: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpn(intent?.getBooleanExtra(EXTRA_EXCLUDE_SELF, false) ?: false)
        return START_NOT_STICKY
    }

    // Synchronized because the teardown no longer arrives only on the main thread: onRevoke() is a
    // binder callback, and closeRunningTun() runs on whichever thread asked for the stop.
    @Synchronized
    private fun startVpn(excludeSelf: Boolean) {
        // Idempotent for the same configuration, but a different one has to re-establish: the two
        // callers want opposite things from the route set.
        if (vpnInterface != null && establishedExcludingSelf == excludeSelf) return
        try {
            val builder = Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0) // Route all traffic to VPN to become the "active network"
                .setSession("Open Headunit Offline Mode")

            if (excludeSelf && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Load-bearing. A VPN captures its own app's sockets, and nothing drains this tun,
                // so without this every socket the app opens once it is up is dropped - including
                // the one a reconnect needs. A session established before establish() keeps its
                // fwmark and survives either way, which is why Self Mode works without it.
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    AppLog.w(
                        "DummyVpnService: could not exclude this app from the tun, so the " +
                            "projection link will be routed through it"
                    )
                }
            }

            val previous = vpnInterface
            // establish() replaces the active interface, so build the new one before closing the
            // old descriptor.
            vpnInterface = builder.establish()
            establishedExcludingSelf = excludeSelf
            previous?.close()
            AppLog.i("DummyVpnService: tun established (excludeSelf=$excludeSelf)")
        } catch (e: Exception) {
            AppLog.e("Failed to start Dummy VPN", e)
            stopSelf()
        }
    }

    @Synchronized
    private fun stopVpn() {
        try {
            // onDestroy() calls this after the descriptor has usually gone already, so say it only
            // when there was something to close. The old code logged "Dummy VPN stopped" twice
            // every time, which reads in a bug report like two tunnels came down.
            val open = vpnInterface != null
            vpnInterface?.close()
            vpnInterface = null
            establishedExcludingSelf = null
            stopSelf()
            if (open) AppLog.i("Dummy VPN stopped")
        } catch (e: Exception) {
            AppLog.e("Error closing Dummy VPN", e)
        }
    }

    /**
     * The user picked another VPN app, or withdrew consent. The framework has already taken the
     * tun down; this is what stops us believing it is still up.
     *
     * Without it [vpnInterface] stays non-null forever, and [startVpn]'s idempotence guard then
     * refuses to establish a new one for the rest of this process's life.
     */
    override fun onRevoke() {
        AppLog.i("DummyVpnService: consent was revoked, so the tun is down")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        stopVpn()
    }

    companion object {
        /** See [com.andrerinas.openheadunit.utils.VpnControl.startVpn]. */
        const val EXTRA_EXCLUDE_SELF = "exclude_self"

        /**
         * The running instance, or null when there is none.
         *
         * A started Service is normally told to stop with an Intent. This one cannot be: the
         * framework binds to a VpnService the moment establish() succeeds, so stopService() does
         * not destroy it, and a startService() carrying a stop action throws on O+ in exactly the
         * teardown window that needs it. See [com.andrerinas.openheadunit.utils.VpnControl.stopVpn].
         */
        @Volatile
        private var instance: DummyVpnService? = null

        /** Closes the tun on the running instance. False means there was nothing running. */
        fun closeRunningTun(): Boolean {
            val live = instance ?: return false
            live.stopVpn()
            return true
        }
    }
}
