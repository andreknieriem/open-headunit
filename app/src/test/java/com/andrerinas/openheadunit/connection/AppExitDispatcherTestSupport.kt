package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Ordinary policy fixtures execute inline; scheduling regressions supply a queued dispatcher. */
internal fun CommManager.withAppExitDispatcherForTest(
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): CommManager = apply {
    CommManager::class.java.getDeclaredField("appExitDispatcher")
        .apply { isAccessible = true }.set(this, dispatcher)
}
