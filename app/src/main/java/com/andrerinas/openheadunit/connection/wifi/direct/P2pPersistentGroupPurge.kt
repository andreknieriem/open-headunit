package com.andrerinas.openheadunit.connection.wifi.direct

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import com.andrerinas.openheadunit.utils.AppLog
import java.lang.reflect.Proxy

/**
 * Deletes this unit's own stored P2P group profiles, the only way to rename the group below API 29.
 *
 * The two-argument createGroup reinvokes whatever profile the platform kept, so a fresh name and
 * passphrase are minted only once no profile of ours is left. Both calls are hidden API, reached by
 * reflection, and refused from Android 11 where the app names the group itself instead.
 */
object P2pPersistentGroupPurge {

    /** A listener that never answers must not hold the create up; the group matters more. */
    private const val ANSWER_TIMEOUT_MS = 3000L

    private const val LISTENER_CLASS = "android.net.wifi.p2p.WifiP2pManager\$PersistentGroupInfoListener"

    /**
     * Deletes every stored profile this unit owns, then reports a short verdict for the log.
     * [onDone] runs exactly once while [isCurrent] remains true; a stopped owner gets no continuation.
     */
    @SuppressLint("MissingPermission")
    fun purge(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        ownAddress: String?,
        handler: Handler,
        isCurrent: () -> Boolean,
        onDone: (verdict: String) -> Unit,
    ) {
        val attempt = Attempt(handler, isCurrent, onDone)

        try {
            val listenerClass = Class.forName(LISTENER_CLASS)
            val request = manager.javaClass.getMethod(
                "requestPersistentGroupInfo",
                WifiP2pManager.Channel::class.java,
                listenerClass,
            )
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "onPersistentGroupInfoAvailable" -> {
                        if (attempt.acceptInfo()) {
                            deleteOurs(manager, channel, ownAddress, args?.getOrNull(0), attempt)
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "P2pPersistentGroupPurge"
                    else -> null
                }
            }
            handler.postDelayed(attempt.timeout, ANSWER_TIMEOUT_MS)
            if (attempt.isActive()) request.invoke(manager, channel, listener)
        } catch (t: Throwable) {
            attempt.finish("unavailable (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    private class Attempt(
        private val handler: Handler,
        private val isCurrent: () -> Boolean,
        private val onDone: (String) -> Unit,
    ) {
        val lock = Any()
        var finished = false
            private set
        private var infoReceived = false
        val timeout = Runnable { finish("unavailable (no answer in ${ANSWER_TIMEOUT_MS / 1000}s)") }

        fun isActive(): Boolean = isCurrent() && synchronized(lock) { !finished }

        fun acceptInfo(): Boolean {
            if (!isCurrent()) return false
            return synchronized(lock) {
                if (finished || infoReceived) false else {
                    infoReceived = true
                    true
                }
            }
        }

        fun finish(verdict: String) {
            synchronized(lock) {
                if (finished) return
                finished = true
            }
            // Platform calls and the continuation may reenter; never invoke them under our lock.
            handler.removeCallbacks(timeout)
            if (isCurrent()) onDone(verdict)
        }
    }

    /** Never a profile we did not own: a phone's own saved group is not ours to forget. */
    @SuppressLint("MissingPermission")
    private fun deleteOurs(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        ownAddress: String?,
        groupList: Any?,
        attempt: Attempt,
    ) {
        var unknownIds = 0
        val ours = try {
            val list = groupList?.javaClass?.getMethod("getGroupList")?.invoke(groupList) as? Collection<*>
            val ids = ArrayList<Int>()
            for (group in list.orEmpty()) {
                if (group !is WifiP2pGroup || !isOurs(group, ownAddress)) continue
                // Each deletion needs this owned profile's real ID; zero is a valid profile.
                // An unreadable entry must not suppress cleanup of other identifiable profiles.
                val netId = WifiDirectCompat.getGroupNetworkId(group)
                when {
                    netId == null -> unknownIds++
                    P2pGroupIdentityPolicy.isPersistentNetworkId(netId) -> ids.add(netId)
                }
            }
            ids
        } catch (t: Throwable) {
            attempt.finish("unavailable (${t.javaClass.simpleName}: ${t.message})")
            return
        }
        if (ours.isEmpty()) {
            attempt.finish(if (unknownIds > 0) "unavailable (network id missing for $unknownIds owned profiles)"
                else "no profile of ours")
            return
        }

        val delete = try {
            manager.javaClass.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
        } catch (t: Throwable) {
            attempt.finish("unavailable (${t.javaClass.simpleName}: ${t.message})")
            return
        }

        var answered = 0
        var purged = 0
        var refusal: String? = null
        for (netId in ours) {
            // Stop/timeout retires the remaining batch, not a platform call already admitted.
            // The call stays outside the lock: a platform may wait for a cross-thread callback.
            if (!attempt.isActive()) return
            var completed = false
            val report = report@{ failure: String? ->
                val verdict = synchronized(attempt.lock) {
                    // A callback followed by another callback (or a throw) is still one answer.
                    if (attempt.finished || completed) return@report
                    completed = true
                    answered++
                    if (failure == null) purged++ else if (refusal == null) refusal = failure
                    if (answered == ours.size) {
                        val result = if (purged > 0) "purged $purged of ${ours.size}"
                            else "refused (${refusal ?: "unknown"})"
                        result + if (unknownIds > 0) "; network id unavailable for $unknownIds owned profiles" else ""
                    } else null
                }
                if (verdict != null) attempt.finish(verdict)
            }
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() = report(null)

                override fun onFailure(reason: Int) = report(reasonName(reason))
            }
            try {
                delete.invoke(manager, channel, netId, listener)
            } catch (t: Throwable) {
                AppLog.d("P2pPersistentGroupPurge: deletePersistentGroup(netId=$netId) threw: ${t.message}")
                report(t.javaClass.simpleName)
            }
        }
    }

    private fun isOurs(group: WifiP2pGroup, ownAddress: String?): Boolean {
        if (group.isGroupOwner) return true
        val owner = group.owner?.deviceAddress ?: return false
        return ownAddress != null && owner.equals(ownAddress, ignoreCase = true)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        else -> "reason $reason"
    }
}
