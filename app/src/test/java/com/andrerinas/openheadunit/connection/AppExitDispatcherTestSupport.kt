package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Ordinary policy fixtures execute inline; scheduling regressions supply a queued dispatcher. */
internal fun CommManager.withAppExitDispatcherForTest(
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): CommManager = apply {
    CommManager::class.java.getDeclaredField("appExitDispatcher")
        .apply { isAccessible = true }.set(this, dispatcher)
    CommManager::class.java.getDeclaredField("connectionIoDispatcher")
        .apply { isAccessible = true }.set(this, Dispatchers.Unconfined)
    val manager = this
    for ((name, initial) in listOf("transportLifecycleLock" to Any(), "appExitPolicy" to AppExitPolicy())) {
        CommManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            if (get(manager) == null) set(manager, initial)
        }
    }
    // Real Exit now persists USB suppression before teardown, even without a live connection.
    CommManager::class.java.getDeclaredField("context").apply {
        isAccessible = true
        if (get(manager) == null) set(manager,
            org.mockito.Mockito.mock(android.content.Context::class.java).withUsbInputPreferencesForTest())
    }
}
