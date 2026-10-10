package com.andrerinas.openheadunit.connection.wifi.scan

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Only the temporary service property is owned; an existing or persistent ADB port is not. */
internal object FytAdbPortPolicy {
    fun valid(value: String?) = value == "" || value == "-1" || value == "0" ||
        value?.toIntOrNull()?.let { it in 1..65535 } == true
    fun port(value: String?) = value?.toIntOrNull()?.takeIf { it in 1..65535 }
    fun existing(current: String, persistent: String): Int? {
        require(valid(current) && valid(persistent))
        // AOSP adbd consults persist.* only when service.* is empty, not when it is -1.
        return port(if (current.isEmpty()) persistent else current)
    }
    fun shouldRestore(current: String, previous: String, ownedPort: Int, ownedListenerOpen: Boolean) =
        current == ownedPort.toString() ||
            ((current == previous || (current.isEmpty() && previous == "-1")) && ownedListenerOpen)
}

internal class FytAdbPortJournal(context: Context) {
    // Settings reads this journal while startup writes it from an IO coroutine. AtomicFile
    // protects crash recovery, not concurrent access; all instances share this lock.
    private companion object { val lock = Any() }
    private val file = AtomicFile(File(context.noBackupFilesDir, "fyt-adb-port.json"))
    // Missing flags identify records from older builds that promised automatic rollback.
    data class Record(val previous: String, val boot: Int, val daemon: String, val port: Int,
                      val keepOpen: Boolean = false, val stopRequested: Boolean = false) {
        fun needsRecovery(currentBoot: Int) = boot == currentBoot && !keepOpen
        fun canClose(currentBoot: Int) = boot == currentBoot
    }
    fun exists() = synchronized(lock) { file.baseFile.exists() }
    fun read(): Record? = synchronized(lock) {
        if (!exists()) return@synchronized null
        val obj = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        Record(obj.getString("previous"), obj.getInt("boot"), obj.getString("daemon"), obj.getInt("port"),
            obj.optBoolean("keepOpen", false), obj.optBoolean("stopRequested", false)).also {
            check(it.previous == "-1" || it.previous == "0")
            check(it.boot >= 0 && it.port in 1024..65535)
            check(it.daemon == "running" || it.daemon == "stopped")
        }
    }
    fun write(record: Record) = synchronized(lock) {
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("previous", record.previous).put("boot", record.boot).put("daemon", record.daemon).put("port", record.port)
                .put("keepOpen", record.keepOpen).put("stopRequested", record.stopRequested)
                .toString().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = synchronized(lock) { file.delete() }
}
