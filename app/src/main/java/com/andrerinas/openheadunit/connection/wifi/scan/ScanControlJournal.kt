package com.andrerinas.openheadunit.connection.wifi.scan

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Device-local rollback data must not travel through backup, settings export or factory reset. */
internal class ScanControlJournal(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wifi-scan-lease.json"))
    data class Record(val mode: Int, val original: Int, val boot: Int, val id: String = java.util.UUID.randomUUID().toString())
    fun read(): Record? {
        if (!file.baseFile.exists()) return null
        val obj = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        // Device-local storage never migrates to another device. An OS update must
        // not discard scan-always rollback, since that setting survives an update.
        return Record(obj.getInt("mode"), obj.getInt("original"), obj.getInt("boot"), obj.getString("id")).also {
            check(it.mode in 1..2 && it.original in 0..1 && it.id.isNotBlank())
        }
    }
    fun write(record: Record) {
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("mode", record.mode)
                .put("original", record.original).put("boot", record.boot).put("id", record.id).toString().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = file.delete()
    fun exists() = file.baseFile.exists()
}
