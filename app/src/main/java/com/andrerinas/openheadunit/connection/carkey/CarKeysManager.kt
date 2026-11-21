package com.andrerinas.openheadunit.connection.carkey

import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog

class CarKeysManager {

    private val all: Array<CarKeyReceiver> = CarKeyReceiver.newDefaultReceivers()
    private val registered: MutableSet<CarKeyReceiver> = mutableSetOf()

    private var isSessionActive = false
    @Volatile private var isLearningActive = false

    val isLearning: Boolean get() = isLearningActive

    @Volatile private var assignDialogOpen = false
    val isAssignDialogOpen: Boolean get() = assignDialogOpen

    /** Tells the service to reach, or release, the media button route. */
    var onLearnStateChanged: (() -> Unit)? = null

    /**
     * Registers baseline (non-session-scoped) receivers like CarKeyBroadcastReceiver and CarBydReceiver.
     * Safe to run throughout AapService lifecycle because they do not hijack system focus.
     */
    fun registerIdleReceivers(context: Context) {
        try {
            for (r in all) {
                if (!r.isSupported || r.isSessionScoped)
                    continue

                if (!registered.contains(r)) {
                    r.register(context)
                    registered.add(r)
                }
            }
            AppLog.d("CarKeysManager: ${registered.size} idle CarKeyReceivers registered")
        } catch (e: Exception) {
            AppLog.e("CarKeysManager: Failed to register idle CarKeyReceivers", e)
        }
    }

    /**
     * Called when projection session connects. Registers session-scoped receivers (e.g. CarFYTReceiver).
     */
    fun onSessionStarted(context: Context) {
        isSessionActive = true
        registerSessionReceivers(context)
    }

    /**
     * Called when projection session disconnects. Unregisters session-scoped receivers unless key learning is active.
     */
    fun onSessionEnded() {
        isSessionActive = false
        if (!isLearningActive) {
            unregisterSessionReceivers()
        }
    }

    /**
     * Called by KeymapFragment onResume to allow learning keys from session-scoped receivers (like FYT)
     * while in the settings screen.
     */
    fun startLearning(context: Context) {
        isLearningActive = true
        registerIdleReceivers(context)
        registerSessionReceivers(context)
        onLearnStateChanged?.invoke()
    }

    /**
     * Called by KeymapFragment onPause to release session-scoped receivers when leaving the key learning screen.
     */
    fun stopLearning() {
        isLearningActive = false
        if (!isSessionActive) {
            unregisterSessionReceivers()
        }
        onLearnStateChanged?.invoke()
    }

    fun setAssignDialogOpen(open: Boolean) {
        assignDialogOpen = open
        onLearnStateChanged?.invoke()
    }

    private fun registerSessionReceivers(context: Context) {
        try {
            for (r in all) {
                if (!r.isSupported || !r.isSessionScoped)
                    continue

                if (!registered.contains(r)) {
                    r.register(context)
                    registered.add(r)
                    AppLog.i("CarKeysManager: Registered session-scoped receiver ${r.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            AppLog.e("CarKeysManager: Failed to register session-scoped CarKeyReceivers", e)
        }
    }

    private fun unregisterSessionReceivers() {
        val toRemove = registered.filter { it.isSessionScoped }
        for (r in toRemove) {
            try {
                r.unregister()
                AppLog.i("CarKeysManager: Unregistered session-scoped receiver ${r.javaClass.simpleName}")
            } catch (e: Exception) {
                AppLog.e("CarKeysManager: Failed to unregister session-scoped receiver ${r.javaClass.simpleName}", e)
            }
            registered.remove(r)
        }
    }

    /**
     * Full teardown on AapService destroy.
     */
    fun unregisterAll() {
        isSessionActive = false
        try {
            for (r in registered) {
                try {
                    r.unregister()
                } catch (e: Exception) {
                    AppLog.e("CarKeysManager: Failed to unregister ${r.javaClass.simpleName}", e)
                }
            }
        } finally {
            registered.clear()
        }
    }

    /** Backward compatibility alias */
    fun registerReceivers(context: Context) {
        registerIdleReceivers(context)
    }

    /** Backward compatibility alias */
    fun unregisterReceivers() {
        unregisterAll()
    }

    fun isSUNeeded(): Boolean {
        return registered.any { it.isSUNeeded }
    }
}
