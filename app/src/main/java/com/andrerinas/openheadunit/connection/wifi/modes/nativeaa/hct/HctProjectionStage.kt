package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A retained WPP channel can replace a projection before the cancelled projection's
 * UI cleanup runs. Publish the lease token and its stage on the same dispatcher as
 * cleanup: a CAS on IO followed by a Main stage update would leave a gap between them.
 * The owning launcher still decides whether any live GAL/association work prevents reset.
 */
internal class HctProjectionStage(
    private val started: () -> Unit,
    private val ended: () -> Unit,
    private val ui: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    // Accessed only on ui, including the callbacks; no suspension inside either update.
    private var current: Any? = null

    suspend fun serve(block: suspend () -> Unit) {
        val lease = Any()
        try {
            withContext(ui) {
                current = lease
                started()
            }
            block()
        } finally {
            // Also covers cancellation after publication but before returning to the IO caller.
            withContext(NonCancellable + ui) {
                if (current === lease) {
                    current = null
                    ended()
                }
            }
        }
    }
}
