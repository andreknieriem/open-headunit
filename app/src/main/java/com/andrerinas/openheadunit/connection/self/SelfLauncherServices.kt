package com.andrerinas.openheadunit.connection.self

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Built only by the launch it serves, which hands over its own acceptance mark.
class SelfLauncherServices internal constructor(
    val aap: AapService,
    val wifiLauncherManager: WifiLauncherManager,
    val settingsRestart: CommManager.ConnectionState.Disconnected? = null,
    val operationTicket: Long,
    // The launch's arrival mark; see SelfLauncherManager.isLaunchPending.
    private val arrivalMark: CommManager.LaunchMark,
) {
    val connectivityManager by lazy { aap.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    val fakeNetwork by lazy { createFakeNetwork(0) }
    val fakeWifiInfo by lazy { createFakeWifiInfo() }


    /**
     * Whether a physical connection arrived since this launch was accepted, even one that has
     * already ended: unlike the current state, an Error published since cannot hide it.
     */
    internal fun arrivedSinceLaunch(): Boolean {
        return App.provide(aap).commManager.arrivedSince(arrivalMark)
    }

    /** A suspension may let USB or another socket replace the Save that requested this launch. */
    internal suspend fun ensureLaunchAllowed() {
        currentCoroutineContext().ensureActive()
        if (!App.provide(aap).commManager.acceptsAppConnectionTicket(operationTicket)) {
            throw CancellationException("Self launch cancelled by Stop")
        }
        if (settingsRestart != null &&
            !settingsRestart.acceptsSettingsRestart(App.provide(aap).commManager.connectionState.value)) {
            // Cancellation ends the whole launch chain; false would run the next fallback.
            throw CancellationException("Settings Self launch superseded")
        }
    }

    /** Reflectively constructs an `android.net.Network` from a raw network ID integer. */
    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (e: Exception) { null } finally { parcel.recycle() }
    }

    /** Reflectively constructs a `WifiInfo` with a fake SSID for the Self Mode intent. */
    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID")
                    .apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (e: Exception) {}
            wifiInfo
        } catch (e: Exception) { null }
    }
}
