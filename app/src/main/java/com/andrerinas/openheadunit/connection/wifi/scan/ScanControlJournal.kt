package com.andrerinas.openheadunit.connection.wifi.scan

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Device-local rollback data must not travel through backup, settings export or factory reset. */
internal class ScanControlJournal(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wifi-scan-lease.json"))
    data class Record(val mode: Int, val original: Int, val boot: Int, val id: String = java.util.UUID.randomUUID().toString())
    // AtomicFile handles interrupted writes; this monitor also excludes UI reads during IO writes.
    @Synchronized fun read(): Record? {
        if (!file.baseFile.exists()) return null
        val obj = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        // Device-local storage never migrates to another device. An OS update must
        // not discard scan-always rollback, since that setting survives an update.
        return Record(obj.getInt("mode"), obj.getInt("original"), obj.getInt("boot"), obj.getString("id")).also {
            check(ScanControlPolicy.validSnapshot(it.mode, it.original) && it.id.isNotBlank())
        }
    }
    @Synchronized fun discardExpiredRuntime(currentBoot: Int) {
        val record = read() ?: return
        if (record.boot >= 0 && currentBoot >= 0 &&
            ScanControlPolicy.discardAfterBoot(record.mode, record.boot, currentBoot)) clear()
    }
    @Synchronized fun write(record: Record) {
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("mode", record.mode)
                .put("original", record.original).put("boot", record.boot).put("id", record.id).toString().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun clear() = file.delete()
    @Synchronized fun exists() = file.baseFile.exists()
}
