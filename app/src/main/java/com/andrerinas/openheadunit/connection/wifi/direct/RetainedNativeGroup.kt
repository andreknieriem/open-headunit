package com.andrerinas.openheadunit.connection.wifi.direct

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.andrerinas.openheadunit.utils.AppLog

/** Process-owned cleanup, independent of AapService and its cancelled scope/handlers. */
internal object RetainedNativeGroup {
    private val cleanup = NativeGroupCleanupCoordinator()

    fun requestStop(isCurrent: () -> Boolean) {
        requireMain()
        cleanup.requestStop(isCurrent)
    }

    fun deferUntilReady(owner: Any, continuation: () -> Unit): Boolean {
        requireMain()
        return cleanup.deferUntilReady(owner, continuation)
    }

    fun isOwnerReady(owner: Any): Boolean {
        requireMain()
        return cleanup.isOwnerReady(owner)
    }

    fun isOwner(owner: Any): Boolean {
        requireMain()
        return cleanup.isOwner(owner)
    }

    fun activate(owner: Any): Boolean {
        requireMain()
        return cleanup.activate(owner)
    }

    fun cancelWaiting(owner: Any) {
        requireMain()
        cleanup.cancelWaiting(owner)
    }

    fun retain(owner: Any, manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        requireMain()
        cleanup.retain(owner, FrameworkRemoval(manager, channel))
    }

    fun removeGroup(
        owner: Any,
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.ActionListener,
    ) {
        requireMain()
        cleanup.remove(owner, FrameworkRemoval(manager, channel)) { failure ->
            if (failure == null) listener.onSuccess() else listener.onFailure(failure)
        }
    }

    private fun requireMain() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "P2P ownership must be ordered on Main" }
    }

    /** Deliberately no Context, WifiDirectManager, service callback or view reference. */
    private class FrameworkRemoval(
        private val manager: WifiP2pManager,
        private val channel: WifiP2pManager.Channel,
    ) : NativeGroupCleanupCoordinator.Removal {
        @SuppressLint("MissingPermission")
        override fun remove(onComplete: (Int?) -> Unit) {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    AppLog.d("WifiDirectManager: submitted group removal completed")
                    onComplete(null)
                }

                override fun onFailure(reason: Int) {
                    AppLog.d("WifiDirectManager: submitted group removal failed: $reason")
                    onComplete(reason)
                }
            })
        }
    }
}
