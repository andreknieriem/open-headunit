package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes

/**
 * The Play Store build has no dummy VPN, and this is the seam that says so.
 *
 * Google does not allow the fake-VPN trick that offline Self Mode uses to give Android Auto a
 * non-null `activeNetwork` (README, v2.1.1). So this flavor ships no `DummyVpnService`, no
 * `BIND_VPN_SERVICE` component, and no `VpnService.prepare` call: [isVpnAvailable] answers false
 * and every caller in `main` checks it before doing anything else.
 *
 * Keep this file's signatures identical to the github copy at
 * `app/src/github/java/com/andrerinas/openheadunit/utils/VpnControl.kt`. It is the only reason
 * `main` can reference the VPN at all.
 */
object VpnControl {
    fun startVpn(context: Context, excludeSelf: Boolean = false) {
        AppLog.i("VpnControl: no dummy VPN in the Play Store build; ignoring the request")
    }

    fun stopVpn(context: Context) = Unit

    fun consentIntent(context: Context): Intent? = null

    fun isPrepared(context: Context): Boolean = false

    fun isVpnAvailable(): Boolean = false

    /**
     * No copy, because the strings live in the github flavor's resources and this build has none.
     * Nothing reads these: every caller checks [isVpnAvailable] first.
     */
    @StringRes
    val toggleNameRes: Int = 0
    @StringRes
    val toggleDescriptionRes: Int = 0
    @StringRes
    val consentDeniedRes: Int = 0
}
