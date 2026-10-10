package com.andrerinas.openheadunit.connection.wifi.scan

import android.content.*
import android.os.*
import com.andrerinas.openheadunit.connection.carkey.fyt.RemoteToolkit
import com.andrerinas.openheadunit.utils.SystemProperties
import com.andrerinas.openheadunit.utils.adb.AdbConnection
import com.andrerinas.openheadunit.utils.adb.AdbCrypto
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit setup action only. No persistent ADB property and no silent RSA authorization. */
internal object FytShizukuStarter {
    const val SHIZUKU = "moe.shizuku.privileged.api"
    private fun isShizukuRunning() = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
    private fun intent() = Intent().setClassName("com.syu.ms", "app.ToolkitService")
    fun available(context: Context) = runCatching {
        context.packageManager.resolveService(intent(), 0)?.serviceInfo?.exported == true
    }.getOrDefault(false)

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private fun boot(context: Context) = android.provider.Settings.Global.getInt(
        context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    fun needsRecovery(context: Context) = FytAdbPortJournal(context).exists()

    // A crash before Shizuku starts cannot have a shell death watcher. Persist first and retry
    // rollback on the next app start. Never enable ADB from this automatic recovery path.
    suspend fun recover(context: Context): Boolean = locked {
        runCatching {
            val journal = FytAdbPortJournal(context)
            val record = journal.read() ?: return@runCatching true
            val currentBoot = boot(context)
            check(currentBoot >= 0)
            if (record.boot != currentBoot) journal.clear() // service.* does not survive reboot
            else withModule(context) { restore(context, it, journal, record) }
            true
        }.getOrDefault(false)
    }

    suspend fun start(context: Context) = locked {
        val app = context.applicationContext
        check(!needsRecovery(app)) { "Restore the previous temporary ADB port first" }
        if (isShizukuRunning()) return@locked
        // FYT main command 161 can start TCP adbd independently of the Android USB
        // debugging toggle. Try that path first; firmware may still require the user to
        // enable debugging for RSA authorization. Never change adb_enabled or bypass auth.
        val info = app.packageManager.getApplicationInfo(SHIZUKU, 0)
        val starter = File(info.nativeLibraryDir, "libshizuku.so")
        check(starter.isFile) { "Open Shizuku and use its USB startup instructions" }
        withModule(app) { module ->
            val current = SystemProperties.get("service.adb.tcp.port")
            val persistent = SystemProperties.get("persist.adb.tcp.port")
            check(FytAdbPortPolicy.valid(current) && FytAdbPortPolicy.valid(persistent))
            val daemon = SystemProperties.get("init.svc.adbd")
            check(daemon == "running" || daemon == "stopped")
            // Android 8–10 adbd can listen on 5555 without a port property when USB is absent.
            // Reuse that live listener instead of claiming ownership of an existing ADB setup.
            val existingPort = FytAdbPortPolicy.existing(current!!, persistent!!)
                ?: 5555.takeIf { daemon == "running" && listenerOpen(it) }
            // A distinct port lets recovery distinguish our listener from a later user
            // enabling the conventional 5555 endpoint. Reserve an unused port briefly.
            val port = existingPort ?: java.net.ServerSocket(0).use { it.localPort }
            val journal = FytAdbPortJournal(app)
            var record: FytAdbPortJournal.Record? = null
            try {
                if (existingPort == null) {
                    val currentBoot = boot(app)
                    check(currentBoot >= 0) { "Cannot safely track the temporary ADB port" }
                    // Empty and -1 both disable the TCP listener; the FYT API rejects empty values.
                    val snapshot = FytAdbPortJournal.Record(current.ifEmpty { "-1" }, currentBoot, daemon, port)
                    journal.write(snapshot)
                    record = snapshot
                    property(module, "service.adb.tcp.port", port.toString())
                    awaitProperty("service.adb.tcp.port", port.toString())
                    property(module, if (daemon == "running") "ctl.restart" else "ctl.start", "adbd")
                    awaitProperty("init.svc.adbd", "running")
                    awaitListener(port, true)
                }
                // This ADB client's normal shell EOF is an IOException, and a short-lived
                // starter may close before open() returns. The Shizuku Binder, not shell EOF,
                // proves startup. A broken transport without that Binder still fails below.
                var transportFailure: java.io.IOException? = null
                try { runStarter(app, port, starter.absolutePath, info.sourceDir) }
                catch (e: java.io.IOException) { transportFailure = e }
                val started = withTimeoutOrNull(10_000) {
                    while (!isShizukuRunning()) delay(200)
                    true
                } == true
                check(started) { transportFailure?.message ?: "Shizuku did not start" }
            } finally {
                withContext(NonCancellable) {
                    record?.let { restore(app, module, journal, it) }
                }
            }
        }
        // Some OEM init scripts kill shell descendants with adbd. Do not claim setup succeeded
        // if closing the temporary listener also stopped Shizuku on that firmware.
        check(isShizukuRunning()) { "Shizuku stopped when temporary ADB closed" }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.lock()
        try { block() } finally { mutex.unlock() }
    }

    private suspend fun <T> withModule(context: Context, block: suspend (IBinder) -> T): T {
        val app = context.applicationContext
        val ready = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null) ready.complete(binder)
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        var bound = false
        try {
            withContext(Dispatchers.Main) { bound = app.bindService(intent(), connection, Context.BIND_AUTO_CREATE) }
            check(bound)
            val module = RemoteToolkit.Stub.asInterface(withTimeout(5_000) { ready.await() })
                .getRemoteModule(0)?.asBinder()
            return block(checkNotNull(module))
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { if (bound) runCatching { app.unbindService(connection) } }
        }
    }

