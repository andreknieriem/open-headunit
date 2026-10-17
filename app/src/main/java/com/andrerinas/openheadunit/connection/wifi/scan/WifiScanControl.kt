package com.andrerinas.openheadunit.connection.wifi.scan

import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.IWifiScanControl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** All requests are serialized, including rollback; a reconnect never races an old restore. */
object WifiScanControl {
    enum class State { OFF, READY, NEEDS_SHIZUKU, NEEDS_PERMISSION, WORKING, ACTIVE, RECOVERY, UNSUPPORTED, CHANGED, FAILED }
    private val mutableState = MutableStateFlow(State.OFF)
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences("wifi-scan-option", Context.MODE_PRIVATE)
    private lateinit var journal: ScanControlJournal
    private var remote: IWifiScanControl? = null
    private var binding = false
    private var bindGeneration = 0
    private var recoveryBindUsed = false
    private var worker: Job? = null
    private var dirty = false
    private var sessionOwner: Any? = null
    private var sessionConnection: Any? = null
    private val adbClose = ScanControlAdbClose()
    private val fytConnection = FytConnectionStart()
    private var fytStartup: Job? = null
    private var hotspot = false
    private var nativeHost = false
    private var nextRecovery = 0L
    private var recoveryDelay = 5_000L
    private var journalReady = false
    private var active = false
    private var yielded = false
    private val readiness = ScanControlReadiness()
    private val renewal = ScanControlRenewal()
    private var permissionCompletion: (() -> Unit)? = null
    private fun completePermissionRequest() {
        val completion = permissionCompletion
        permissionCompletion = null
        completion?.invoke()
    }
    private val ownerBinder = Binder()
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, WifiScanUserService::class.java.name))
            // AIDL/behavior changes must replace a surviving helper even in preview APKs
            // which share the same application versionCode.
            .daemon(true).processNameSuffix("wifi_scan_control").version(BuildConfig.VERSION_CODE * 100 + 4)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!binding || !granted()) return
            remote = IWifiScanControl.Stub.asInterface(binder)
            active = false // Any journal must reconcile with this helper before a new lease.
            reconcile()
        }
        override fun onServiceDisconnected(name: ComponentName?) { lostBinder() }
    }

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < 26 || this::context.isInitialized) return
        this.context = context.applicationContext
        journal = ScanControlJournal(this.context)
        Shizuku.addBinderReceivedListenerSticky { scope.launch { recoveryBindUsed = false; refresh() } }
        Shizuku.addBinderDeadListener { scope.launch { lostBinder() } }
        Shizuku.addRequestPermissionResultListener { code, _ ->
            if (code == REQUEST_PERMISSION) scope.launch { completePermissionRequest(); refresh() }
        }
        // Runtime gates reset at boot even if Shizuku has not restarted. Clear only a
        // positively identified older boot before allowing any new helper/lease work.
        // Persistent scan-always rollback must survive this check, including an OTA.
        scope.launch {
            runCatching {
                val currentBoot = boot()
                withContext(Dispatchers.IO) { journal.discardExpiredRuntime(currentBoot) }
            }.onFailure { AppLog.w("Wi-Fi scan control: recovery record retained") }
            journalReady = true
            refresh()
        }
        FytShizukuStarter.rememberPreviousFytChoice(this.context)
        // Remove preview 5's queued boot job. Startup now belongs to a live native wireless
        // session, so merely launching the app or receiving BOOT_COMPLETED opens no ADB port.
        this.context.getSystemService(android.app.job.JobScheduler::class.java).cancel(7315)
        scope.launch { FytShizukuStarter.recover(this@WifiScanControl.context) }
        scope.launch {
            while (true) {
                delay(5_000)
                // Permission can be granted in Shizuku itself, and legacy hotspot support
                // can become available after STA is turned off. Neither sends us a callback.
                // Do not bind for USB/self/no-session paths, or retry an unchanged refusal.
                readiness.poll(currentReadiness()) { refresh() }
                // Observe without fighting a user/OEM change. A Wi-Fi toggle can reset autojoin.
                if (active) reconcile()
                else if (journal.exists() && SystemClock.elapsedRealtime() >= nextRecovery) {
                    nextRecovery = SystemClock.elapsedRealtime() + recoveryDelay
                    recoveryDelay = (recoveryDelay * 2).coerceAtMost(60_000)
                    // No background permission prompts. A surviving authorized helper can
                    // restore even after the Shizuku manager disappears.
                    if (remote?.asBinder()?.isBinderAlive == true) reconcile()
                    else if (granted()) refresh()
                }
            }
        }
    }
    private fun lostBinder() {
        completePermissionRequest()
        binding = false
        bindGeneration++
        dirty = true
        // The Shizuku manager and user-service Binder are different processes. If only the
        // manager died, keep the already-authorized helper long enough to roll back its lease.
        if (remote?.asBinder()?.isBinderAlive == true) {
            reconcile()
        } else {
            remote = null
            active = false
            mutableState.value = if (journal.exists()) State.RECOVERY else if (!enabled()) State.OFF
                else if (sessionOwner == null) State.READY
                else if (yielded) State.CHANGED
                else if (hasBinder()) State.FAILED else State.NEEDS_SHIZUKU
            if (journal.exists() && granted() && !recoveryBindUsed) {
                recoveryBindUsed = true
                // Shizuku removes its dead connection AFTER delivering this callback. Defer
                // rebinding until removal, and allow only one automatic recovery attempt.
                scope.launch { yield(); refresh() }
            }
        }
    }
    fun enabled() = this::context.isInitialized && prefs.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) {
        check(prefs.edit().putBoolean("enabled", value).commit())
        yielded = false
        if (!value) cancelFytStartup() else startFytForConnection()
        refresh()
    }
    fun session(owner: Any, connection: Any, hotspot: Boolean) {
        if (!this::context.isInitialized) return
        if (sessionConnection !== connection) { yielded = false; cancelFytStartup() }
        fytConnection.session(connection)
        sessionOwner = owner
        sessionConnection = connection
        this.hotspot = hotspot
        this.nativeHost = true
        startFytForConnection()
        refresh()
    }
    private fun cancelFytStartup() {
        fytConnection.cancel()
        fytStartup?.cancel()
    }
    private fun startFytForConnection() {
        if (sessionOwner == null || !nativeHost || adbClose.active) return
        val permit = fytConnection.claim(FytShizukuStarter.shouldStartOnConnection(context)) ?: return
        // AapService supplies this hook only after a real wireless transport opens, excluding
        // USB/self/client sessions. Handshake progress reuses the connection key and never retries.
        // Run separately so projection does not wait for Shizuku or Android authorization.
        fytStartup = scope.launch {
            try {
                AppLog.i("FYT Shizuku: attempting startup for native wireless connection")
                FytShizukuStarter.start(context, automatic = true, connectionActive = { permit.valid })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.e("FYT Shizuku: connection startup failed; use setup to retry", e) }
            finally { refresh() }
        }
    }
    fun end(owner: Any) {
        if (!this::context.isInitialized || sessionOwner !== owner) return
        cancelFytStartup()
        fytConnection.end()
        sessionOwner = null
        sessionConnection = null
        yielded = false
        refresh()
    }
    fun providerInstalled() = runCatching {
        context.packageManager.getApplicationInfo(FytShizukuStarter.SHIZUKU, 0)
    }.isSuccess
    fun hasBinder() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun legacyBinder() = runCatching { hasBinder() && Shizuku.isPreV11() }.getOrDefault(false)
    fun granted() = runCatching {
        hasBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    fun retry() { recoveryBindUsed = false; yielded = false; refresh() }

    fun requestPermission(onComplete: () -> Unit = {}): Boolean {
        // A cached answer can arrive without showing any window. Complete the UI hold from the
        // reply (or Binder loss), rather than relying on an activity focus/resume callback.
        if (permissionCompletion != null) return false
        permissionCompletion = onComplete
        val launched = runCatching {
            if (!hasBinder() || Shizuku.isPreV11()) false
            else { Shizuku.requestPermission(REQUEST_PERMISSION); true }
        }.getOrDefault(false)
        if (!launched) {
            completePermissionRequest()
            // A failed prompt must not hide an outstanding rollback or invent a live session.
            refresh()
        }
        return launched
    }
    fun permissionDeniedPermanently() = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    private fun currentReadiness(): ScanControlReadiness.Snapshot? =
        if (enabled() && sessionOwner != null) ScanControlReadiness.Snapshot(mode(), granted()) else null

    fun refresh() {
        if (!this::context.isInitialized || !journalReady) return
        readiness.remember(currentReadiness())
        if (!enabled() && !journal.exists()) { mutableState.value = State.OFF; return }
        // Enabling an option is not an active scan-control lease. USB/self/no-session paths
        // need neither the privileged helper nor automatic binding/permission preparation.
        if (sessionOwner == null && !journal.exists()) { mutableState.value = State.READY; return }
        if (sessionOwner != null && mode() == ScanControlPolicy.UNSUPPORTED && !journal.exists()) {
            mutableState.value = State.UNSUPPORTED
            return
        }
        if (!granted()) {
            mutableState.value = when {
                journal.exists() -> State.RECOVERY
                !hasBinder() -> State.NEEDS_SHIZUKU
                else -> State.NEEDS_PERMISSION
            }
            if (journal.exists() && remote?.asBinder()?.isBinderAlive == true) reconcile()
            return
        }
        if (remote == null) {
            if (!binding) {
                binding = true
                val generation = ++bindGeneration
                mutableState.value = State.WORKING
                runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
                    binding = false
                    mutableState.value = if (journal.exists()) State.RECOVERY else State.FAILED
                }
                scope.launch {
                    delay(30_000)
                    if (generation == bindGeneration && binding && remote == null) {
                        binding = false
                        runCatching { Shizuku.unbindUserService(args, connection, false) }
                        mutableState.value = if (journal.exists()) State.RECOVERY else State.FAILED
                    }
                }
            }
            return
        }
        reconcile()
    }

    private fun mode(): Int {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return ScanControlPolicy.mode(Build.VERSION.SDK_INT, hotspot,
            wifi.wifiState == WifiManager.WIFI_STATE_DISABLED, nativeHost)
    }
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun wantsPause() = enabled() && sessionOwner != null && !yielded && !adbClose.active && granted()

    private fun reconcile() {
        dirty = true
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (dirty) {
                dirty = false
                val service = remote ?: break
                try {
                    val want = wantsPause()
                    val record = withContext(Dispatchers.IO) { journal.read() }
                    if (record != null && (!active || !want || mode() != record.mode)) {
                        mutableState.value = State.WORKING
                        // Autojoin resets at boot. Do not replay an old snapshot over the new
                        // boot's user/admin policy. Scan-always persists, so it still needs rollback.
                        val currentBoot = boot()
                        if (!ScanControlPolicy.discardAfterBoot(record.mode, record.boot, currentBoot)) {
                            check(withContext(Dispatchers.IO) { service.restore(record.id, record.mode, record.original) })
                        }
                        check(service === remote)
                        withContext(Dispatchers.IO) { journal.clear() }
                        active = false
                        recoveryDelay = 5_000
                        AppLog.i("Wi-Fi scan control: lease released")
                    }
                    if (active && record != null && ScanControlPolicy.hasReadback(record.mode)) {
                        val current = withContext(Dispatchers.IO) { service.readState(record.mode) }
                        if (current != 0) {
                            check(withContext(Dispatchers.IO) { service.restore(record.id, record.mode, record.original) })
                            check(service === remote)
                            withContext(Dispatchers.IO) { journal.clear() }
                            active = false
                            yielded = true
                        }
                    }
                    // The five-second loop observes readiness; legacy Wi-Fi writes have a
                    // separate five-minute deadline. A delayed poll sends one request, never
                    // a burst to catch up. Recheck current intent after the suspending reads.
                    if (active && record != null && wantsPause() && mode() == record.mode &&
                        renewal.due(record.id, record.mode, SystemClock.elapsedRealtime())) {
                        check(withContext(Dispatchers.IO) { service.renew(record.id) })
                        check(service === remote)
                        renewal.applied(record.id, SystemClock.elapsedRealtime())
                    }
                    if (want && !active && !yielded) {
                        val mode = mode()
                        if (mode == ScanControlPolicy.UNSUPPORTED) {
                            mutableState.value = State.UNSUPPORTED
                            continue
                        }
                        mutableState.value = State.WORKING
                        val original = withContext(Dispatchers.IO) { service.readState(mode) }
                        check(service === remote && ScanControlPolicy.validSnapshot(mode, original))
                        if (!wantsPause()) { dirty = true; continue }
                        val currentBoot = boot()
                        if (mode != ScanControlPolicy.HOTSPOT_SCAN) check(currentBoot >= 0)
                        val acquired = ScanControlJournal.Record(mode, original, currentBoot)
                        withContext(Dispatchers.IO) { journal.write(acquired) }
                        val applied = withContext(Dispatchers.IO) { service.apply(ownerBinder, acquired.id, mode, original) }
                        check(service === remote)
                        if (!applied) {
                            // False means no change or a confirmed helper rollback. A thrown
                            // exception instead retains the journal for uncertain completion.
                            withContext(Dispatchers.IO) { journal.clear() }
                            // A concurrent user/radio change is not a device failure. Yield
                            // for this session; an explicit Retry can acquire a fresh snapshot.
                            yielded = true
                            mutableState.value = if (mode() == ScanControlPolicy.UNSUPPORTED)
                                State.UNSUPPORTED else State.CHANGED
                            continue
                        }
                        active = true
                        renewal.applied(acquired.id, SystemClock.elapsedRealtime())
                        AppLog.i("Wi-Fi scan control: paused, mode=$mode SDK=${Build.VERSION.SDK_INT}")
                    }
                    mutableState.value = when {
                        yielded -> State.CHANGED
                        active -> State.ACTIVE
                        enabled() && !hasBinder() -> State.NEEDS_SHIZUKU
                        enabled() && !granted() -> State.NEEDS_PERMISSION
                        enabled() -> State.READY
                        else -> State.OFF
                    }
                    if (want != wantsPause()) dirty = true
                    if (!granted() && !journal.exists()) { remote = null; binding = false }
                } catch (e: Exception) {
                    AppLog.w("Wi-Fi scan control: ${e.javaClass.simpleName}: ${e.message}")
                    active = false
                    mutableState.value = if (journal.exists()) State.RECOVERY else State.FAILED
                    // Retain rollback on refusal/timeouts; a foreground retry or new granted
                    // binder retries it. Never silently replace the original with the current 0.
                }
            }
        }
    }
    /** ADB shutdown may take the helper with it. Restore first, and keep new leases out until
     * shutdown finishes; a failed restoration leaves ADB available for another recovery try. */
    internal suspend fun closeOwnedAdb(): ScanControlAdbClose.Result = adbClose.run(
        restore = {
            cancelFytStartup()
            reconcile()
            worker?.join()
            !journal.exists()
        },
        close = { FytShizukuStarter.closeAdb(context) },
        finished = {
            // Also covers a connection replacement while close was suspended. A surviving
            // helper must not immediately reacquire a lease after the user's explicit stop.
            yielded = true
            refresh()
        }
    )

    fun canRestoreWithoutShizuku(): Boolean = runCatching {
        val record = journal.read()
        record?.mode == ScanControlPolicy.HOTSPOT_SCAN && record.original == 1 && !granted() &&
            remote?.asBinder()?.isBinderAlive != true && worker?.isActive != true
    }.getOrDefault(false)

    fun confirmSystemRestore() {
        if (!canRestoreWithoutShizuku()) return
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 0 &&
            wifi.isScanAlwaysAvailable) {
            journal.clear()
            active = false
            refresh()
        }
    }

    fun summaryText(displayContext: Context, nativeHost: Boolean, hotspot: Boolean): String =
        displayContext.getString(summaryRes(nativeHost, hotspot))

    /** Share the reason with UI actions; localized text is never used to decide what to show. */
    internal fun summaryRes(nativeHost: Boolean, hotspot: Boolean): Int {
        if (FytShizukuStarter.needsRecovery(context)) {
            return R.string.wifi_scan_summary_adb_recovery
        }
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val current = state.value
        // A running session takes priority over unsaved connection choices in the list.
        val detailRes = ScanControlSummary.detail(
            current, Build.VERSION.SDK_INT,
            if (sessionOwner != null) this.nativeHost else nativeHost,
            if (sessionOwner != null) this.hotspot else hotspot,
            runCatching { wifi.wifiState == WifiManager.WIFI_STATE_DISABLED }.getOrDefault(false),
            hasBinder(), granted(), legacyBinder(), providerInstalled())
        return ScanControlSummary.compact(detailRes, enabled())
    }

    const val REQUEST_PERMISSION = 7314
}
