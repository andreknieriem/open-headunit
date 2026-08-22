package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.annotation.StringRes
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.DummyVpnService

/**
 * The whole dummy-VPN surface, in one object, because it is the flavor seam.
 *
 * `main` must never touch [VpnService] or [DummyVpnService] directly: the fake VPN cannot ship in
 * the Play Store build, so the playstore copy of this file answers no to everything and carries no
 * VPN component at all. Every caller in `main` asks [isVpnAvailable] first.
 */
object VpnControl {
    /**
     * @param excludeSelf leaves this app out of the tun's route set. An Android VPN captures its
     * own app's sockets too, and this one routes 0.0.0.0/0 into a descriptor nobody reads, so any
     * socket opened after it comes up is dropped. Self Mode must pass `false`: its whole purpose
     * is that `activeNetwork` is non-null *for our own process*.
     */
    fun startVpn(context: Context, excludeSelf: Boolean = false) {
        AppLog.i("VpnControl: Starting DummyVpnService (GitHub Build, excludeSelf=$excludeSelf)")
        try {
            context.startService(
                Intent(context, DummyVpnService::class.java)
                    .putExtra(DummyVpnService.EXTRA_EXCLUDE_SELF, excludeSelf)
            )
        } catch (e: Exception) {
            // Starting a non-foreground service from the background throws on O+. Every caller
            // runs from a foreground Service or an Activity, so this should not happen - say which
            // exception it was rather than swallowing it, because the tun silently not coming up
            // looks identical to the VPN having no effect.
            AppLog.w("VpnControl: Failed to start VPN (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    fun stopVpn(context: Context) {
        AppLog.i("VpnControl: Stopping DummyVpnService (GitHub Build)")
        try {
            // Closing the descriptor is the teardown, and no Intent can ask for it. The framework
            // binds to a VpnService as soon as establish() succeeds, so stopService() clears only
            // the started flag: the service stays alive, onDestroy() never runs, and the tun stays
            // up. Measured on an Android 14 unit - tun0 held UP across two teardowns that both
            // logged as if they had worked, and only killing the process took it down. A
            // startService() carrying a stop action is not the answer either, because the stop
            // often runs as the app is losing foreground, where that throws on O+.
            val closed = DummyVpnService.closeRunningTun()
            // Belt and braces for a service that was started but never established, where there is
            // no instance to close and only the started flag to clear.
            val wasStarted = context.stopService(Intent(context, DummyVpnService::class.java))
            if (!closed && !wasStarted) AppLog.i("VpnControl: DummyVpnService was not running")
        } catch (e: Exception) {
            AppLog.w("VpnControl: Failed to stop VPN (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /**
     * The consent Intent to launch, or null when this app is already the prepared VPN app.
     *
     * Wraps `VpnService.prepare`, which needs an Activity the first time and returns null forever
     * afterwards.
     */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * Whether this app is already the prepared VPN app, so a Service can bring the tun up with no
     * Activity. False also means consent was withdrawn or another VPN app took the slot.
     */
    fun isPrepared(context: Context): Boolean = VpnService.prepare(context) == null

    fun isVpnAvailable(): Boolean = true

    /**
     * The settings toggle's copy, which lives in `app/src/github/res/values/strings.xml`.
     *
     * `main` cannot say `R.string.keep_dummy_vpn`: those strings do not exist in the playstore
     * flavor, where this whole feature is absent. Reaching them through here keeps the toggle's
     * code in `main` while keeping its words out of the Play Store build. The playstore copy
     * answers 0, which nothing ever renders because [isVpnAvailable] is false there.
     */
    @StringRes
    val toggleNameRes: Int = R.string.keep_dummy_vpn
    @StringRes
    val toggleDescriptionRes: Int = R.string.keep_dummy_vpn_description
    @StringRes
    val consentDeniedRes: Int = R.string.keep_dummy_vpn_denied
}