    private suspend fun restore(context: Context, module: IBinder, journal: FytAdbPortJournal,
        record: FytAdbPortJournal.Record) {
        check(boot(context) == record.boot)
        val current = SystemProperties.get("service.adb.tcp.port")
        check(FytAdbPortPolicy.valid(current))
        val survivingAdb = if (current == record.previous || (current == "" && record.previous == "-1")) {
            // The property may have been restored before a crash. An unrelated process can
            // subsequently reuse the private port; a TCP accept alone cannot identify adbd.
            checkNotNull(AdbHelloProbe.isAdb(record.port)) { "Temporary ADB listener state is unknown" }
        } else false
        if (!FytAdbPortPolicy.shouldRestore(current!!, record.previous, record.port, survivingAdb)) {
            // Either a later owner changed the setting, or rollback already completed.
            // If the property was restored before a crash but our private listener is still
            // open, shouldRestore keeps ownership until the daemon has also been restored.
            journal.clear()
            return
        }
        property(module, "service.adb.tcp.port", record.previous)
        awaitProperty("service.adb.tcp.port", record.previous)
        // Restore the daemon as well as the property. AOSP Android 10 adbd_main falls back to
        // 5555 without USB even for port=-1, so restarting a previously stopped daemon would
        // leave network debugging open. Source: system/core/adb/daemon/main.cpp, adbd_main.
        val targetDaemon = if (android.provider.Settings.Global.getInt(context.contentResolver,
            android.provider.Settings.Global.ADB_ENABLED, 0) == 0) "stopped" else record.daemon
        // Disabling debugging while setup is running wins over our original daemon snapshot.
        property(module, if (targetDaemon == "running") "ctl.restart" else "ctl.stop", "adbd")
        awaitProperty("init.svc.adbd", targetDaemon)
        awaitListener(record.port, false)
        journal.clear()
    }

    private suspend fun awaitProperty(key: String, value: String) {
        withTimeout(3_000) { while (SystemProperties.get(key) != value) delay(100) }
    }
    private fun listenerOpen(port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }; true
    }.getOrDefault(false)

    private suspend fun awaitListener(port: Int, enabled: Boolean) {
        withTimeout(5_000) {
            // Check twice so a transient gap while adbd restarts does not count as closed.
            var matching = 0
            while (matching < 2) {
                val open = listenerOpen(port)
                matching = if (open == enabled) matching + 1 else 0
                delay(250)
            }
        }
    }

    private fun property(module: IBinder, key: String, value: String) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.syu.ipc.IRemoteModule")
            data.writeInt(161)
            data.writeIntArray(intArrayOf(0, 0))
            data.writeFloatArray(null)
            data.writeStringArray(arrayOf(key, value))
            check(module.transact(1, data, null, IBinder.FLAG_ONEWAY))
        } finally { data.recycle() }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun runStarter(context: Context, port: Int, starter: String, apk: String) {
        val socket = Socket()
        val timer = Executors.newSingleThreadScheduledExecutor()
        var adb: AdbConnection? = null
        try {
            // Close the socket to bound BOTH authentication and stream reads. Coroutine timeout
            // alone cannot interrupt the blocking wait inside the existing ADB client.
            timer.schedule({ runCatching { socket.close() } }, 45, TimeUnit.SECONDS)
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            val privateKey = File(context.noBackupFilesDir, "shizuku-adb-private")
            val publicKey = File(context.noBackupFilesDir, "shizuku-adb-public")
            val crypto = if (privateKey.exists() && publicKey.exists())
                AdbCrypto.loadAdbKeyPair(privateKey, publicKey)
            else AdbCrypto.generateAdbKeyPair().also { it.saveAdbKeyPair(privateKey, publicKey) }
            adb = AdbConnection.create(socket, crypto)
            adb.connect(30_000)
            // Same native starter and --apk argument as Shizuku's own Starter.kt. Paths come
            // solely from the installed package; no editable command or shell input is accepted.
            adb.open("shell:${quote(starter)} --apk=${quote(apk)}").use { stream ->
                while (!stream.isClosed) stream.read()
            }
        } finally {
            runCatching { adb?.close() }
            runCatching { socket.close() }
            timer.shutdownNow()
        }
    }
}
