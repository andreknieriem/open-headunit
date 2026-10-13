package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.content.ComponentName
import android.content.pm.PackageManager
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.BluetoothHelper
import java.io.File

/** Keeps vendor discovery and its cache choice inside the shared ZBT transport. */
object ZbtDiscovery {
    private val latch = ZbtDiscoveryPolicy.Latch {
        ZbtDiscoveryPolicy.detect(
            externalModuleEvidence = { BluetoothHelper.externalBtEvidence },
            nodeExists = { path -> try { File(path).exists() } catch (_: SecurityException) { false } },
            hctServiceAvailable = {
                // The manifest declares this exact package. Inspection does not start the
                // vendor service or call its control Binder, and needs no privileged permission.
                try {
                    val component = ComponentName(
                        "android.microntek.mtcser", "android.microntek.mtcser.BlueToothService"
                    )
                    @Suppress("DEPRECATION")
                    val info = App.instance?.packageManager?.getServiceInfo(component, 0)
                    info != null && info.exported && info.enabled && info.applicationInfo.enabled
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        )
    }

    /** Call once for a fresh arming, not when its asynchronous measurement resumes. */
    fun beginArming() {
        latch.reconsiderForArming(carrierOwnsSlot())
        ZbtDaemonReachability.expireIdleIdentity()
    }

    /** Reads metadata and the selected cache; never opens a socket on a UI path. */
    fun snapshot(): ZbtDiscoveryPolicy.Snapshot = measurement(latch.decision()).snapshot()

    /** Capture the cache/probe choice once, including across suspension or carrier handover. */
    fun measurement(decision: ZbtDiscoveryPolicy.Decision): Measurement = Measurement(decision)

    private fun carrierOwnsSlot(): Boolean =
        ZbtDaemonReachability.carrierLive() || ZbtDaemonReachability.carrierWantsClient()

    class Measurement internal constructor(val decision: ZbtDiscoveryPolicy.Decision) {
        private val requireIdentity: Boolean get() = decision.requiresIdentity
        val waitsForCarrier: Boolean get() = requireIdentity && carrierOwnsSlot()
        val settled: Boolean get() = !requireIdentity || cached() != null
        val fallsBackToAndroid: Boolean get() = decision.allowsAndroidFallback

        fun cached(): Boolean? = if (decision.available) {
            ZbtDaemonReachability.cached(requireIdentity = requireIdentity)
        } else null

        fun snapshot(): ZbtDiscoveryPolicy.Snapshot = ZbtDiscoveryPolicy.Snapshot(decision, cached())

        /** Blocking socket work: call off the main thread. Settings previews do not cache refusals. */
        fun resolve(
            retryRefusals: Boolean = false,
            cacheIdentityRefusal: Boolean = true,
            keepTrying: () -> Boolean = { true }
        ): Boolean = decision.available && ZbtDaemonReachability.resolve(
            requireIdentity = requireIdentity,
            retryRefusals = retryRefusals,
            cacheIdentityRefusal = cacheIdentityRefusal,
            keepTrying = keepTrying
        )
    }
}
