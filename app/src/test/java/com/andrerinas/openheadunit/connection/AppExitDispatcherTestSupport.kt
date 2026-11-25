package com.andrerinas.openheadunit.connection

import android.content.Context
import com.andrerinas.openheadunit.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Ordinary policy fixtures execute inline; scheduling regressions supply a queued dispatcher. */
internal fun CommManager.withAppExitDispatcherForTest(
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): CommManager = apply {
    CommManager::class.java.getDeclaredField("appExitDispatcher")
        .apply { isAccessible = true }.set(this, dispatcher)
    CommManager::class.java.getDeclaredField("connectionIoDispatcher")
        .apply { isAccessible = true }.set(this, Dispatchers.Unconfined)
    val manager = this
    for ((name, initial) in listOf("transportLifecycleLock" to Any(), "appExitPolicy" to AppExitPolicy(),
            "appExitScreens" to LinkedHashSet<(Long) -> Unit>())) {
        CommManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            if (get(manager) == null) set(manager, initial)
        }
    }
    // CALLS_REAL_METHODS bypasses the constructor, but Stop still sends a package-scoped broadcast.
    CommManager::class.java.getDeclaredField("context").apply {
        isAccessible = true
        if (get(manager) == null) {
            val context = mock<Context>()
            whenever(context.packageName).thenReturn(BuildConfig.APPLICATION_ID)
            set(manager, context)
        }
    }
}
