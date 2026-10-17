package com.andrerinas.openheadunit.connection.wifi.scan

/** Main-thread gate: the helper may die with adbd, so rollback must finish before shutdown. */
internal class ScanControlAdbClose {
    enum class Result { CLOSED, SCAN_RECOVERY, ADB_RECOVERY, BUSY }
    var active = false
        private set

    suspend fun run(restore: suspend () -> Boolean, close: suspend () -> Boolean,
                    finished: () -> Unit): Result {
        if (active) return Result.BUSY
        active = true
        return try {
            if (!restore()) Result.SCAN_RECOVERY
            else if (close()) Result.CLOSED else Result.ADB_RECOVERY
        } finally {
            active = false
            finished()
        }
    }
}
