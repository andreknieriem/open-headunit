package com.andrerinas.openheadunit.connection.bluetooth.xyauto

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** XYAuto's existing A2DP policy, without binding its UI service or touching the UART. */
internal object XyAutoMediaSession {
    private const val PACKAGE = "com.xygala.bluetooth"
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "XYAutoMediaPolicy").apply { isDaemon = true }
    }
    private data class Request(val owner: Any?, val session: Any?)
    private val requestLock = Any()
    @Volatile private var requested = Request(null, null)
    private var owner: Any? = null
    private var lease: XyAutoMediaLease? = null

    @Suppress("DEPRECATION")
    fun supported(context: Context): Boolean = try {
        val info = context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SERVICES)
        // Match the XYAuto service family, not a particular CHQbt version: the reporter and
        // analyzed SM4250 firmware have different builds. This exposes an opt-in experiment,
        // not a claim that every blink daemon has the inspected policy. Writes still need
        // return-code/readback confirmation and never bypass device permissions.
        Build.VERSION.SDK_INT >= 24 && File("/system/bin/blink").exists() &&
            (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0 &&
            info.services?.any { it.name == "$PACKAGE.service.blinkService" && it.enabled } == true
    } catch (_: RuntimeException) { false }
    catch (_: PackageManager.NameNotFoundException) { false }

    fun open(context: Context, service: Any) = synchronized(requestLock) {
        val app = context.applicationContext
        requested = Request(service, null)
        submit {
            if (requested.owner !== service) return@submit
            val previous = lease
            lease = null
            owner = service
            releaseSafely(previous)
            if (requested.owner !== service) return@submit
            lease = createLease(app)
            // Recovery also runs when the opt-in has since been switched off.
            releaseSafely(lease)
        }
    }

    fun update(context: Context, service: Any, acceptedSession: Any?) = synchronized(requestLock) {
        if (requested.owner !== service || requested.session === acceptedSession) return@synchronized
        val app = context.applicationContext
        val request = Request(service, acceptedSession)
        requested = request
        submit {
            // Cancellation is published by the caller, not queued behind slow property I/O.
            // Stale tasks must not acquire policy after disconnect/toggle-off.
            if (requested !== request || owner !== service) return@submit
            if (acceptedSession == null) {
                releaseSafely(lease)
                return@submit
            }
            val result = if (supported(app)) {
                lease?.acquire { requested === request } ?: XyAutoMediaLease.Result.UNAVAILABLE
            } else {
                releaseSafely(lease)
                XyAutoMediaLease.Result.UNAVAILABLE
            }
            report(result)
            // Let blink's existing watchdog observe the property. Do not send a separate DA
            // broadcast: it has no completion/cancellation reply and can arrive after restore.

        }
    }

    fun close(service: Any) = synchronized(requestLock) {
        if (requested.owner === service) requested = Request(null, null)
        submit {
            if (owner !== service) return@submit
            releaseSafely(lease)
            owner = null
            lease = null
        }
    }

    private fun releaseSafely(value: XyAutoMediaLease?) {
        try { value?.let { report(it.release()) } }
        catch (e: Exception) {
            // Recovery failure must not retain a dead service owner or block the next open.
            // The durable journal remains the authority; no guessed restore is attempted.
            AppLog.e("XYAuto: restoration pending; journal left for recovery", e)
        }
    }

    private fun submit(action: () -> Unit) {
        worker.execute {
            try { action() }
            catch (e: Exception) { AppLog.e("XYAuto: media policy unavailable; check recovery state on the next service start", e) }
        }
    }

    fun beforeProcessExit() {
        // killOnDisconnect calls System.exit after service teardown. Give the already queued
        // restore a bounded chance to finish rather than killing it with the executor thread.
        // A denied or stalled property write remains in the durable recovery journal.
        try { worker.submit {}.get(500, TimeUnit.MILLISECONDS) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: Exception) { AppLog.w("XYAuto: restoration still pending at process exit") }
    }

    private fun report(result: XyAutoMediaLease.Result) {
        AppLog.i("XYAuto projection media policy: $result (property state, not HFP/A2DP status)")
    }

    private fun createLease(context: Context): XyAutoMediaLease {
        // A lease is local recovery state, not a preference to migrate to another head unit.
        val journal = AtomicFile(File(ContextCompat.getNoBackupFilesDir(context), "xyauto-media-lease"))
        return XyAutoMediaLease(object : XyAutoMediaLease.Port {
            override fun boot(): String? = if (Build.VERSION.SDK_INT >= 24) {
                // AOSP PowerManagerService increments BOOT_COUNT at
                // PHASE_THIRD_PARTY_APPS_CAN_START, before ordinary apps run (API 24+).
                // Do not switch identity sources across reads: that could strand a live lease.
                XyAutoMediaJournal.bootId(android.provider.Settings.Global.getString(
                    context.contentResolver, android.provider.Settings.Global.BOOT_COUNT))
            } else null
            override fun read() = XyAutoProjectionProperty.read()
            override fun write(value: String) = XyAutoProjectionProperty.write(value)
            override fun saved(): XyAutoMediaLease.Journal? = try {
                journal.openRead().use { stream ->
                    val bytes = ByteArray(XyAutoMediaJournal.MAX_BYTES + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val n = stream.read(bytes, count, bytes.size - count)
                        if (n < 0) break
                        count += n
                    }
                    XyAutoMediaJournal.decode(bytes.copyOf(count))
                }
            } catch (e: FileNotFoundException) {
                // openRead also throws for inaccessible files; that is not an absent lease.
                if (journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()) throw e
                null
            }
            override fun save(record: XyAutoMediaLease.Journal): Boolean {
                var output: java.io.FileOutputStream? = null
                return try {
                    output = journal.startWrite()
                    output.write(XyAutoMediaJournal.encode(record))
                    // AtomicFile logs sync failures internally. Require our own successful
                    // sync before taking ownership of a firmware property that outlives us.
                    output.fd.sync()
                    journal.finishWrite(output)
                    saved() == record
                } catch (_: Exception) { journal.failWrite(output); false }
            }
            override fun clear(): Boolean {
                journal.delete()
                return listOf("", ".new", ".bak").none { File(journal.baseFile.path + it).exists() }
            }
        })
    }
}

internal object XyAutoProjectionProperty {
    private val loaded = try { System.loadLibrary("hur_xyauto"); true } catch (_: LinkageError) { false }
    fun read(): String? = try { if (loaded) nativeRead() else null } catch (_: LinkageError) { null }
    fun write(value: String): Boolean = try { loaded && nativeWrite(value) } catch (_: LinkageError) { false }
    private external fun nativeRead(): String?
    private external fun nativeWrite(value: String): Boolean
}
