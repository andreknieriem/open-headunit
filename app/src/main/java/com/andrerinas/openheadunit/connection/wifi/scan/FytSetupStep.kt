package com.andrerinas.openheadunit.connection.wifi.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retain the failing operation even if cleanup subsequently starts a different operation. */
internal class FytSetupStep(private val report: (String) -> Unit) {
    enum class Stage { PREPARE, VENDOR_SERVICE, OPEN_ADB, CONNECT_ADB, START_SHIZUKU,
        WAIT_SHIZUKU, RESTORE_ADB }
    class Failure(val stage: Stage, cause: Exception) : java.io.IOException("${stage.name}: ${cause.message}", cause)

    suspend fun <T> run(stage: Stage, block: suspend () -> T): T {
        report("${stage.name}: begin")
        try {
            return block().also { report("${stage.name}: complete") }
        } catch (e: Exception) {
            if (e is CancellationException) {
                // A child withTimeout expiring is an operation failure, not the user leaving
                // this screen. Parent cancellation must still abort setup.
                if (e !is TimeoutCancellationException) throw e
                currentCoroutineContext().ensureActive()
            }
            if (e is Failure) throw e
            report("${stage.name}: failed (${e.javaClass.simpleName}: ${e.message})")
            throw Failure(stage, e)
        }
    }
}
