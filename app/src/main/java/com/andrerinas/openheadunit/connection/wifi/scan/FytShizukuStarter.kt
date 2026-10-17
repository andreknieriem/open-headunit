package com.andrerinas.openheadunit.connection.wifi.scan

import android.content.*
import android.os.*
import com.andrerinas.openheadunit.connection.carkey.fyt.RemoteToolkit
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.connection.wifi.scan.FytSetupStep.Stage
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
    private fun steps() = FytSetupStep { AppLog.i("FYT Shizuku: $it") }
    private fun logState(context: Context, event: String) {
        // Only diagnostic flags/properties: never log ADB keys or starter shell output.
        AppLog.i("FYT Shizuku: $event; usbDebugging=" +
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) +
            " daemon=${SystemProperties.get("init.svc.adbd")} servicePort=${SystemProperties.get("service.adb.tcp.port")}" +
            " persistentPort=${SystemProperties.get("persist.adb.tcp.port")} binder=${isShizukuRunning()}")
    }
    fun needsRecovery(context: Context) = runCatching {
        val record = FytAdbPortJournal(context).read()
        record != null && (boot(context) < 0 || record.needsRecovery(boot(context)))
    }.getOrDefault(true)
    fun canCloseAdb(context: Context) = runCatching {
        val record = FytAdbPortJournal(context).read()
        record != null && (boot(context) < 0 || record.canClose(boot(context)))
    }.getOrDefault(FytAdbPortJournal(context).exists())

    private const val AUTO_START = "fyt-auto-start"
    private fun prefs(context: Context) = context.getSharedPreferences("wifi-scan-option", Context.MODE_PRIVATE)

    /** Preview 4 already recorded the user's choice to retain ADB. Migrate it before recovery
     * discards a journal from a previous boot; an explicit close is never migrated as consent. */
    fun rememberPreviousFytChoice(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(AUTO_START)) return
        val retained = runCatching { FytAdbPortJournal(context).read() }.getOrNull()
        if (retained?.keepOpen == true && !retained.stopRequested) {
            check(prefs.edit().putBoolean(AUTO_START, true).commit())
        }
    }

    fun shouldStartOnConnection(context: Context): Boolean = runCatching {
        val prefs = prefs(context)
        FytConnectionStart.eligible(Build.VERSION.SDK_INT,
            context.getSystemService(UserManager::class.java).isUserUnlocked,
            prefs.getBoolean("enabled", false), prefs.getBoolean(AUTO_START, false),
            isShizukuRunning(), needsRecovery(context))
    }.getOrDefault(false)

    /** Only the listener opened by this app is owned. Persist the user's close request first
     * so an interrupted close can finish on next launch without closing a retained session. */
    suspend fun closeAdb(context: Context): Boolean = locked {
        // Save the user's stop decision even if no owned listener remains. It also prevents
        // a queued connection startup from reopening ADB after this explicit close.
        val journal = FytAdbPortJournal(context)
        try {
            check(prefs(context).edit().putBoolean(AUTO_START, false).commit())
            val record = journal.read() ?: return@locked true
            val currentBoot = boot(context)
            check(currentBoot >= 0)
            if (record.boot != currentBoot) { journal.clear(); return@locked true }
            val closing = record.copy(keepOpen = false, stopRequested = true)
            journal.write(closing)
            val trace = steps()
            withModule(context, trace) { module ->
                trace.run(Stage.RESTORE_ADB) { restore(context, module, journal, closing) }
            }
            logState(context, "explicit ADB close complete")
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { AppLog.e("FYT Shizuku: explicit ADB close failed", e); false }
    }

    // Retained sessions survive app restarts. Only an interrupted explicit close or a
    // legacy rollback is retried automatically; this path never enables a new ADB port.
    suspend fun recover(context: Context): Boolean = locked {
        runCatching {
            val journal = FytAdbPortJournal(context)
            val record = journal.read() ?: return@runCatching true
            val currentBoot = boot(context)
            check(currentBoot >= 0)
            if (record.boot != currentBoot) journal.clear() // service.* does not survive reboot
            else if (record.needsRecovery(currentBoot)) {
                val trace = steps()
                withModule(context, trace) { module ->
                    trace.run(Stage.RESTORE_ADB) { restore(context, module, journal, record) }
                }
            }
            true
        }.onFailure {
            if (it is CancellationException) throw it
            logState(context, "ADB recovery failed")
            AppLog.e("FYT Shizuku: ADB recovery failed", it)
        }.getOrDefault(false)
    }

    suspend fun start(context: Context, automatic: Boolean = false,
                      connectionActive: () -> Boolean = { true }) = locked {
        val app = context.applicationContext
        if (automatic && (!connectionActive() || !shouldStartOnConnection(app))) return@locked
        currentCoroutineContext().ensureActive()
        if (automatic) {
            check(available(app)) { "FYT service unavailable during connection" }
            check(File(app.noBackupFilesDir, "shizuku-adb-private").isFile &&
                File(app.noBackupFilesDir, "shizuku-adb-public").isFile) { "Use FYT setup once to prepare ADB authorization" }
        }
        val trace = steps()
        logState(app, "setup requested")
        trace.run(Stage.PREPARE) {
            check(!needsRecovery(app)) { "Restore the previous temporary ADB port first" }
        }
        if (isShizukuRunning()) {
            if (!automatic) check(prefs(app).edit().putBoolean(AUTO_START, true).commit())
            return@locked
        }
        // FYT main command 161 can start TCP adbd independently of the Android USB
        // debugging toggle. Firmware may still require USB debugging for RSA authorization.
        val (info, starter) = trace.run(Stage.PREPARE) {
            val info = app.packageManager.getApplicationInfo(SHIZUKU, 0)
            val starter = File(info.nativeLibraryDir, "libshizuku.so")
            check(starter.isFile) { "Open Shizuku and use its USB startup instructions" }
            info to starter
        }
        withModule(app, trace) { module ->
            currentCoroutineContext().ensureActive()
            // A disconnect, explicit ADB close or option change may happen while binding.
            // Recheck before opening ADB; an obsolete connection must not start Shizuku later.
            if (automatic && (!connectionActive() || !shouldStartOnConnection(app)))
                throw CancellationException("Connection startup no longer needed")
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

            if (existingPort == null) {
                val currentBoot = boot(app)
                check(currentBoot >= 0) { "Cannot safely track the temporary ADB port" }
                // Empty and -1 both disable the TCP listener; the FYT API rejects empty values.
                val snapshot = FytAdbPortJournal.Record(current.ifEmpty { "-1" }, currentBoot, daemon, port, keepOpen = true)
                journal.write(snapshot)
                trace.run(Stage.OPEN_ADB) {
                    property(module, "service.adb.tcp.port", port.toString())
                    awaitProperty("service.adb.tcp.port", port.toString())
                    property(module, if (daemon == "running") "ctl.restart" else "ctl.start", "adbd")
                    awaitProperty("init.svc.adbd", "running")
                    awaitListener(port, true)
                }
            }
            // This ADB client's normal shell EOF is an IOException, and a short-lived
            // starter may close before open() returns. The Shizuku Binder, not shell EOF,
            // proves startup. A broken transport without that Binder still fails below.
            var transportFailure: java.io.IOException? = null
            try { runStarter(app, port, starter.absolutePath, info.sourceDir, trace) }
            catch (e: java.io.IOException) {
                transportFailure = if (e is FytSetupStep.Failure) e else FytSetupStep.Failure(Stage.START_SHIZUKU, e)
                AppLog.i("FYT Shizuku: starter transport ended (${e.javaClass.simpleName}); checking Binder")
            }
            trace.run(Stage.WAIT_SHIZUKU) {
                val started = withTimeoutOrNull(10_000) {
                    while (!isShizukuRunning()) delay(200)
                    true
                } == true
                if (!started) throw (transportFailure ?: java.io.IOException("Shizuku did not start"))
            }
            // On the reported FYT firmware, restarting adbd invalidates the live Shizuku
            // Binder. Keep the listener until an explicit close, including after app restart.
            logState(app, "Shizuku started; ADB retained until explicit close")
        }
        check(prefs(app).edit().putBoolean(AUTO_START, true).commit())
        AppLog.i("FYT Shizuku: setup complete")
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.lock()
        try { block() } finally { mutex.unlock() }
    }

    private suspend fun <T> withModule(context: Context, trace: FytSetupStep, block: suspend (IBinder) -> T): T {
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
            val module = trace.run(Stage.VENDOR_SERVICE) {
                withContext(Dispatchers.Main) { bound = app.bindService(intent(), connection, Context.BIND_AUTO_CREATE) }
                check(bound) { "FYT vendor service could not be bound" }
                checkNotNull(RemoteToolkit.Stub.asInterface(withTimeout(5_000) { ready.await() })
                    .getRemoteModule(0)?.asBinder()) { "FYT main module unavailable" }
            }
            return block(module)
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
        val targetDaemon = if (record.stopRequested || android.provider.Settings.Global.getInt(context.contentResolver,
            android.provider.Settings.Global.ADB_ENABLED, 0) == 0) "stopped" else record.daemon
        // Explicit close stops adbd: merely clearing the port and restarting can open fallback
        // TCP 5555 on Android 8–10 without USB. USB debugging sessions may disconnect as well.
        // Disabling debugging while setup is running also wins over the original snapshot.
        property(module, if (targetDaemon == "running") "ctl.restart" else "ctl.stop", "adbd")
        // A queued FYT command can leave the old daemon state visible briefly. Require the
        // desired state AND a closed owned port together for consecutive observations.
        withTimeout(5_000) {
            var matching = 0
            while (matching < 3) {
                val closed = SystemProperties.get("init.svc.adbd") == targetDaemon && !listenerOpen(record.port)
                matching = if (closed) matching + 1 else 0
                delay(250)
            }
        }
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
    private suspend fun runStarter(context: Context, port: Int, starter: String, apk: String, trace: FytSetupStep) {
        val socket = Socket()
        val timer = Executors.newSingleThreadScheduledExecutor()
        var adb: AdbConnection? = null
        try {
            // Close the socket to bound BOTH authentication and stream reads. Coroutine timeout
            // alone cannot interrupt the blocking wait inside the existing ADB client.
            timer.schedule({ runCatching { socket.close() } }, 45, TimeUnit.SECONDS)
            trace.run(Stage.CONNECT_ADB) {
                runInterruptible {
                    socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                    val privateKey = File(context.noBackupFilesDir, "shizuku-adb-private")
                    val publicKey = File(context.noBackupFilesDir, "shizuku-adb-public")
                    val crypto = if (privateKey.exists() && publicKey.exists())
                        AdbCrypto.loadAdbKeyPair(privateKey, publicKey)
                    else AdbCrypto.generateAdbKeyPair().also { it.saveAdbKeyPair(privateKey, publicKey) }
                    adb = AdbConnection.create(socket, crypto)
                    adb!!.connect(30_000)
                }
            }
            // Same native starter and --apk argument as Shizuku's own Starter.kt. Paths come
            // solely from the installed package; no editable command or shell input is accepted.
            // A normal short-lived shell can close before open() returns. Its IOException
            // is interpreted by the Binder check above rather than logged as startup failure.
            AppLog.i("FYT Shizuku: START_SHIZUKU: submitting starter")
            // A disconnected session cancels Java monitor waits in connect/open/read too,
            // preventing a delayed RSA approval from launching a now-obsolete starter.
            runInterruptible {
                adb!!.open("shell:${quote(starter)} --apk=${quote(apk)}").use { stream ->
                    while (!stream.isClosed) stream.read()
                }
            }
        } finally {
            runCatching { adb?.close() }
            runCatching { socket.close() }
            timer.shutdownNow()
        }
    }
}
