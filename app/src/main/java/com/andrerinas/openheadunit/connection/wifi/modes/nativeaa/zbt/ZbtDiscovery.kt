package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.BluetoothHelper
import kotlinx.coroutines.delay
import java.io.File

/** Keeps vendor discovery and its cache choice inside the shared ZBT transport. */
object ZbtDiscovery {
    internal const val CARRIER_WAIT_MS = 10_000L
    // A busy carrier is not a failed identity probe. Keep the fallback only for this arming,
    // shared by routing and UI, without writing a negative result into the ten-minute cache.
    @Volatile private var deferredIdentity: ZbtDiscoveryPolicy.Decision? = null

    internal fun deferIdentityForArming(decision: ZbtDiscoveryPolicy.Decision) {
        require(decision.requiresIdentity)
        deferredIdentity = decision
    }

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
        deferredIdentity = null
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
        val waitsForCarrier: Boolean get() = carrierOwnsSlot()
        // A yielded observer has no measurement. Legacy discovery must wait too, rather
        // than re-arm immediately with the same unknown cache and start another measurement.
        val settled: Boolean get() = cached() != null
        val fallsBackToAndroid: Boolean get() = decision.allowsAndroidFallback

        fun cached(): Boolean? = if (decision.available) {
            ZbtDaemonReachability.cached(requireIdentity = requireIdentity)
        } else null

        fun snapshot(): ZbtDiscoveryPolicy.Snapshot = ZbtDiscoveryPolicy.Snapshot(
            decision, if (deferredIdentity == decision) false else cached()
        )

        internal enum class Completion { SETTLED, CARRIER_BUSY, CANCELLED }

        /**
         * A retiring carrier may keep its single-client slot through a read or reopen backoff.
         * Bound the polling across ownership races; never probe through that carrier or record
         * its ownership as a negative measurement. A carrier claim without a cached answer is
         * not completion: the manager would otherwise re-arm and immediately ask again. The
         * caller handles a busy result according to the route, after checking this arming is
         * current. An actual socket probe retains its own bounded deadline/refusal window.
         */
        internal suspend fun awaitResolution(
            keepTrying: () -> Boolean,
            nowMs: () -> Long = { SystemClock.elapsedRealtime() },
            pause: suspend (Long) -> Unit = { delay(it) },
            probe: () -> Unit = { resolve(retryRefusals = true, keepTrying = keepTrying); Unit },
        ): Completion {
            val startedAt = nowMs()
            while (keepTrying()) {
                if (nowMs() - startedAt >= CARRIER_WAIT_MS) {
                    return Completion.CARRIER_BUSY
                }
                if (!waitsForCarrier) {
                    probe()
                    if (!keepTrying()) return Completion.CANCELLED
                    if (settled) return Completion.SETTLED
                }
                val remaining = CARRIER_WAIT_MS - (nowMs() - startedAt)
                pause(minOf(250L, remaining.coerceAtLeast(0L)))
            }
            return Completion.CANCELLED
        }

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
