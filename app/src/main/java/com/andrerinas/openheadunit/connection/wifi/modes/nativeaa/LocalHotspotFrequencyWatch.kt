package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.utils.AppLog
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * The frequency of our own local-only hotspot, by `registerLocalOnlyHotspotSoftApCallback`. It is a
 * system API, open to an app with NEARBY_WIFI_DEVICES from API 33, so it is called by reflection and
 * a refusal only means that nothing is read.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LocalHotspotFrequencyWatch(private val wifi: WifiManager, private val executor: Executor) {

    private var callback: Any? = null

    /** [onFrequencies] runs on the executor for every change; an empty list means that no hotspot is up. */
    @SuppressLint("PrivateApi")
    fun start(onFrequencies: (List<Int>) -> Unit) {
        try {
            val type = Class.forName(CALLBACK)
            val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                when (method.name) {
                    "equals" -> self === args?.get(0)
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "LocalHotspotFrequencyWatch"
                    "onInfoChanged" -> {
                        // The single-value form of the callback repeats this one.
                        (args?.get(0) as? List<*>)?.let { onFrequencies(frequencies(it)) }
                        null
                    }
                    else -> null
                }
            }
            // Set first: the platform keeps the callback in a map before it asks the service, a refusal included.
            callback = proxy
            wifi.javaClass.getMethod("registerLocalOnlyHotspotSoftApCallback", Executor::class.java, type)
                .invoke(wifi, executor, proxy)
        } catch (e: Exception) {
            AppLog.i(LocalHotspotPolicy.noLiveBandLine(causeOf(e)))
            stop()
        }
    }

    /** Safe to call twice, and after a refused start. */
    @SuppressLint("PrivateApi")
    fun stop() {
        val held = callback ?: return
        callback = null
        try {
            wifi.javaClass.getMethod("unregisterLocalOnlyHotspotSoftApCallback", Class.forName(CALLBACK)).invoke(wifi, held)
        } catch (e: Exception) {
            AppLog.d("LocalHotspotFrequencyWatch: the unregister was refused: ${causeOf(e)}")
        }
    }

    companion object {
        private const val CALLBACK = "android.net.wifi.WifiManager\$SoftApCallback"

        /** `SoftApInfo.getFrequency` of each entry; one that cannot be read is left out. */
        fun frequencies(infos: List<*>): List<Int> = infos.mapNotNull { info ->
            try {
                info?.javaClass?.getMethod("getFrequency")?.invoke(info) as? Int
            } catch (e: Exception) {
                null
            }
        }

        private fun causeOf(e: Exception): String =
            ((e as? InvocationTargetException)?.targetException ?: e).javaClass.simpleName
    }
}
