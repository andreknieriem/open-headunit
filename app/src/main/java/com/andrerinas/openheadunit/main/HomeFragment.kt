package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerDiscovery
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Gravity
import android.view.LayoutInflater
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.net.ConnectivityManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import android.os.Build
import android.bluetooth.BluetoothDevice
import android.os.CountDownTimer
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.DriverCandidatePolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.PokeTargetPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeAaHandshakeManager
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbyManager
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbDeviceDiagnostics
import android.content.res.Configuration
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDaemonReachability
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.CarLauncherManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ColorUtils
import com.andrerinas.openheadunit.utils.HomeUiHelper
import com.andrerinas.openheadunit.utils.ProjectionHealthPolicy
import com.andrerinas.openheadunit.utils.ProjectionHealthStore
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.button.MaterialButton
import com.andrerinas.openheadunit.utils.VpnControl
import com.andrerinas.openheadunit.connection.self.SelfLaunchPath
import com.andrerinas.openheadunit.connection.self.SelfLaunchRoutePolicy
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.location.LocationHolder
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.connection.usb.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WirelessSelectionPolicy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionNetworkDetail
import com.andrerinas.openheadunit.connection.ConnectionNetworkDetailPolicy
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.view.HomePagesHintView
import androidx.activity.OnBackPressedCallback
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.PhoneExitQuietPolicy
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    // Permission is a continuation of its original input, including a manual Self launch.
    private var pendingSelfModeVpn = false
    private var pendingSelfModeTicket: Long? = null

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!pendingSelfModeVpn) return@registerForActivityResult
        val ticket = pendingSelfModeTicket
        pendingSelfModeVpn = false
        pendingSelfModeTicket = null
        if (ticket == null) return@registerForActivityResult
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AppLog.i("VPN permission granted. Starting DummyVpnService and Self Mode.")
            startSelfModeInternal(needsDummyVpn = true, ticket = ticket)
        } else {
            AppLog.w("VPN permission denied. Offline Self Mode might fail.")
            ToastUtils.showToast(requireContext(), getString(R.string.failed_start_android_auto), Toast.LENGTH_LONG, force = true)
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            showNativeAaDeviceSelector()
        } else {
            ToastUtils.showToast(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG, force = true)
        }
    }

    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var selfModeTile: View
    private lateinit var usbTile: View
    private lateinit var settingsTile: View
    private lateinit var wifiTile: View
    private lateinit var exitButton: View
    private lateinit var appsButton: View
    private var primaryAction: View? = null
    private var primaryCancel: View? = null
    private lateinit var favoriteSlots: List<View>
    private var decision: HomePrimaryPolicy.Decision? = null
    private var cachedPalette: HomeUiHelper.Palette? = null
    private var reconnectCountdown: Job? = null
    private var stageQuietJob: Job? = null
    private var reconnectTarget: HomePrimaryPolicy.Connection? = null
    private var reconnectSecondsLeft = 0
    private var autoConnectCancelled = false
    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var actionDialog: android.app.Dialog? = null
    private var manufacturerPickerLoading = false
    private var driverCountdownTimer: CountDownTimer? = null
    private var healthCard: View? = null
    private var healthMessage: TextView? = null
    private var pendingHealthAction: ProjectionHealthPolicy.Action? = null
    private var homeInfoTicker: Job? = null
    private var gpsListener: LocationListener? = null
    private var gpsSpeedKmh: Int? = null
    /** The launcher home on a small screen, where the main button's card gives way to the rest. */
    private var compactHome = false
    /** The launcher home on a very wide unit, where the favorites sit beside the card. */
    private var ultrawideHome = false
    /** How many pages the home has now: one unless it is the launcher and has more to show. */
    private var pageCount = 1
    private var cardFitPending = false
    /** Watches MainActivity's status notices, which come and go on its schedule, not the home's renders. */
    private var noticesLayoutListener: View.OnLayoutChangeListener? = null
    /**
     * The widgets under the favorites had no room on this screen, so they are on a page of their
     * own. Decided once per home view: the screen does not change size without a new one.
     */
    private var widgetRowOverflows = false
    /** The one-time hint for adding pages, while it is on screen. */
    private var pagesHint: HomePagesHintView? = null
    private val pagesHintCheck = Runnable { maybeShowPagesHint() }
    private val pagesHintBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            pagesHint?.close(HomePagesHintView.Reason.BACK)
        }
    }
    /** Whether a session was up the last time the home looked, to notice one starting or ending. */
    private var lastSessionActive: Boolean? = null

    // The widget waiting on the user, to be allowed or set up, and the answer coming back. Kept
    // across a recreation, or a cancel after it would leave the id allocated with no widget.
    private var pendingWidgetId = -1
    // Where that widget goes: a widget page's index, or null for the row under the favorites.
    private var pendingWidgetPage: Int? = null

    private val widgetBindLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val (id, page) = takePendingWidget(result.data)
        if (result.resultCode == android.app.Activity.RESULT_OK && id >= 0) {
            configureOrPlaceWidget(id, page)
        } else {
            releasePendingWidget(id, "was not allowed")
        }
    }

    private val widgetConfigureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val (id, page) = takePendingWidget(result.data)
        if (result.resultCode == android.app.Activity.RESULT_OK && id >= 0) {
            placeWidget(id, page)
        } else {
            releasePendingWidget(id, "was not set up")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            pendingWidgetId = state.getInt(STATE_PENDING_WIDGET_ID, -1)
            pendingWidgetPage = state.getInt(STATE_PENDING_WIDGET_PAGE, -1).takeIf { it >= 0 }
            pendingSelfModeVpn = state.getBoolean(STATE_PENDING_SELF_VPN, false)
            pendingSelfModeTicket = if (state.containsKey(STATE_PENDING_SELF_TICKET)) {
                state.getLong(STATE_PENDING_SELF_TICKET)
            } else null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_WIDGET_ID, pendingWidgetId)
        outState.putInt(STATE_PENDING_WIDGET_PAGE, pendingWidgetPage ?: -1)
        outState.putBoolean(STATE_PENDING_SELF_VPN, pendingSelfModeVpn)
        pendingSelfModeTicket?.let { outState.putLong(STATE_PENDING_SELF_TICKET, it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context).also { inflateHomeContent(it) }

    private fun inflateHomeContent(container: FrameLayout) {
        cachedPalette = null
        LayoutInflater.from(container.context).inflate(R.layout.fragment_home, container, true)
        selfModeTile = container.findViewById(R.id.self_mode_button)
        usbTile = container.findViewById(R.id.usb_button)
        settingsTile = container.findViewById(R.id.settings_button)
        wifiTile = container.findViewById(R.id.wifi_button)
        exitButton = container.findViewById(R.id.exit_button)
        appsButton = container.findViewById(R.id.apps_button)
        primaryAction = container.findViewById(R.id.home_primary_action)
        primaryCancel = container.findViewById(R.id.home_primary_cancel)
        favoriteSlots = HomeUiHelper.favoriteSlotIds.mapNotNull { container.findViewById(it) }
        pageCount = 1
        widgetRowOverflows = false
        // A fit still waiting on the previous view would never run, and block every later one.
        cardFitPending = false
        container.findViewById<HomePager>(R.id.home_pager)?.let { pager ->
            pager.pageGap = container.resources.getDimensionPixelSize(R.dimen.home_gap)
            pager.onPageChanged = {
                renderDots()
                reportBringUpOwner()
            }
        }
        // A long press on the main button's empty space is where pages are added, and so is one
        // on the empty part of the main page around it: under the favorites and the widget row.
        // The widget row itself is in the list because its scroll view takes every touch on it and
        // never calls a long-click listener of its own, so the empty space past the last widget
        // would swallow the press. Long-clickable is not focusable, so the D-pad does not stop there.
        // Only a long press: a tap there still does nothing.
        listOf(
            R.id.home_primary_card, R.id.home_page_main, R.id.home_favorites_area, R.id.home_widgets_row
        ).forEach { id ->
            container.findViewById<View>(id)?.setOnLongClickListener { showPageMenu(widgetPage = null) }
        }
        setupListeners()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderHome()

        activity?.findViewById<View>(R.id.status_notices)?.let { notices ->
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                this.view?.let { scheduleCardFit(it) }
            }
            notices.addOnLayoutChangeListener(listener)
            noticesLayoutListener = listener
        }

        // In MainActivity's notices, under the connection issue banner, so both home layouts
        // show it the same way and it never covers the pill.
        activity?.let { act ->
            healthCard = act.findViewById(R.id.projection_health_card)
            healthMessage = act.findViewById(R.id.projection_health_message)
            act.findViewById<MaterialButton>(R.id.projection_health_apply)?.setOnClickListener {
                applyProjectionHealthSuggestion()
            }
            act.findViewById<MaterialButton>(R.id.projection_health_later)?.setOnClickListener {
                dismissProjectionHealthSuggestion()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect {
                    // A session starting or ending is a fresh start for the home: back to the main
                    // button, which is where "Back to Android Auto" and the connections are.
                    val active = commManager.isConnected
                    if (lastSessionActive != null && lastSessionActive != active) showFirstPage(animate = false)
                    lastSessionActive = active
                    renderHome()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { renderHome() }
            }
        }

        // The card and the tiles tell the bring-up now, so they follow the tracker the pill reads.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStageTracker.stage.collect { renderHome() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStageTracker.network.collect { renderHome() }
            }
        }

        mainViewModel.usbDevices.observe(viewLifecycleOwner) { renderHome() }
        mainViewModel.homePressed.observe(viewLifecycleOwner) { showFirstPage(animate = isResumed) }

        val appSettings = App.provide(requireContext()).settings
        // A retained/configuration Home is not a new Open. Automatic work must start from Run
        // and carry its Stop boundary through countdowns, dispatch and permission continuations.
        val autoConnectTicket = commManager.currentAppRunTicket()

        if (autoConnectTicket != null &&
            (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot)) {
            commManager.restoreAppForEntry(commManager.appOwnershipRevision())
        }

        if (autoConnectTicket != null) viewLifecycleOwner.lifecycleScope.launch {
            val isAutoConnectEnabled = appSettings.autoStartSelfMode ||
                appSettings.autoConnectLastSession ||
                appSettings.autoConnectSingleUsbDevice

            val delaySec = appSettings.autoConnectDelaySeconds
            if (isAutoConnectEnabled && delaySec > 0 && !forceSelfModeLaunch && !commManager.isConnected) {
                AppLog.i("HomeFragment: Waiting ${delaySec}s before attempting auto-connect...")
                countDownToAutoConnect(delaySec, autoConnectTarget(appSettings))
            }

            if (!isAdded || commManager.isConnected ||
                !commManager.acceptsAppRunTicket(autoConnectTicket)) return@launch
            if (autoConnectCancelled) {
                AppLog.i("HomeFragment: auto-connect cancelled from the home screen countdown")
                return@launch
            }

            for (methodId in appSettings.autoConnectPriorityOrder) {
                if (commManager.isConnected || !commManager.acceptsAppRunTicket(autoConnectTicket)) break
                when (methodId) {
                    Settings.AUTO_CONNECT_LAST_SESSION -> {
                        if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                            hasAttemptedAutoConnect = true
                            if (attemptAutoConnect(autoConnectTicket)) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "auto-connect last session",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                            }
                        }
                    }
                    Settings.AUTO_CONNECT_SELF_MODE -> {
                        if ((appSettings.autoStartSelfMode || forceSelfModeLaunch) && !hasAutoStarted && !commManager.isConnected) {
                            hasAutoStarted = true
                            forceSelfModeLaunch = false // Reset once processed
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-start self mode",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                            startSelfMode(autoConnectTicket)
                        }
                    }
                    Settings.AUTO_CONNECT_SINGLE_USB -> {
                        if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                            hasAttemptedSingleUsbAutoConnect = true
                            if (attemptSingleUsbAutoConnect(autoConnectTicket)) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "auto-connect single USB",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The auto-connect delay, shown on the main button so the driver can skip or cancel it. Nothing
     * is shown, and nothing waited for, when no method is left that would actually start.
     */
    private suspend fun countDownToAutoConnect(seconds: Int, target: HomePrimaryPolicy.Connection?) {
        if (target == null) return
        reconnectTarget = target
        val owner = viewLifecycleOwner
        reconnectCountdown = owner.lifecycleScope.launch {
            for (left in seconds downTo 1) {
                // Settings inflates the main graph, so it builds a home of its own and drops it
                // before that view's lifecycle starts. Its scope is then never cancelled, and
                // asking for viewLifecycleOwner once the view is gone throws.
                if (view == null) break
                reconnectSecondsLeft = left
                // A stopped home is not on screen, and rendering restarts its shine; onResume
                // renders the number it has reached when it comes back.
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) renderHome()
                delay(1000L)
            }
        }
        try {
            reconnectCountdown?.join()
        } finally {
            // The home's view can go (a list or settings screen opened over it) while this waits,
            // which cancels the wait while the fragment lives on. Without this the next view would
            // show the countdown frozen with nothing behind it to cancel or skip.
            reconnectCountdown = null
            reconnectTarget = null
        }
        renderHome()
    }

    /** The connection the first auto-connect method still to run will try, in the user's priority order. */
    private fun autoConnectTarget(appSettings: Settings): HomePrimaryPolicy.Connection? {
        if (autoConnectCancelled) return null
        return appSettings.autoConnectPriorityOrder.firstNotNullOfOrNull { methodId ->
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION ->
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect) HomeUiHelper.lastConnection(appSettings) else null
                Settings.AUTO_CONNECT_SELF_MODE ->
                    if (appSettings.autoStartSelfMode && !hasAutoStarted) HomePrimaryPolicy.Connection.SELF else null
                Settings.AUTO_CONNECT_SINGLE_USB ->
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect) HomePrimaryPolicy.Connection.USB else null
                else -> null
            }
        }
    }

    private fun cancelAutoConnectCountdown() {
        autoConnectCancelled = true
        reconnectCountdown?.cancel()
    }

    /**
     * For [MainActivity.cancelBringUp]: the status pill's X has to end this countdown too, or the
     * home connects once it runs out although the driver cancelled. Does nothing when no countdown
     * is running, so a later bring-up is never blocked by an old cancel.
     */
    fun stopAutoConnectCountdown() {
        if (reconnectCountdown?.isActive != true) return
        AppLog.i("HomeFragment: auto-connect countdown stopped by the bring-up's cancel")
        cancelAutoConnectCountdown()
    }

    private fun renderHome() {
        val ctx = context ?: return
        val root = view ?: return
        val appSettings = App.provide(ctx).settings
        val usbPhone = detectedUsbPhone(ctx)
        val chosen = HomeUiHelper.chosenConnections(appSettings)
        val decision = HomePrimaryPolicy.decide(
            HomePrimaryPolicy.Input(
                chosen = chosen,
                lastConnection = HomeUiHelper.lastConnection(appSettings),
                usbPhoneDetected = usbPhone != null,
                sessionActive = sessionLive(),
                reconnectTarget = reconnectTarget,
                wifiScanning = AapService.scanningState.value,
                sessionConnection = transportConnection().takeIf { sessionLive() },
                stage = shownStage(),
                attemptConnection = transportConnection().takeIf { !sessionLive() }
            )
        )
        this.decision = decision
        // A connection that starts under the hint takes the screen back; the hint was seen.
        if (pagesHint != null && (commManager.isConnected || !HomePagesHintPolicy.isIdle(decision.status))) {
            pagesHint?.close(HomePagesHintView.Reason.LEFT)
        }
        val palette = homePalette(ctx)
        // With more than one page the card is not always in view, so the bar carries every chosen
        // connection, the one the card offers included. Otherwise, on a page without the card, a
        // unit with a single chosen mode has no way to start it.
        val barConnections = HomePrimaryPolicy.barConnections(
            decision, chosen, HomePagesPolicy.barCarriesEveryConnection(pageCount)
        )
        HomeUiHelper.applyBar(ctx, root, appSettings, barConnections, palette, decision.tiles)
        HomeUiHelper.applyPrimaryCard(
            ctx, root, appSettings, decision, usbPhone?.let { phoneName(it) }, reconnectSecondsLeft, palette,
            networkLine = ConnectionStageTracker.network.value?.let { networkText(it) }
        )
        HomeUiHelper.fitDriverBar(root, appSettings.homeButtonScalePercent)
        renderHomeInfo()
        reportBringUpOwner()
        // Cancel, the progress bar and the step come and go with the status, so the lines that fit
        // change with it, not only with the layout.
        scheduleCardFit(root)
    }

    /**
     * Hands the bring-up to the status pill whenever the card is not the one telling it. [started]
     * is passed from onStart, where the view's lifecycle has not reached STARTED yet.
     */
    private fun reportBringUpOwner(
        started: Boolean = viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    ) {
        val cardOnScreen = (view?.findViewById<HomePager>(R.id.home_pager)?.currentPage ?: 0) == 0
        val tells = started && HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen)
        if (mainViewModel.homeShowsBringUp.value != tells) mainViewModel.homeShowsBringUp.value = tells
    }

    /**
     * Android Auto is up, or about to cover the home. Narrower than [CommManager.isConnected],
     * which is already true while the connection is being secured: that part is a step the card
     * names now, not a session to go back to.
     */
    private fun sessionLive(): Boolean = commManager.connectionState.value.let {
        it is CommManager.ConnectionState.HandshakeComplete || it is CommManager.ConnectionState.TransportStarted
    }

    /** What the session, or the attempt in flight, goes over. Null without a connection. */
    private fun transportConnection(): HomePrimaryPolicy.Connection? = when {
        !commManager.isConnected -> null
        commManager.isUsbSession -> HomePrimaryPolicy.Connection.USB
        commManager.isLoopbackSession -> HomePrimaryPolicy.Connection.SELF
        else -> HomePrimaryPolicy.Connection.WIFI
    }

    /**
     * The bring-up step, held back for a moment after the phone ended the session itself, the
     * same as the status pill: the listeners reopen right behind a bye-bye and their step would
     * read as a reconnect nobody asked for.
     */
    private fun shownStage(): ConnectionStage? {
        val now = SystemClock.elapsedRealtime()
        val leftAt = ConnectionStageTracker.phoneLeftAtMs
        stageQuietJob?.cancel()
        if (!PhoneExitQuietPolicy.suppressesPill(leftAt, now)) {
            // A path that connects without reporting steps still has a connection on its way.
            val inFlight = commManager.isConnected && !sessionLive()
            return ConnectionStageTracker.stage.value ?: if (inFlight) ConnectionStage.CONNECTING else null
        }
        stageQuietJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(PhoneExitQuietPolicy.remainingMs(leftAt, now))
            renderHome()
        }
        return null
    }

    private fun networkText(detail: ConnectionNetworkDetail): String {
        val line = ConnectionNetworkDetailPolicy.lineFor(detail)
        return getString(line.id, *line.args.toTypedArray())
    }

    /**
     * A tile whose connection is on its way leads to the card, where the step and Cancel are,
     * instead of starting a second attempt.
     */
    private fun showsBringUp(connection: HomePrimaryPolicy.Connection): Boolean {
        if (decision?.tiles?.get(connection) != HomePrimaryPolicy.TileState.CONNECTING) return false
        showFirstPage(animate = true)
        return true
    }

    private fun onPrimaryCancelClicked() {
        // During the countdown Cancel only ends the countdown, even when the pill was hidden for it
        // (HomePrimaryPolicy.homeTellsBringUp): the card then shows the listening step with its own
        // Cancel, which is the one that stops the bring-up.
        if (decision?.status == HomePrimaryPolicy.Status.RECONNECTING) {
            cancelAutoConnectCountdown()
        } else {
            (activity as? MainActivity)?.cancelBringUp("home Cancel")
        }
    }

    /**
     * The date, the time and the speed, and nothing else: the ticker has to refresh this block
     * once a minute, not rebuild the whole home screen.
     */
    private fun renderHomeInfo() {
        val ctx = context ?: return
        val root = view ?: return
        HomeUiHelper.applyInfoBlock(
            rootView = root,
            settings = App.provide(ctx).settings,
            palette = homePalette(ctx),
            clock = clockText(),
            date = dateText(),
            speed = HomeUiHelper.speedText(gpsSpeedKmh),
            countdownVisible = decision?.status == HomePrimaryPolicy.Status.RECONNECTING
        )
    }

    // The format follows the unit's locale and its 12/24 hour setting, which is what the driver
    // already reads everywhere else on the screen.
    private fun clockText(): String =
        android.text.format.DateFormat.getTimeFormat(requireContext()).format(java.util.Date())

    private fun dateText(): String =
        android.text.format.DateFormat.getMediumDateFormat(requireContext()).format(java.util.Date())

    private fun startHomeInfo() {
        val appSettings = App.provide(requireContext()).settings
        homeInfoTicker?.cancel()
        homeInfoTicker = null
        stopSpeedUpdates()
        gpsSpeedKmh = null
        if (!appSettings.showHomeClock && !appSettings.showHomeSpeed) return
        if (appSettings.showHomeSpeed) {
            // Whatever the unit already knows, so the readout is not blank while the chip locks.
            gpsSpeedKmh = LocationHolder.currentGpsFix(requireContext(), SPEED_MAX_AGE_MS)
                ?.takeIf { it.hasSpeed() }
                ?.let { kmh(it.speed) }
            startSpeedUpdates()
        }
        renderHomeInfo()
        if (appSettings.showHomeClock) {
            homeInfoTicker = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    renderHomeInfo()
                    // To the next minute, so the clock turns over with the unit's own.
                    delay(MINUTE_MS - now % MINUTE_MS + CLOCK_SLACK_MS)
                }
            }
        }
    }

    private fun stopHomeInfo() {
        homeInfoTicker?.cancel()
        homeInfoTicker = null
        stopSpeedUpdates()
        gpsSpeedKmh = null
    }

    /**
     * The home screen is what the driver looks at while nothing is projected, and the app's own
     * GPS service only runs with a session, so the readout subscribes from here and lets go in
     * onPause.
     */
    @SuppressLint("MissingPermission")
    private fun startSpeedUpdates() {
        if (gpsListener != null) return
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.i("HomeFragment: the speed readout has no location permission, showing the placeholder")
            return
        }
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gpsSpeedKmh = if (location.hasSpeed()) kmh(location.speed) else null
                renderHomeInfo()
            }

            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }
        try {
            // Time only, no distance gate: a head unit sitting still still reports its speed, and
            // this is the same subscription the app's own GPS service makes.
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, SPEED_INTERVAL_MS, 0f, listener, Looper.getMainLooper()
            )
            gpsListener = listener
        } catch (e: Exception) {
            // A unit with no GPS provider, or one that refuses the subscription.
            AppLog.w("HomeFragment: the speed readout could not subscribe to GPS: ${e.message}")
        }
    }

    private fun stopSpeedUpdates() {
        val listener = gpsListener ?: return
        gpsListener = null
        (context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
    }

    private fun kmh(metersPerSecond: Float): Int = (metersPerSecond * 3.6f).roundToInt()

    private fun detectedUsbPhone(ctx: Context): UsbDevice? =
        mainViewModel.usbDevices.value.orEmpty().map { it.wrappedDevice }
            .firstOrNull { UsbDeviceCompat.isConnectable(ctx, it) }

    // The product name is API 21; older units say "Phone detected" instead.
    private fun phoneName(device: UsbDevice): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.productName?.takeIf { it.isNotBlank() } else null

    private fun isNightActive(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // A theme change recreates the activity, so the palette can be kept for the life of the view.
    private fun homePalette(ctx: Context): HomeUiHelper.Palette {
        val appSettings = App.provide(ctx).settings
        // The opacity is changed on another screen, so a palette cached before it is stale.
        cachedPalette?.takeIf { it.surfaceOpacityPercent == appSettings.homeSurfaceOpacityPercent }?.let { return it }
        val mode = HomeThemePolicy.resolve(appSettings.appTheme, appSettings.useExtremeDarkMode, isNightActive())
        return HomeUiHelper.palette(ctx, mode, appSettings.homeSurfaceOpacityPercent).also { cachedPalette = it }
    }

    private fun updateLauncherViews() {
        val ctx = context ?: return
        val root = view ?: return
        val appSettings = App.provide(ctx).settings
        val isLauncherActive = appSettings.isCarLauncherActive
        val shouldShowExit = CarLauncherManager.shouldShowExitButton(
            isCarLauncherEnabled = appSettings.enableCarLauncher,
            isDefaultLauncher = CarLauncherManager.isDefaultLauncher(ctx)
        )
        exitButton.visibility = if (shouldShowExit) View.VISIBLE else View.GONE
        HomeUiHelper.applyFavoritesVisibility(root, isLauncherActive, appSettings.showHomeFavoriteApps)
        if (isLauncherActive) {
            AppDrawerFragment.preload(ctx)
            if (appSettings.showHomeFavoriteApps) loadFavorites()
        }
        applyHomeLayout(root, appSettings, isLauncherActive)
        renderWidgets()
        scheduleCardFit(root)
    }

    /**
     * After the layout pass that sizes the card, not inside it: hiding a line asks for another
     * one. Renders come in bursts (every step, every second of the countdown), so one fit waits
     * at a time.
     */
    private fun scheduleCardFit(root: View) {
        if (cardFitPending) return
        val card = root.findViewById<View>(R.id.home_primary_card) ?: return
        cardFitPending = true
        card.doOnLayout {
            root.post {
                cardFitPending = false
                fitCardText(root)
            }
        }
    }

    /**
     * The lines of the main button that step aside, in [HomeCardFitPolicy]'s order, when the card
     * is too short for all of it. Otherwise it is the button itself that gets cut, which is what
     * happened on a 720x360dp screen once the page dots took their row.
     */
    private fun fitCardText(root: View) {
        if (!isAdded || view !== root) return
        val card = root.findViewById<View>(R.id.home_primary_card) ?: return
        if (card.height <= 0) return
        val pushBefore = root.findViewById<View>(R.id.home_primary_header)?.paddingTop ?: 0
        setNoticePush(root, statusNoticesPush(root))
        val stepShown = decision?.stage != null
        val optional = HomeCardFitPolicy.stepAsideOrder(stepShown).mapNotNull { line ->
            when (line) {
                // Only when the card asked for it: unlike the others it is not always meant to be there.
                HomeCardFitPolicy.Line.DETAIL -> root.findViewById<TextView>(R.id.home_primary_detail)
                    ?.takeIf { !it.text.isNullOrEmpty() }
                HomeCardFitPolicy.Line.SUBTITLE -> root.findViewById<View>(R.id.home_primary_subtitle)
                HomeCardFitPolicy.Line.KICKER -> root.findViewById<View>(R.id.home_primary_kicker)
                HomeCardFitPolicy.Line.TITLE -> root.findViewById<View>(R.id.home_primary_title)
            }
        }
        // A line the previous status hid has to be measured again under this one.
        restoreCardLines(root, stepShown)
        val dropped = optional.takeWhile { cardMinimumHeight(card) > card.height }
        dropped.forEach { it.visibility = View.GONE }
        if (dropped.isNotEmpty()) {
            AppLog.i("HomeFragment: the main button is ${card.height}px tall, hiding ${dropped.size} line(s) so its action fits")
        }
        // On a short card the push can cost more than the lines that stepped aside give back,
        // and then it is the action that gets cut: the push gives that part up.
        val push = root.findViewById<View>(R.id.home_primary_header)?.paddingTop ?: 0
        val kept = if (push > 0) NoticeClearancePolicy.withinCard(push, cardMinimumHeight(card) - card.height) else push
        setNoticePush(root, kept)
        if (kept != pushBefore) {
            AppLog.i(
                if (kept > 0) "HomeFragment: moving the main button's top corner ${kept}px down, clear of the status notices"
                else "HomeFragment: the main button's top corner is back where it was"
            )
        }
    }

    /**
     * How far to move the card's top corner (the countdown, or the date, time and speed) down from
     * under MainActivity's status notices, when the card is narrow enough to put it there: see
     * [NoticeClearancePolicy]. The step-aside fit applies it before measuring, so the lines under
     * it make room in the same layout, and takes back whatever the action would lose to it.
     */
    private fun statusNoticesPush(root: View): Int {
        val header = root.findViewById<View>(R.id.home_primary_header) ?: return 0
        val content = root.findViewById<View>(R.id.home_primary_content) ?: return 0
        if (!content.isShown) return 0
        val current = header.paddingTop
        val notices = activity?.findViewById<ViewGroup>(R.id.status_notices)?.takeIf { it.isShown }
        val noticeBoxes = notices?.let { group ->
            (0 until group.childCount).map { group.getChildAt(it) }
                .filter { it.isShown && it.height > 0 }
                .map { boxIn(root, it) }
        }.orEmpty()
        // Where the corner sits with no push, so the answer does not depend on the last one.
        val corner = listOfNotNull(root.findViewById<View>(R.id.home_countdown), root.findViewById(R.id.home_info))
            .filter { it.isShown && it.height > 0 }
            .map { boxIn(root, it).let { b -> HomePagesHintPolicy.Box(b.left, b.top - current, b.right, b.bottom - current) } }
        val limit = boxIn(root, content).bottom - content.paddingBottom
        return NoticeClearancePolicy.push(noticeBoxes, corner, resources.getDimensionPixelSize(R.dimen.home_gap), limit)
    }

    /** The header row takes the push as top padding and the date block, which floats over it, as margin. */
    private fun setNoticePush(root: View, push: Int) {
        val header = root.findViewById<View>(R.id.home_primary_header) ?: return
        if (header.paddingTop == push) return
        header.setPadding(header.paddingLeft, push, header.paddingRight, header.paddingBottom)
        val info = root.findViewById<View>(R.id.home_info)
        (info?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.topMargin = resources.getDimensionPixelSize(R.dimen.home_card_padding_v) + push
            info.layoutParams = params
        }
    }

    private fun restoreCardLines(root: View, stepShown: Boolean) {
        listOf(R.id.home_primary_subtitle, R.id.home_primary_kicker, R.id.home_primary_title).forEach {
            root.findViewById<View>(it)?.visibility = View.VISIBLE
        }
        root.findViewById<TextView>(R.id.home_primary_detail)?.let {
            it.visibility = if (stepShown && !it.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        }
    }

    /**
     * How the home arranges itself, in one place:
     *
     * - A small or narrow screen used as the launcher has no room for the main button and the
     *   favorite apps together, so the main button keeps the first page and the favorite apps and
     *   widgets get the next one.
     * - A very wide unit (8:3, not 16:9) gets two columns: the card on the driver's side and the
     *   favorites and widgets beside it, so the width is used instead of left empty under a wide
     *   card.
     * - Everything else keeps the card on top and the favorites under it.
     *
     * Any widget page the driver added follows those. The pages are rebuilt here, so this runs
     * before the widget row and the main button are filled in.
     */
    private fun applyHomeLayout(root: View, appSettings: Settings, isLauncherActive: Boolean) {
        val card = root.findViewById<View>(R.id.home_primary_card) ?: return
        val area = root.findViewById<View>(R.id.home_favorites_area) ?: return
        val main = root.findViewById<LinearLayout>(R.id.home_page_main) ?: return
        val config = resources.configuration

        val small = config.screenHeightDp < COMPACT_HEIGHT_DP || config.screenWidthDp < COMPACT_WIDTH_DP
        val compact = isLauncherActive && small
        val wide = isLauncherActive && HomePagesPolicy.sideBySide(
            config.orientation == Configuration.ORIENTATION_LANDSCAPE,
            config.screenWidthDp,
            config.screenHeightDp
        )
        compactHome = compact
        ultrawideHome = wide

        val orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (main.orientation != orientation) main.orientation = orientation

        renderPages(root, appSettings, isLauncherActive, area, main)

        (card.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val width = if (wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            val height = if (wide) ViewGroup.LayoutParams.MATCH_PARENT else 0
            val weight = if (wide) 1.6f else 1f
            if (params.width != width || params.height != height || params.weight != weight) {
                params.width = width
                params.height = height
                params.weight = weight
                card.layoutParams = params
            }
        }

        (area.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val width = if (wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            val height = when {
                wide -> ViewGroup.LayoutParams.MATCH_PARENT
                compact -> 0
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            val weight = if (wide || compact) 1f else 0f
            if (params.width != width || params.height != height || params.weight != weight) {
                params.width = width
                params.height = height
                params.weight = weight
                area.layoutParams = params
            }
        }
    }

    /**
     * Rebuilds every page after the main one. The favorite apps area moves to a page of its own on
     * the compact home and back under the main button otherwise; widget pages are built fresh, the
     * same way the widget row is, so a widget that was just added and one that was there look alike.
     */
    private fun renderPages(root: View, appSettings: Settings, isLauncherActive: Boolean, area: View, main: LinearLayout) {
        val pager = root.findViewById<HomePager>(R.id.home_pager) ?: return
        // With the widgets switch off the stored pages are left as they are, not even pruned.
        val widgetsShown = appSettings.showHomeWidgets
        val stored = if (isLauncherActive && widgetsShown) prunePages(root.context, appSettings.homeWidgetPages) else emptyList()
        val pages = if (isLauncherActive) {
            HomePagesPolicy.pages(compactHome, stored.size, widgetRowOnItsOwnPage(appSettings), widgetsShown)
        } else {
            HomePagesPolicy.pages(compact = false, widgetPages = 0)
        }

        (area.parent as? ViewGroup)?.removeView(area)
        while (pager.childCount > 1) pager.removeViewAt(pager.childCount - 1)

        val palette = homePalette(root.context)
        var favoritesPlaced = false
        for (page in pages.drop(1)) {
            when (page.kind) {
                HomePagesPolicy.Kind.APPS -> {
                    pager.addView(appsPage(root.context, area))
                    favoritesPlaced = true
                }
                HomePagesPolicy.Kind.WIDGET_ROW ->
                    pager.addView(widgetRowPage(root.context, appSettings.homeWidgets, palette))
                HomePagesPolicy.Kind.WIDGETS ->
                    pager.addView(widgetPage(root.context, stored[page.widgetPage], page.widgetPage, palette))
                HomePagesPolicy.Kind.HOME -> Unit
            }
        }
        if (!favoritesPlaced) {
            main.addView(
                area,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        if (pages.size != pageCount) AppLog.i("HomeFragment: the home has ${pages.size} page(s), compact=$compactHome")
        pageCount = pages.size
        pager.showPage(pager.currentPage, animate = false)
        renderDots()
    }

    private fun widgetRowOnItsOwnPage(appSettings: Settings): Boolean =
        widgetRowOverflows && !ultrawideHome && appSettings.showHomeWidgets && appSettings.homeWidgets.isNotEmpty()

    private fun appsPage(ctx: Context, area: View): View = LinearLayout(ctx).apply {
        id = R.id.home_page_apps
        orientation = LinearLayout.VERTICAL
        setOnLongClickListener { showPageMenu(widgetPage = null) }
        addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * One page of widgets: a grid of equal cells, two across on a landscape screen and one on a
     * portrait one, with an "Add a widget" cell while the page has room. Below Android 5.0 there
     * are no widgets to add, so a page stored on a newer unit only offers to be removed.
     */
    private fun widgetPage(ctx: Context, ids: List<Int>, index: Int, palette: HomeUiHelper.Palette): View =
        widgetGrid(ctx, ids, index, ids.size < HomePagesPolicy.MAX_WIDGETS_PER_PAGE, palette)

    /** The row under the favorites, on its own page because this screen has no room for it there. */
    private fun widgetRowPage(ctx: Context, ids: List<Int>, palette: HomeUiHelper.Palette): View =
        widgetGrid(ctx, ids, pageIndex = null, roomForMore = ids.size < HomeWidgetsPolicy.MAX_WIDGETS, palette = palette)

    /**
     * @param pageIndex the added page this is, or null for the widget row's page; it decides where
     *   a new widget goes and whether the page can be removed.
     */
    private fun widgetGrid(
        ctx: Context,
        ids: List<Int>,
        pageIndex: Int?,
        roomForMore: Boolean,
        palette: HomeUiHelper.Palette
    ): View {
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            dividerDrawable = ContextCompat.getDrawable(ctx, R.drawable.home_gap)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            setOnLongClickListener { showPageMenu(widgetPage = pageIndex) }
        }
        val cells = ids.mapNotNull { pageWidgetCell(ctx, page, it, pageIndex, palette) }.toMutableList()
        if (HomeWidgets.supported() && roomForMore) {
            val add = LayoutInflater.from(ctx).inflate(R.layout.item_home_widget_add, page, false)
            add.setOnClickListener { pickWidget(page = pageIndex) }
            add.setOnLongClickListener { showPageMenu(widgetPage = pageIndex) }
            HomeUiHelper.paintWidgetAddTile(ctx, add as ViewGroup, palette)
            cells.add(add)
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var next = 0
        for (count in HomePagesPolicy.rows(cells.size, HomePagesPolicy.columns(landscape))) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                dividerDrawable = ContextCompat.getDrawable(ctx, R.drawable.home_gap)
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            }
            repeat(count) {
                row.addView(cells[next++], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            page.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return page
    }

    /** A widget filling its cell, told the cell's size once it has one so it can reflow into it. */
    private fun pageWidgetCell(
        ctx: Context,
        parent: ViewGroup,
        appWidgetId: Int,
        pageIndex: Int?,
        palette: HomeUiHelper.Palette
    ): View? {
        val hosted = HomeWidgets.view(ctx, appWidgetId) ?: return null
        val frame = LayoutInflater.from(ctx).inflate(R.layout.item_home_widget, parent, false) as ViewGroup
        frame.addView(hosted, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        HomeUiHelper.paintWidgetSlot(ctx, frame, palette)
        frame.setOnLongClickListener { showWidgetMenu(appWidgetId, pageIndex) }
        (hosted as? HomeWidgetHostView)?.onLongPress = { showWidgetMenu(appWidgetId, pageIndex) }
        val padding = ctx.resources.getDimensionPixelSize(R.dimen.home_widget_padding)
        frame.doOnLayout { HomeWidgets.tellSize(hosted, it.width - padding * 2, it.height - padding * 2) }
        return frame
    }

    /** Drops the widgets whose provider is gone from the widget pages, keeping the pages. */
    private fun prunePages(ctx: Context, stored: List<List<Int>>): List<List<Int>> {
        // Below Android 5.0 nothing can be looked up, and a page stored elsewhere is left alone.
        if (!HomeWidgets.supported()) return stored
        val gone = HomePagesPolicy.allWidgets(stored).filterNot { HomeWidgets.isAlive(ctx, it) }
        if (gone.isEmpty()) return stored
        gone.forEach { appWidgetId ->
            AppLog.i("HomeFragment: widget $appWidgetId on a home page is no longer installed, dropping it")
            HomeWidgets.forget(ctx, appWidgetId)
        }
        val pruned = gone.fold(stored) { pages, appWidgetId -> HomePagesPolicy.removeWidget(pages, appWidgetId) }
        App.provide(ctx).settings.homeWidgetPages = pruned
        return pruned
    }

    /** The dots under the pages: one per page, the current one longer, each one a way there. */
    private fun renderDots() {
        val root = view ?: return
        val ctx = root.context
        val dots = root.findViewById<LinearLayout>(R.id.home_page_dots) ?: return
        val pager = root.findViewById<HomePager>(R.id.home_pager) ?: return
        val count = pager.childCount
        if (count <= 1) {
            dots.removeAllViews()
            dots.visibility = View.GONE
            return
        }
        dots.visibility = View.VISIBLE
        val palette = homePalette(ctx)
        val res = ctx.resources
        val dotSize = res.getDimensionPixelSize(R.dimen.home_dot_size)
        val activeWidth = res.getDimensionPixelSize(R.dimen.home_dot_active_width)
        val touchWidth = res.getDimensionPixelSize(R.dimen.home_dot_touch_width)
        // Muted, but still there on the black of the extreme dark look.
        val idle = (palette.textMuted and 0x00FFFFFF) or (DOT_IDLE_ALPHA shl 24)
        dots.removeAllViews()
        for (i in 0 until count) {
            val current = i == pager.currentPage
            val dot = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dotSize / 2f
                    setColor(if (current) palette.text else idle)
                }
            }
            val button = FrameLayout(ctx).apply {
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.home_page_position, i + 1, count)
                setOnClickListener { pager.showPage(i, animate = true) }
                addView(dot, FrameLayout.LayoutParams(if (current) activeWidth else dotSize, dotSize, Gravity.CENTER))
            }
            dots.addView(button, LinearLayout.LayoutParams(touchWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun showFirstPage(animate: Boolean) {
        view?.findViewById<HomePager>(R.id.home_pager)?.showPage(0, animate)
    }

    /**
     * The long press on an empty spot of a page: adding a page while there is room for one, and
     * removing a widget page along with its widgets.
     */
    private fun showPageMenu(widgetPage: Int?): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings
        if (!appSettings.isCarLauncherActive) return false
        val canAdd = HomeWidgets.supported() &&
            HomePagesPolicy.canAddPage(
                compactHome,
                appSettings.homeWidgetPages.size,
                widgetRowOnItsOwnPage(appSettings),
                widgetsShown = appSettings.showHomeWidgets
            )
        val actions = mutableListOf<HomeActionDialog.Action>()
        if (canAdd) {
            actions.add(
                HomeActionDialog.Action(
                    getString(R.string.home_page_add), getString(R.string.home_page_add_detail),
                    R.drawable.ic_home_page_add, destructive = false
                ) { addWidgetPage() }
            )
        }
        if (widgetPage != null) actions.add(removePageAction(widgetPage, otherWidgets = pageWidgetCount(widgetPage)))
        if (actions.isEmpty()) return false
        showActions(getString(R.string.home_pages_title), actions)
        return true
    }

    private fun removePageAction(widgetPage: Int, otherWidgets: Int) = HomeActionDialog.Action(
        getString(R.string.home_page_remove),
        // Said only when it is true, so removing an empty page does not sound like it costs anything.
        if (otherWidgets > 0) getString(R.string.home_page_remove_detail) else null,
        R.drawable.ic_home_page_remove,
        destructive = true
    ) { removeWidgetPage(widgetPage) }

    private fun pageWidgetCount(widgetPage: Int): Int =
        App.provide(requireContext()).settings.homeWidgetPages.getOrNull(widgetPage)?.size ?: 0

    /**
     * The home's own menu rather than a popup: a popup hangs off the view that was pressed, and a
     * page or a widget filling one reaches the bottom of the screen, where the popup only had room
     * for its first row.
     */
    private fun showActions(title: String, actions: List<HomeActionDialog.Action>) {
        val ctx = context ?: return
        actionDialog?.dismiss()
        // The menu stays opaque whatever the home's opacity, or it would vanish over the home.
        val opaque = HomeUiHelper.palette(ctx, homePalette(ctx).mode)
        actionDialog = HomeActionDialog.show(ctx, title, actions, opaque)
    }

    private fun addWidgetPage() {
        val appSettings = App.provide(requireContext()).settings
        appSettings.homeWidgetPages = HomePagesPolicy.addPage(appSettings.homeWidgetPages)
        AppLog.i("HomeFragment: added a home page")
        refreshPages()
        view?.findViewById<HomePager>(R.id.home_pager)?.let { it.showPage(it.childCount - 1, animate = true) }
    }

    private fun removeWidgetPage(index: Int) {
        val ctx = requireContext()
        val appSettings = App.provide(ctx).settings
        val stored = appSettings.homeWidgetPages
        stored.getOrNull(index)?.forEach { HomeWidgets.forget(ctx, it) }
        appSettings.homeWidgetPages = HomePagesPolicy.removePage(stored, index)
        AppLog.i("HomeFragment: removed added home page ${index + 1}")
        refreshPages()
    }

    /** After a page or a widget on one changed: the pages, the widget row and the bar, in order. */
    private fun refreshPages() {
        updateLauncherViews()
        renderHome()
    }

    /** [available] is false for a shortcut that is kept but cannot be read outside the home role. */
    private data class FavoriteApp(val label: CharSequence, val icon: Drawable, val available: Boolean = true)

    /** Loads only the four favorites, off the main thread: the drawer's full list is not needed here. */
    private fun loadFavorites() {
        val ctx = requireContext().applicationContext
        val pm = ctx.packageManager
        val appSettings = App.provide(requireContext()).settings
        val targets = appSettings.homeFavoriteApps
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { targets.map { loadFavorite(ctx, pm, it) } }
            apps.forEachIndexed { slot, app -> bindFavorite(favoriteSlots[slot], app) }
            // An uninstalled app, or a shortcut the app dropped, frees its slot so "Add to
            // favorites" fills the "+" the user sees.
            val gone = targets.indices.filter { targets[it] != FavoriteTarget.Empty && apps[it] == null }
            if (gone.isNotEmpty()) {
                appSettings.homeFavoriteApps = gone.fold(targets) { slots, slot -> FavoriteAppsPolicy.remove(slots, slot) }
            }
        }
    }

    private fun loadFavorite(context: Context, pm: PackageManager, target: FavoriteTarget): FavoriteApp? =
        when (target) {
            is FavoriteTarget.Empty -> null
            is FavoriteTarget.App -> appFavorite(pm, target.packageName)
            is FavoriteTarget.Shortcut -> shortcutFavorite(context, pm, target)
        }

    private fun appFavorite(pm: PackageManager, packageName: String): FavoriteApp? = try {
        val info = pm.getApplicationInfo(packageName, 0)
        FavoriteApp(pm.getApplicationLabel(info), pm.getApplicationIcon(info))
    } catch (e: PackageManager.NameNotFoundException) {
        AppLog.i("HomeFragment: favorite $packageName is no longer installed, showing its slot as free")
        null
    }

    /**
     * A shortcut the app still offers, with its own icon and the app's as the fallback. One that
     * cannot be read right now keeps its slot and shows as its app, dimmed.
     */
    private fun shortcutFavorite(context: Context, pm: PackageManager, target: FavoriteTarget.Shortcut): FavoriteApp? {
        val app = appFavorite(pm, target.packageName)
        val shortcuts = LauncherShortcuts.forPackage(context, target.packageName)
        val name = "${target.packageName}/${target.shortcutId}"
        return when (FavoriteAppsPolicy.shortcutState(app != null, shortcuts?.map { it.shortcutId }, target.shortcutId)) {
            FavoriteAppsPolicy.SlotState.GONE -> {
                AppLog.i("HomeFragment: favorite shortcut $name is gone, showing its slot as free")
                null
            }
            FavoriteAppsPolicy.SlotState.UNAVAILABLE -> {
                AppLog.i("HomeFragment: favorite shortcut $name cannot be read now, keeping its slot")
                app?.copy(available = false)
            }
            FavoriteAppsPolicy.SlotState.SHOWN -> {
                val shortcut = shortcuts?.firstOrNull { it.shortcutId == target.shortcutId } ?: return null
                val icon = LauncherShortcuts.icon(context, target.packageName, target.shortcutId) ?: app?.icon
                    ?: return null
                FavoriteApp(shortcut.label, icon)
            }
        }
    }

    private fun bindFavorite(slotView: View, app: FavoriteApp?) {
        val icon = slotView.findViewById<ImageView>(R.id.favorite_icon)
        val label = slotView.findViewById<TextView>(R.id.favorite_label)
        if (app == null) {
            icon.setImageResource(R.drawable.ic_add_white)
            label.setText(R.string.home_add_favorite)
        } else {
            icon.setImageDrawable(app.icon)
            label.text = app.label
        }
        icon.alpha = if (app == null || app.available) 1f else UNAVAILABLE_FAVORITE_ALPHA
        (slotView as? ViewGroup)?.let { HomeUiHelper.paintFavoriteSlot(slotView.context, it, app != null, homePalette(slotView.context)) }
    }

    /**
     * The widgets under the favorite apps, rebuilt rather than updated: there are at most two, and
     * a rebuild is what tells a widget that was just added from one that was already there.
     */
    private fun renderWidgets() {
        val ctx = context ?: return
        val root = view ?: return
        val scroll = root.findViewById<HorizontalScrollView>(R.id.home_widgets_scroll) ?: return
        val row = root.findViewById<LinearLayout>(R.id.home_widgets_row) ?: return
        row.removeAllViews()
        val appSettings = App.provide(ctx).settings
        // A widget can only be drawn in one place, and on this screen that place is its own page.
        // The widgets switch turns the row off, add tile included.
        if (!appSettings.isCarLauncherActive || !appSettings.showHomeWidgets || widgetRowOnItsOwnPage(appSettings)) {
            scroll.visibility = View.GONE
            return
        }

        val ids = pruneWidgets(ctx, appSettings.homeWidgets)
        val palette = homePalette(ctx)
        // A for loop: forEach here resolved to java.lang.Iterable's, which is API 24.
        for (appWidgetId in ids) {
            widgetTile(ctx, row, appWidgetId, palette)?.let { row.addView(it) }
        }
        // Android 5.0 is where the system lets an app ask for a widget at all.
        if (HomeWidgets.supported() && ids.size < HomeWidgetsPolicy.MAX_WIDGETS) {
            val addTile = LayoutInflater.from(ctx).inflate(R.layout.item_home_widget_add, row, false)
            addTile.setOnClickListener { pickWidget() }
            HomeUiHelper.paintWidgetAddTile(ctx, addTile as ViewGroup, palette)
            row.addView(addTile)
        }
        if (row.childCount == 0) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        val addTileHeight = ctx.resources.getDimensionPixelSize(R.dimen.home_favorites_height)
        val tallest = ids.maxOfOrNull { HomeWidgets.minHeightPx(ctx, it) } ?: 0
        // One frame later: changing the row's height or hiding it inside the layout pass does not
        // take, and the main button is left with the space the row was not going to use.
        root.post {
            if (!isAdded || view !== root) return@post
            fitWidgetStrip(root, maxOf(tallest, addTileHeight))
        }
    }

    /** @return the frame that holds the widget, or null when its provider cannot draw any more. */
    private fun widgetTile(
        ctx: Context,
        parent: ViewGroup,
        appWidgetId: Int,
        palette: HomeUiHelper.Palette
    ): View? {
        val hosted = HomeWidgets.view(ctx, appWidgetId) ?: return null
        val frame = LayoutInflater.from(ctx).inflate(R.layout.item_home_widget, parent, false)
        (frame as ViewGroup).addView(
            hosted,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        HomeUiHelper.paintWidgetSlot(ctx, frame, palette)
        // The width the widget asks for is the smallest it draws at. The row scrolls rather than
        // squeezing it, and a narrow widget still gets a tile sized square. On the compact home the
        // row is the whole width, so the widget gives up the little the "+" needs and reflows.
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.home_gap)
        val addTileWidth = ctx.resources.getDimensionPixelSize(R.dimen.home_favorites_height)
        val ceiling = if (compactHome) {
            ctx.resources.displayMetrics.widthPixels - gap * 3 - addTileWidth
        } else {
            Int.MAX_VALUE
        }
        val width = minOf(HomeWidgets.minWidthPx(ctx, appWidgetId), ceiling)
            .coerceAtLeast(addTileWidth)
        frame.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        frame.setOnLongClickListener { showWidgetMenu(appWidgetId, pageIndex = null) }
        (hosted as? HomeWidgetHostView)?.onLongPress = { showWidgetMenu(appWidgetId, pageIndex = null) }
        return frame
    }

    /**
     * Gives the widget row what the main button can spare and no more. Without this a tall widget
     * pushed the main button's action off the bottom of a short screen, the same way the driver
     * bar used to lose its Exit tile.
     */
    private fun fitWidgetStrip(root: View, preferredPx: Int) {
        val ctx = root.context
        val scroll = root.findViewById<View>(R.id.home_widgets_scroll) ?: return
        val area = root.findViewById<View>(R.id.home_favorites_area) ?: return
        val appsRow = root.findViewById<View>(R.id.home_favorites) ?: return
        val card = root.findViewById<View>(R.id.home_primary_card) ?: return
        val column = area.parent as? View ?: return
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.home_gap)

        // A portrait home stacks the driver bar in this same column, so its height is not room.
        val bar = root.findViewById<View>(R.id.home_driver_bar)
        val below = if (bar != null && bar.parent === column && bar.visibility == View.VISIBLE) {
            bar.height + gap
        } else 0
        val columnHeight = column.height - column.paddingTop - column.paddingBottom - below
        val cardMin = cardMinimumHeight(card)
        val compact = compactHome
        // Wide home: the card sits beside this column, and on the compact home it is on a page of
        // its own, so in both its height is not part of the room.
        val room = if (ultrawideHome || compact) {
            columnHeight - appsRow.height - gap
        } else {
            WidgetStripPolicy.roomFor(columnHeight, cardMin, appsRow.height, gap)
        }
        val minPx = ctx.resources.getDimensionPixelSize(R.dimen.home_widget_strip_min)
        val height = if (compact || ultrawideHome) {
            // No main button to protect: the widgets take everything the favorite apps row leaves,
            // so the home is not a small strip with a lot of empty screen under it.
            room.coerceAtLeast(minPx)
        } else {
            WidgetStripPolicy.height(
                roomPx = room,
                minPx = minPx,
                maxPx = ctx.resources.getDimensionPixelSize(R.dimen.home_widget_strip_max),
                tallestWidgetPx = preferredPx
            )
        }
        if (height <= 0) {
            scroll.visibility = View.GONE
            val hasWidgets = App.provide(ctx).settings.homeWidgets.isNotEmpty()
            if (hasWidgets && !compact && !ultrawideHome && !widgetRowOverflows) {
                AppLog.i(
                    "HomeFragment: no room for the widget row beside the main button " +
                        "(room=${room}px, card needs ${cardMin}px), moving it to its own page"
                )
                widgetRowOverflows = true
                refreshPages()
            }
            return
        }
        AppLog.i("HomeFragment: the widget row is ${height}px tall, out of ${room}px of room")
        if (scroll.height != height) {
            scroll.layoutParams = (scroll.layoutParams ?: return).also { it.height = height }
            scroll.requestLayout()
        }
        tellWidgetsTheirSize(root, height)
    }

    /**
     * The height the main button cannot give up: its content measured with no height limit, so the
     * answer does not depend on how much room the card happens to have right now. Measuring the
     * children as they currently sit was the bug: once the card was squeezed they reported the
     * squeezed heights, the arithmetic believed it, and it kept the squeeze.
     */
    private fun cardMinimumHeight(card: View): Int {
        val content = card.findViewById<View>(R.id.home_primary_content) ?: return 0
        val width = if (content.width > 0) content.width else card.width
        if (width <= 0) return 0
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return content.measuredHeight
    }

    /** Tells every hosted widget the box it ended up with, so one that reflows lays itself out. */
    private fun tellWidgetsTheirSize(root: View, height: Int) {
        val row = root.findViewById<ViewGroup>(R.id.home_widgets_row) ?: return
        val padding = root.resources.getDimensionPixelSize(R.dimen.home_widget_padding)
        // A scroll view measures its child with AT_MOST, so a row that asks for match_parent does
        // not get the viewport's height. Every box is told its size instead.
        setViewHeight(row, height)
        for (i in 0 until row.childCount) {
            val frame = row.getChildAt(i) as? ViewGroup ?: continue
            setViewHeight(frame, height)
            val hosted = frame.getChildAt(0) as? AppWidgetHostView ?: continue
            HomeWidgets.tellSize(hosted, (frame.layoutParams?.width ?: 0) - padding * 2, height - padding * 2)
        }
        // The "+" keeps a tile shape: on the compact home the row is tall, and a stretched dashed
        // rectangle with its label in the middle reads as a mistake.
        row.findViewById<View>(R.id.widget_add_tile)?.let { add ->
            if (add.width <= 0) return@let
            setViewHeight(add, minOf(height, add.width * 3 / 2))
        }
    }

    private fun setViewHeight(view: View, height: Int) {
        val params = view.layoutParams ?: return
        if (params.height == height) return
        params.height = height
        view.layoutParams = params
    }

    /** Drops the widgets whose provider is gone, so an uninstalled widget frees its place. */
    private fun pruneWidgets(ctx: Context, stored: List<Int>): List<Int> {
        val alive = stored.filter { appWidgetId ->
            val isAlive = HomeWidgets.isAlive(ctx, appWidgetId)
            if (!isAlive) {
                AppLog.i("HomeFragment: widget $appWidgetId is no longer installed, dropping it")
                HomeWidgets.forget(ctx, appWidgetId)
            }
            isAlive
        }
        if (alive.size != stored.size) App.provide(ctx).settings.homeWidgets = alive
        return alive
    }

    /** @param pageIndex the widget page the widget is on, or null for the row under the favorites. */
    private fun showWidgetMenu(appWidgetId: Int, pageIndex: Int?): Boolean {
        val ctx = requireContext()
        val actions = mutableListOf(
            HomeActionDialog.Action(
                getString(R.string.home_widget_remove), null, R.drawable.ic_home_delete, destructive = true
            ) { removeWidget(appWidgetId, pageIndex) }
        )
        if (pageIndex != null) actions.add(removePageAction(pageIndex, otherWidgets = pageWidgetCount(pageIndex) - 1))
        showActions(HomeWidgets.label(ctx, appWidgetId) ?: getString(R.string.home_widget_fallback_title), actions)
        return true
    }

    private fun removeWidget(appWidgetId: Int, pageIndex: Int?) {
        val ctx = requireContext()
        val appSettings = App.provide(ctx).settings
        HomeWidgets.forget(ctx, appWidgetId)
        if (pageIndex == null) {
            appSettings.homeWidgets = HomeWidgetsPolicy.remove(appSettings.homeWidgets, appWidgetId)
            if (widgetRowOverflows) refreshPages() else renderWidgets()
        } else {
            appSettings.homeWidgetPages = HomePagesPolicy.removeWidget(appSettings.homeWidgetPages, appWidgetId)
            refreshPages()
        }
    }

    /**
     * The list of widgets the unit has, named the way their own apps name them.
     *
     * @param page the widget page it goes on, or null for the row under the favorites.
     */
    private fun pickWidget(page: Int? = null) {
        val ctx = requireContext()
        val providers = HomeWidgets.providers(ctx)
        if (providers.isEmpty()) {
            ToastUtils.showToast(ctx, R.string.home_widgets_none, Toast.LENGTH_LONG, force = true)
            return
        }
        val adapter = object : ArrayAdapter<HomeWidgets.Provider>(
            ctx, R.layout.item_widget_option, providers
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.item_widget_option, parent, false)
                val provider = providers[position]
                view.findViewById<TextView>(R.id.widget_option_label).text = provider.label
                view.findViewById<TextView>(R.id.widget_option_app).text = provider.appLabel
                view.findViewById<ImageView>(R.id.widget_option_icon)?.setImageDrawable(provider.icon)
                return view
            }
        }
        MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle(R.string.home_widget_pick_title)
            .setAdapter(adapter) { _, which -> addWidget(providers[which].info.provider, page) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Asks the system for the widget the user picked. From Android 5.0 the app may not bind one on
     * its own: when the system says no, it is the user who is asked, and the id waits for them.
     */
    private fun addWidget(provider: ComponentName, page: Int?) {
        val ctx = requireContext()
        val appWidgetId = HomeWidgets.allocateId(ctx)
        if (HomeWidgets.tryBind(ctx, appWidgetId, provider)) {
            configureOrPlaceWidget(appWidgetId, page)
            return
        }
        pendingWidgetId = appWidgetId
        pendingWidgetPage = page
        widgetBindLauncher.launch(HomeWidgets.bindRequest(appWidgetId, provider))
    }

    /** The widget the answer is about, and where it goes; the answer names the id when it can. */
    private fun takePendingWidget(data: Intent?): Pair<Int, Int?> {
        val returned = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val pending = (if (returned >= 0) returned else pendingWidgetId) to pendingWidgetPage
        pendingWidgetId = -1
        pendingWidgetPage = null
        return pending
    }

    private fun releasePendingWidget(appWidgetId: Int, why: String) {
        val ctx = context ?: return
        if (appWidgetId < 0) {
            // No widget was waiting. The usual case is a setup activity its app did not export:
            // the system refuses the launch and still answers it with a cancel, which only arrives
            // once the home is back on screen. By then the widget went through the host and was
            // placed (configureOrPlaceWidget), so there is nothing to release.
            AppLog.i("HomeFragment: a widget answer came back with no widget waiting ($why), nothing to release")
            return
        }
        HomeWidgets.forget(ctx, appWidgetId)
        AppLog.i("HomeFragment: widget $appWidgetId $why, releasing its id")
    }

    /**
     * A widget that declares a setup activity shows nothing useful until it ran, so it is placed
     * only once the user finished it, the way every launcher does.
     */
    private fun configureOrPlaceWidget(appWidgetId: Int, page: Int?) {
        val ctx = context ?: return
        val configure = HomeWidgets.configureActivity(ctx, appWidgetId)
        if (configure == null) {
            placeWidget(appWidgetId, page)
            return
        }
        pendingWidgetId = appWidgetId
        pendingWidgetPage = page
        try {
            widgetConfigureLauncher.launch(HomeWidgets.configureRequest(appWidgetId, configure))
        } catch (e: Exception) {
            // Not exported to other apps: only the host may start it, and its answer does not come
            // back here, so the widget is kept and shows whatever the setup leaves it with.
            AppLog.i("HomeFragment: widget $appWidgetId set up through the host: ${e.message}")
            pendingWidgetId = -1
            pendingWidgetPage = null
            val owner = activity
            if (owner != null && HomeWidgets.supported()) {
                try {
                    HomeWidgets.configureThroughHost(ctx, owner, appWidgetId)
                } catch (hostError: Exception) {
                    AppLog.w("HomeFragment: widget $appWidgetId could not be set up: ${hostError.message}")
                }
            }
            placeWidget(appWidgetId, page)
        }
    }

    private fun placeWidget(appWidgetId: Int, page: Int?) {
        val ctx = requireContext()
        val appSettings = App.provide(ctx).settings
        if (page != null) {
            val pages = HomePagesPolicy.addWidget(appSettings.homeWidgetPages, page, appWidgetId)
            if (pages == null) {
                HomeWidgets.forget(ctx, appWidgetId)
                ToastUtils.showToast(ctx, R.string.home_widgets_full, Toast.LENGTH_LONG, force = true)
                return
            }
            appSettings.homeWidgetPages = pages
            refreshPages()
            return
        }
        val updated = HomeWidgetsPolicy.add(appSettings.homeWidgets, appWidgetId)
        if (updated == null) {
            HomeWidgets.forget(ctx, appWidgetId)
            ToastUtils.showToast(ctx, R.string.home_widgets_full, Toast.LENGTH_LONG, force = true)
            return
        }
        appSettings.homeWidgets = updated
        if (widgetRowOverflows) refreshPages() else renderWidgets()
    }

    private fun onFavoriteClicked(slot: Int) {
        val target = App.provide(requireContext()).settings.homeFavoriteApps[slot]
        val launched = when (target) {
            is FavoriteTarget.Empty -> false
            is FavoriteTarget.App -> AppDrawerFragment.launchPackage(requireContext(), target.packageName)
            is FavoriteTarget.Shortcut ->
                LauncherShortcuts.launch(requireContext(), target.packageName, target.shortcutId) ||
                    // Unreadable outside the home role: the app is the closest thing that still opens.
                    (!LauncherShortcuts.canRead(requireContext()) &&
                        AppDrawerFragment.launchPackage(requireContext(), target.packageName))
        }
        if (!launched) openAppDrawer(favoriteSlot = slot)
    }

    private fun showFavoriteMenu(slot: Int, anchor: View): Boolean {
        val appSettings = App.provide(requireContext()).settings
        if (appSettings.homeFavoriteApps[slot] == FavoriteTarget.Empty) return false
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.home_favorite_change).setOnMenuItemClickListener {
                openAppDrawer(favoriteSlot = slot)
                true
            }
            menu.add(R.string.remove).setOnMenuItemClickListener {
                appSettings.homeFavoriteApps = FavoriteAppsPolicy.remove(appSettings.homeFavoriteApps, slot)
                loadFavorites()
                true
            }
            show()
        }
        return true
    }

    private fun openAppDrawer(favoriteSlot: Int? = null) {
        val controller = findNavController()
        if (controller.currentDestination?.id == R.id.homeFragment) {
            controller.navigate(
                R.id.action_homeFragment_to_appDrawerFragment,
                favoriteSlot?.let { AppDrawerFragment.pickFavoriteArgs(it) }
            )
        }
    }

    private fun openProjection() {
        val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
        aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        startActivity(com.andrerinas.openheadunit.app.AppUiEntry.continuation(aapIntent))
    }

    private fun startSelfModeInternal(needsDummyVpn: Boolean, ticket: Long) {
        if (!commManager.acceptsAppConnectionTicket(ticket)) return
        val intent = AapService.operationIntent(requireContext(), ticket)
        intent.action = AapService.ACTION_START_SELF_MODE
        intent.putExtra(AapService.EXTRA_SELF_MODE_VPN, needsDummyVpn)
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
    }

    private fun startSelfMode(ticket: Long = commManager.appConnectionTicket()) {
        if (!commManager.acceptsAppConnectionTicket(ticket)) return
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else null

        val path = SelfLauncherManager.installedPath(requireContext())
        val offline = activeNetwork == null

        val needsDummyVpn = SelfLaunchRoutePolicy.needsDummyVpn(path, offline, VpnControl.isVpnAvailable())
        if (needsDummyVpn) {
            AppLog.i("Device is offline. Preparing Dummy VPN for Self Mode.")
            val vpnIntent = VpnControl.consentIntent(requireContext())
            if (vpnIntent != null) {
                pendingSelfModeVpn = true
                pendingSelfModeTicket = ticket
                vpnPermissionLauncher.launch(vpnIntent)
                return
            } else {
                AppLog.i("VPN permission already granted. Self Mode will prepare it after launch admission.")
            }
        } else if (offline) {
            if (path == SelfLaunchPath.HEADUNIT_SERVER) {
                AppLog.i("HomeFragment: Device is offline; Android Auto 17.4+ connects over 127.0.0.1:5277, so no dummy VPN.")
            } else {
                AppLog.i("Device is offline and VPN is not available in this build. Self Mode may fail.")
            }
        }
        startSelfModeInternal(needsDummyVpn, ticket)
    }

    /**
     * Tries to start an auto-reconnect to the last session.
     *
     * @return `true` if a connection attempt was actually dispatched (so the
     *   caller should surface the pill), `false` if nothing was started (e.g.
     *   Native AA mode, no last session, missing USB device or permission).
     *   Returning a flag prevents the pill from being shown for 30 s when no
     *   work was queued.
     */
    private fun attemptAutoConnect(ticket: Long): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings

        // [FIX] Skip manual WiFi connection if Native AA is selected.
        // Native AA handles its own handshake via Bluetooth/P2P.
        if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE) {
            AppLog.i("HomeFragment: Native AA mode active. Skipping manual auto-connect attempt.")
            return false
        }

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected || !commManager.acceptsAppRunTicket(ticket)) {
            return false
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return false
        }

        return when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                if (appSettings.wifiConnectionMode == WifiLauncherMode.AUTO) {
                    val ip = appSettings.lastConnectionIp
                    if (ip.isNotEmpty()) {
                        AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                        ToastUtils.showToast(ctx, getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT)
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (commManager.acceptsAppRunTicket(ticket)) {
                                App.provide(ctx).commManager.connect(ip, 5277, ticket = ticket)
                            }
                        }
                        ContextCompat.startForegroundService(ctx, AapService.operationIntent(ctx, ticket).apply {
                            action = AapService.ACTION_CONNECT_SOCKET
                        })
                        true
                    } else false
                } else {
                    AppLog.i("Auto-connect: Last session was WiFi, but connection mode is not Headunit Server. Skipping active connect.")
                    false
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = UsbDeviceCompat.usbManager(requireContext())
                    val matchingDevice = usbManager?.deviceList?.values?.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (usbManager != null && matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        ToastUtils.showToast(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT)
                        ContextCompat.startForegroundService(requireContext(), AapService.operationIntent(requireContext(), ticket).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                        true
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                        false
                    }
                } else false
            }
            Settings.CONNECTION_TYPE_NEARBY -> {
                AppLog.i("Auto-connect: Last session was via Google Nearby. AapService will handle discovery.")
                // No manual connect(ip) needed, NearbyManager in AapService manages this automatically on start.
                true
            }
            else -> false
        }
    }

    /**
     * @return `true` if a single-USB connection attempt was dispatched to the one device that is
     *   plugged in, `false` if guards (setting disabled, disclaimer pending, already connected)
     *   blocked it or there is not exactly one device to connect to. Same intent as
     *   [attemptAutoConnect].
     */
    private fun attemptSingleUsbAutoConnect(ticket: Long): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected || !commManager.acceptsAppRunTicket(ticket)) return false

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(ctx,
            AapService.operationIntent(ctx, ticket).apply {
                action = AapService.ACTION_CHECK_USB
            })
        // The check is cheap and the service decides, but the full-screen loading overlay is a
        // promise that a phone is there. With nothing plugged in it sat black over the home for
        // its whole 30 s on every launch (#1147).
        val usbManager = UsbDeviceCompat.usbManager(ctx)
        val connectable = usbManager?.deviceList?.values?.count { UsbDeviceCompat.isConnectable(ctx, it) } ?: 0
        val showsOverlay = AutoConnectAttemptPolicy.singleUsbShowsOverlay(connectable)
        if (!showsOverlay) {
            AppLog.i("HomeFragment: single-USB auto-connect found $connectable connectable devices, so no loading screen")
        }
        return showsOverlay
    }

    private fun setupListeners() {
        appsButton.setOnClickListener { openAppDrawer() }
        exitButton.setOnClickListener { exitApp() }
        selfModeTile.setOnClickListener { onSelfModeClicked() }
        usbTile.setOnClickListener { onUsbClicked() }
        wifiTile.setOnClickListener { onWifiClicked() }
        wifiTile.setOnLongClickListener {
            openNetworkList()
            true
        }
        settingsTile.setOnClickListener {
            startActivity(com.andrerinas.openheadunit.app.AppUiEntry.continuation(Intent(requireContext(), SettingsActivity::class.java)))
        }
        primaryAction?.setOnClickListener { onPrimaryActionClicked() }
        primaryCancel?.setOnClickListener { onPrimaryCancelClicked() }
        favoriteSlots.forEachIndexed { slot, slotView ->
            slotView.setOnClickListener { onFavoriteClicked(slot) }
            slotView.setOnLongClickListener { showFavoriteMenu(slot, it) }
        }
    }

    private fun onPrimaryActionClicked() {
        val decision = decision ?: return
        when (decision.status) {
            HomePrimaryPolicy.Status.ACTIVE_SESSION -> openProjection()
            // Ends the wait; the auto-connect that was counting down starts right away.
            HomePrimaryPolicy.Status.RECONNECTING -> reconnectCountdown?.cancel()
            else -> when (decision.primary) {
                HomePrimaryPolicy.Connection.USB -> onUsbClicked()
                HomePrimaryPolicy.Connection.WIFI -> onWifiClicked()
                HomePrimaryPolicy.Connection.SELF -> onSelfModeClicked()
            }
        }
    }

    private fun exitApp() {
        val appSettings = App.provide(requireContext()).settings
        val keepServiceAlive = appSettings.autoStartOnBoot ||
            appSettings.autoStartOnScreenOn ||
            (appSettings.autoStartOnUsb && appSettings.reopenOnReconnection)
        if (keepServiceAlive) {
            val disconnectIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_DISCONNECT
            }
            ContextCompat.startForegroundService(requireContext(), disconnectIntent)
        } else {
            val stopServiceIntent = AapService.stopIntent(requireContext())
            ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
        }
        requireActivity().finishAffinity()
    }

    private fun onSelfModeClicked() {
        if (showsBringUp(HomePrimaryPolicy.Connection.SELF)) return
        if (commManager.isConnected) {
            openProjection()
        } else {
            if (!AppPermissions.isOverlayGranted(requireContext())) {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.overlay_permission_title)
                    .setMessage(R.string.self_mode_overlay_permission_description)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${requireContext().packageName}")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual self mode",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                startSelfMode()
            }
        }
    }

    private fun onUsbClicked() {
        if (showsBringUp(HomePrimaryPolicy.Connection.USB)) return
        // Already connected to Android Auto - just show projection
        if (commManager.isConnected) {
            openProjection()
            return
        }

        AapService.instance?.liftUsbCancel("the USB button was pressed")

        // Get list of Android USB devices
        val usbManager = UsbDeviceCompat.usbManager(requireContext())
        val androidDevices = if (usbManager == null) emptyList() else {
            UsbDeviceDiagnostics.logDeviceList(requireContext(), usbManager, "USB button")
            usbManager.deviceList.values.filter { UsbDeviceCompat.isConnectable(requireContext(), it) }
        }

        // If exactly one device found - auto-connect
        if (usbManager != null && androidDevices.size == 1) {
            val device = UsbDeviceCompat(androidDevices[0])
            AppLog.i("USB button: Single device found - ${device.uniqueName}, auto-connecting")
            (requireActivity() as? MainActivity)?.beginAutoConnect(
                "USB button auto-connect",
                MainActivity.ConnectionUiMode.OVERLAY
            )

            if (device.isInAccessoryMode) {
                ContextCompat.startForegroundService(requireContext(),
                    Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    })
            } else {
                if (usbManager.hasPermission(device.wrappedDevice)) {
                    val usbMode = UsbAccessoryMode(usbManager)
                    val useLibusb = App.provide(requireContext()).settings.useLibusb
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val success = usbMode.connectAndSwitch(device.wrappedDevice, useLibusb)
                        withContext(Dispatchers.Main) {
                            context?.let { ctx ->
                                if (success) {
                                    ToastUtils.showToast(ctx, R.string.switching_to_android_auto, Toast.LENGTH_SHORT)
                                } else {
                                    ToastUtils.showToast(ctx, R.string.switch_failed, Toast.LENGTH_SHORT, force = true)
                                }
                            }
                        }
                    }
                } else {
                    ToastUtils.showToast(requireContext(), R.string.requesting_usb_permission, Toast.LENGTH_SHORT)
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
                    usbManager.requestPermission(
                        device.wrappedDevice,
                        UsbReceiver.createPermissionPendingIntent(requireContext(), commManager.appConnectionTicket())
                    )
                }
            }
        } else {
            // 0 or multiple devices - open the list
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_usbListFragment)
            }
        }
    }

    private fun onWifiClicked() {
        if (showsBringUp(HomePrimaryPolicy.Connection.WIFI)) return
        // With a session up the wireless tile is the way back to it, the same as the USB one. It
        // used to do nothing, and on a page without the main button nothing else led back.
        if (commManager.isConnected) {
            openProjection()
            return
        }
        val mode = App.provide(requireContext()).settings.wifiConnectionMode
        when (mode) {
            WifiLauncherMode.AUTO -> { // Auto (Headunit Server) - One-Shot Scan
                if (commManager.isConnected) {
                    // Already connected
                } else if (AapService.scanningState.value) {
                    ToastUtils.showToast(requireContext(), getString(R.string.already_scanning), Toast.LENGTH_SHORT)
                } else {
                    ToastUtils.showToast(requireContext(), getString(R.string.searching_headunit_server), Toast.LENGTH_SHORT)
                    (requireActivity() as? MainActivity)?.beginAutoConnect(
                        "manual WiFi headunit server scan",
                        MainActivity.ConnectionUiMode.OVERLAY
                    )
                    val intent = Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_START_WIRELESS_SCAN
                    }
                    ContextCompat.startForegroundService(requireContext(), intent)
                }
            }
            WifiLauncherMode.HELPER -> { // Helper (Wireless Launcher)
                if (commManager.isConnected) {
                    // Already connected
                } else {
                    val strategy = App.provide(requireContext()).settings.helperConnectionStrategy
                    if (strategy == HelperStrategy.HEADUNIT_HOTSPOT) {
                        if (!AapService.scanningState.value) {
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "manual WiFi helper scan",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                            val intent = Intent(requireContext(), AapService::class.java).apply {
                                action = AapService.ACTION_START_WIRELESS_SCAN
                            }
                            ContextCompat.startForegroundService(requireContext(), intent)
                        }
                        com.andrerinas.openheadunit.utils.ShareHotspotQrDialog.show(
                            requireContext()
                        )
                    } else if (strategy == HelperStrategy.NEARBY_DEVICES) {
                        // Nearby Devices — show live discovery dialog
                        showNearbyDeviceSelector()
                    } else if (AapService.scanningState.value) {
                        ToastUtils.showToast(requireContext(), getString(R.string.already_searching_phone), Toast.LENGTH_SHORT)
                    } else {
                        ToastUtils.showToast(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT)
                        (requireActivity() as? MainActivity)?.beginAutoConnect(
                            "manual WiFi helper scan",
                            MainActivity.ConnectionUiMode.OVERLAY
                        )
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
            }
            WifiLauncherMode.NATIVE -> { // Native AA
                // A unit whose Bluetooth is an external module has no Android radio to check
                // and no Android device to pick; AapService arms the module route instead.
                val route = NativeAaHandshakeManager.wifiButtonRoute(requireContext())
                if (NativeAaHandshakeManager.transportRoute(requireContext()) == ExternalBtTransportPolicy.Route.HCT) {
                    showManufacturerPhoneSelector()
                } else if (route == ExternalBtTransportPolicy.WifiButton.MODULE) {
                    ToastUtils.showToast(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT)
                    val intent = Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_NATIVE_AA_POKE
                    }
                    ContextCompat.startForegroundService(requireContext(), intent)
                } else if (route == ExternalBtTransportPolicy.WifiButton.REFUSED) {
                    AppLog.i(
                        "HomeFragment: WiFi button refused: route=$route, daemon answer=" +
                            "${ZbtDaemonReachability.cached()} ${ZbtDaemonReachability.answerAgeMs()?.let { it / 1000 }}s old"
                    )
                    ToastUtils.showToast(requireContext(), getString(R.string.native_aa_poke_not_running), Toast.LENGTH_LONG, force = true)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    val appSettings = App.provide(requireContext()).settings
                    val cands = BluetoothHelper.driverCandidates(
                        requireContext(), appSettings.nativePreferredDeviceMac, appSettings.lastConnectedNativeMac
                    )
                    val candidates = cands.offered
                    val connectedMacs = cands.connectedOffered.map { it.address }
                    val hasHistory = appSettings.lastConnectedNativeMac.isNotEmpty() ||
                        appSettings.nativePreferredDeviceMac.isNotEmpty() ||
                        appSettings.nativePokeBtMacs.isNotEmpty()

                    val scope = autoScopeMacs(cands, appSettings)
                    val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
                        preferredMac = appSettings.nativePreferredDeviceMac,
                        lastUsedMac = NativeDriverSelectionPolicy.lastUsedMac(
                            appSettings.lastConnectedNativeMac, appSettings.nativePokeBtMacs
                        ),
                        connectedMacs = connectedMacs,
                        pairedMacs = candidates.map { it.address },
                        scopeMacs = scope
                    )

                    val shouldShow = NativeDriverSelectionPolicy.shouldShowSelector(
                        mode = appSettings.nativeDriverSelectionMode,
                        pairedCount = candidates.size,
                        connectedCount = connectedMacs.size,
                        scopedCount = scope.size,
                        hasHistory = hasHistory
                    )

                    if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.DISABLED) {
                        if (autoTargetMac != null) {
                            val targetDev = cands.deviceFor(autoTargetMac)
                            val devName = targetDev?.name ?: autoTargetMac
                            connectToNativeDevice(autoTargetMac, devName, connectedMacs, explicit = false)
                        } else {
                            // AapService drops a poke with no MAC, so this branch used to be a
                            // toast and nothing else. Ask which phone instead, which is what
                            // this button did before the selector existed.
                            showNativeAaDeviceSelector(autoCountdown = false)
                        }
                    } else if (!shouldShow && autoTargetMac != null) {
                        val targetDev = cands.deviceFor(autoTargetMac)
                        val devName = targetDev?.name ?: autoTargetMac
                        connectToNativeDevice(autoTargetMac, devName, connectedMacs, explicit = false)
                    } else {
                        showNativeAaDeviceSelector(autoCountdown = false)
                    }
                }
            }
            WifiLauncherMode.MANUAL -> { // Manual (0) -> Open List
                val controller = findNavController()
                if (controller.currentDestination?.id == R.id.homeFragment) {
                    controller.navigate(R.id.action_homeFragment_to_networkListFragment)
                }
            }
        }
    }

    private fun openNetworkList() {
        val controller = findNavController()
        if (controller.currentDestination?.id == R.id.homeFragment) {
            controller.navigate(R.id.action_homeFragment_to_networkListFragment)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        val root = requireView()
        val homeSettings = App.provide(requireContext()).settings
        applyLayoutSettings(root, homeSettings)
        updateLauncherViews()
        renderHome()
        startHomeInfo()

        val appSettings = App.provide(requireContext()).settings
        if (requestDriverSelection) {
            requestDriverSelection = false
            if (!ExternalBtTransportPolicy.usesExternalModule(NativeAaHandshakeManager.transportRoute(requireContext()))) {
                showNativeAaDeviceSelector(autoCountdown = false)
            }
        } else if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE && !commManager.isConnected) {
            if (!hasCheckedNativeDriverSelection) checkNativeDriverSelectionOnStartup()
        }

        activity?.let { act ->
            val isDestroyed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                act.isDestroyed
            else
                false

            if (!act.isFinishing && !isDestroyed) {
                RenameNotice.maybeShow(act, App.provide(requireContext()).settings)
                Aa174Notice.maybeShow(act, App.provide(requireContext()).settings)
            }
        }
        refreshProjectionHealthSuggestion()
        // After the notices above and any auto-connect have had their moment.
        root.removeCallbacks(pagesHintCheck)
        root.postDelayed(pagesHintCheck, PAGES_HINT_DELAY_MS)
    }

    /**
     * The one-time hint that a touch and hold on an empty spot adds pages, for whoever uses
     * Open Headunit as the home, once the home is idle and nothing else is on screen. When it is
     * not the moment, the next time the home is shown asks again.
     */
    private fun maybeShowPagesHint() {
        val ctx = context ?: return
        val root = view as? FrameLayout ?: return
        if (pagesHint != null || !isResumed) return
        val appSettings = App.provide(ctx).settings
        val main = activity as? MainActivity
        val pager = root.findViewById<HomePager>(R.id.home_pager)
        val input = HomePagesHintPolicy.Input(
            alreadyShown = appSettings.homePagesHintShown,
            launcherActive = appSettings.isCarLauncherActive,
            widgetsSupported = HomeWidgets.supported(),
            widgetsOn = appSettings.showHomeWidgets,
            connected = commManager.isConnected,
            status = decision?.status,
            bringUpRunning = ConnectionStageTracker.stage.value != null || MainActivity.overlayOwnsScreen(),
            otherNoticeShowing = RenameNotice.isShowing() || Aa174Notice.isShowing() ||
                activeDialog?.isShowing == true || actionDialog?.isShowing == true ||
                main?.connectionIssueBannerShown() == true,
            onMainPage = (pager?.currentPage ?: 0) == 0,
        )
        if (!HomePagesHintPolicy.shouldShow(input)) return
        val card = root.findViewById<View>(R.id.home_primary_card) ?: return
        if (card.width == 0 || !card.isShown) return

        val res = resources
        val density = res.displayMetrics.density
        val small = compactHome || res.configuration.screenHeightDp < PAGES_HINT_SMALL_HEIGHT_DP
        val rightHandDrive = appSettings.rightHandDrive
        val cardBox = boxIn(root, card)
        val padH = res.getDimensionPixelSize(R.dimen.home_card_padding_h)
        val padV = res.getDimensionPixelSize(R.dimen.home_card_padding_v)
        val interior = HomePagesHintPolicy.Box(cardBox.left + padH, cardBox.top + padV, cardBox.right - padH, cardBox.bottom - padV)
        val occupied = listOf(
            R.id.home_primary_icon_tile, R.id.home_countdown, R.id.home_primary_progress,
            R.id.home_primary_actions, R.id.home_info,
        ).mapNotNull { id -> root.findViewById<View>(id)?.takeIf { it.isShown && it.width > 0 }?.let { boxIn(root, it) } } +
            listOf(R.id.home_primary_kicker, R.id.home_primary_title, R.id.home_primary_subtitle, R.id.home_primary_detail)
                .mapNotNull { id -> root.findViewById<TextView>(id)?.takeIf { it.isShown && it.width > 0 }?.let { textBox(root, it) } }
        val margin = (16 * density).toInt()
        val screen = HomePagesHintPolicy.Box(0, 0, root.width, root.height)
        val gap = (12 * density).toInt()
        val minSize = (PAGES_HINT_MIN_SPOT_DP * density).toInt()
        val maxWidth = ((if (small) 280 else 360) * density).toInt()
        val maxHeight = ((if (small) 220 else 290) * density).toInt()
        val regions = listOf(HomePagesHintPolicy.Region(interior, occupied)) +
            listOfNotNull(mainPageRegion(root, cardBox, screen, margin))
        val spot = HomePagesHintPolicy.bestSpot(regions, rightHandDrive, minSize, maxWidth, maxHeight, gap)
        val placement = spot?.let {
            HomePagesHintPolicy.place(
                spot = it,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                minSize = minSize,
                preferredSize = (PAGES_HINT_PREFERRED_SPOT_DP * density).toInt(),
                bounds = pagesHintBounds(root, cardBox, margin),
                callouts = pagesHintCallouts(ctx, root.width - 2 * margin),
                gap = (18 * density).toInt(),
                arrowInset = HomePagesHintView.arrowInset(ctx),
                rightHandDrive = rightHandDrive,
            )
        }
        val hint = if (placement != null) {
            AppLog.i(
                "HomeFragment: showing the pages hint, ${placement.hole.width}x${placement.hole.height}px, " +
                    "callout ${placement.side.name.lowercase()}"
            )
            HomePagesHintView(
                ctx, placement.hole, placement.callout, placement.side,
                small = small, look = HomePagesHintView.Look(small = placement.calloutSize > 0)
            ) { reason -> onPagesHintClosed(reason) }
        } else {
            // No empty spot anywhere a long press reaches the menu: the hint still says where to.
            val looks = listOf(
                HomePagesHintView.Look(small = false, withArt = true),
                HomePagesHintView.Look(small = false, withArt = true, artBeside = true),
                HomePagesHintView.Look(small = true, withArt = true),
                HomePagesHintView.Look(small = true, withArt = true, artBeside = true),
            )
            val room = root.width - 2 * margin
            val sizes = looks.map { look ->
                // Beside the art the text needs the room, so the callout takes the whole width.
                val width = if (look.artBeside) room else HomePagesHintView.calloutWidth(ctx, look.small, room)
                HomePagesHintView.calloutSize(ctx, look, width, holeless = true)
            }
            val (box, index) = HomePagesHintPolicy.centredCallout(screen, margin, sizes)
            AppLog.i("HomeFragment: showing the pages hint without a spotlight, ${looks[index]}")
            HomePagesHintView(ctx, null, box, null, small = small, look = looks[index]) { reason -> onPagesHintClosed(reason) }
        }
        setHomeBehindHint(root, hidden = true)
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pagesHint = hint
        // Added now rather than with the view: the dispatcher asks the last callback added first,
        // and a callback tied to a lifecycle only joins the list on ON_START, which the home's view
        // reaches before the activity does. The activity's own Back, always on, would then come
        // after this one and swallow the key. The hint only shows while the home is resumed and
        // closes on pause, so nothing re-adds the activity's callback while it is up.
        pagesHintBack.isEnabled = true
        requireActivity().onBackPressedDispatcher.addCallback(pagesHintBack)
    }

    /**
     * The main page around the card, which also opens the page menu on a long press: what is
     * drawn there is the card, the favorites row and the widget row (whose scroller keeps the
     * touches for itself, so it counts whole). Kept [margin] inside the screen.
     */
    private fun mainPageRegion(
        root: View,
        card: HomePagesHintPolicy.Box,
        screen: HomePagesHintPolicy.Box,
        margin: Int,
    ): HomePagesHintPolicy.Region? {
        val page = root.findViewById<View>(R.id.home_page_main)?.takeIf { it.isShown && it.width > 0 } ?: return null
        val box = boxIn(root, page)
        val interior = HomePagesHintPolicy.Box(
            maxOf(box.left, screen.left + margin), maxOf(box.top, screen.top + margin),
            minOf(box.right, screen.right - margin), minOf(box.bottom, screen.bottom - margin)
        )
        if (interior.width <= 0 || interior.height <= 0) return null
        val drawn = listOf(R.id.home_favorites, R.id.home_widgets_scroll)
            .mapNotNull { id -> root.findViewById<View>(id)?.takeIf { it.isShown && it.width > 0 }?.let { boxIn(root, it) } }
        return HomePagesHintPolicy.Region(interior, listOf(card) + drawn)
    }

    /**
     * The callout sizes to try: the regular one, then the small one, which on a narrow portrait
     * screen also narrows to the room there is, down to [PAGES_HINT_MIN_CALLOUT_DP].
     */
    private fun pagesHintCallouts(ctx: Context, room: Int): List<HomePagesHintPolicy.Size> {
        val minWidth = (PAGES_HINT_MIN_CALLOUT_DP * ctx.resources.displayMetrics.density).toInt()
        return listOf(false, true).mapNotNull { small ->
            val width = HomePagesHintView.calloutWidth(ctx, small, room)
            if (width < minWidth) null else HomePagesHintView.calloutSize(ctx, HomePagesHintView.Look(small), width, holeless = false)
        }
    }

    /**
     * Where the hint's callout may go, in order: inside the card, then anywhere but over the
     * driver bar. Both keep [margin] from the screen's edges.
     */
    private fun pagesHintBounds(root: View, card: HomePagesHintPolicy.Box, margin: Int): List<HomePagesHintPolicy.Box> {
        val screen = HomePagesHintPolicy.Box(margin, margin, root.width - margin, root.height - margin)
        fun clip(box: HomePagesHintPolicy.Box) = HomePagesHintPolicy.Box(
            maxOf(box.left, screen.left), maxOf(box.top, screen.top),
            minOf(box.right, screen.right), minOf(box.bottom, screen.bottom)
        )
        val inCard = clip(card)
        val bar = root.findViewById<View>(R.id.home_driver_bar)?.takeIf { it.isShown && it.width > 0 }?.let { boxIn(root, it) }
            ?: return listOf(inCard)
        // The side of the screen the bar is not on, as seen from the card.
        val clearOfBar = when {
            bar.right <= card.left -> screen.copy(left = maxOf(screen.left, bar.right))
            bar.left >= card.right -> screen.copy(right = minOf(screen.right, bar.left))
            bar.top >= card.bottom -> screen.copy(bottom = minOf(screen.bottom, bar.top))
            bar.bottom <= card.top -> screen.copy(top = maxOf(screen.top, bar.bottom))
            else -> inCard
        }
        return listOf(inCard, clip(clearOfBar)).distinct()
    }

    private fun onPagesHintClosed(reason: HomePagesHintView.Reason) {
        pagesHintBack.isEnabled = false
        pagesHintBack.remove()
        val ctx = context ?: return
        App.provide(ctx).settings.homePagesHintShown = true
        val hint = pagesHint
        pagesHint = null
        AppLog.i("HomeFragment: pages hint closed ($reason)")
        val root = view as? FrameLayout
        if (root != null) {
            setHomeBehindHint(root, hidden = false)
            // Posted: this can run inside the hint's own touch handling.
            root.post { root.removeView(hint) }
        }
        if (reason == HomePagesHintView.Reason.HOLD && isResumed) showPageMenu(widgetPage = null)
    }

    /**
     * Keeps D-pad focus and TalkBack on the hint while it is up, and gives the home back after:
     * every view of the fragment but the hint, not just the first, in case anything else was
     * added next to the home.
     */
    private fun setHomeBehindHint(root: FrameLayout, hidden: Boolean) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is HomePagesHintView) continue
            (child as? ViewGroup)?.descendantFocusability =
                if (hidden) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                child.importantForAccessibility = if (hidden) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
            }
        }
        // Whatever on the home had focus would keep it, out of reach behind the hint.
        if (hidden) {
            val focused = root.findFocus()
            if (focused != null && pagesHint?.let { isInside(focused, it) } != true) focused.clearFocus()
        }
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun boxIn(root: View, child: View): HomePagesHintPolicy.Box {
        val rootAt = IntArray(2)
        val childAt = IntArray(2)
        root.getLocationInWindow(rootAt)
        child.getLocationInWindow(childAt)
        val left = childAt[0] - rootAt[0]
        val top = childAt[1] - rootAt[1]
        return HomePagesHintPolicy.Box(left, top, left + child.width, top + child.height)
    }

    /** A text by the room its lines take, not its view, which spans the card. */
    private fun textBox(root: View, text: TextView): HomePagesHintPolicy.Box {
        val box = boxIn(root, text)
        val layout = text.layout ?: return box
        if (layout.lineCount == 0) return box
        var lineLeft = Float.MAX_VALUE
        var lineRight = 0f
        for (i in 0 until layout.lineCount) {
            lineLeft = minOf(lineLeft, layout.getLineLeft(i))
            lineRight = maxOf(lineRight, layout.getLineRight(i))
        }
        val offset = text.compoundPaddingLeft - text.scrollX
        return HomePagesHintPolicy.Box(
            box.left + offset + lineLeft.toInt(), box.top,
            box.left + offset + lineRight.roundToInt(), box.bottom
        )
    }

    override fun onStart() {
        super.onStart()
        // A host that is not listening holds an empty widget view, so this runs before the row is
        // built in onResume.
        HomeWidgets.startListening(requireContext())
        reportBringUpOwner(started = true)
        activity?.window?.decorView?.viewTreeObserver?.addOnGlobalLayoutListener(issueBannerWatcher)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        noticesLayoutListener?.let { activity?.findViewById<View>(R.id.status_notices)?.removeOnLayoutChangeListener(it) }
        noticesLayoutListener = null
        // The next view builds its widgets again; the host must not keep this one's alive.
        HomeWidgets.releaseViews()
    }

    override fun onStop() {
        super.onStop()
        HomeWidgets.stopListening()
        mainViewModel.homeShowsBringUp.value = false
        // Started again by the next render, once the home is back on screen.
        view?.let { HomeUiHelper.stopShines(it) }
        activity?.window?.decorView?.viewTreeObserver?.let {
            if (it.isAlive) it.removeOnGlobalLayoutListener(issueBannerWatcher)
        }
        lastIssueBannerUp = null
        // The card sits in MainActivity's notices, so it would stay up over the next screen.
        healthCard?.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        driverCountdownTimer?.cancel()
        driverCountdownTimer = null
        stopHomeInfo()
        activeDialog?.dismiss()
        activeDialog = null
        actionDialog?.dismiss()
        actionDialog = null
        RenameNotice.dismiss()
        Aa174Notice.dismiss()
        view?.removeCallbacks(pagesHintCheck)
        pagesHint?.close(HomePagesHintView.Reason.LEFT)
    }

    private fun applyLayoutSettings(root: View, homeSettings: Settings) {
        HomeUiHelper.applyDriverSide(root, homeSettings.rightHandDrive)
        HomeUiHelper.applyButtonScale(root, homeSettings.homeButtonScalePercent)
        HomeUiHelper.applyTheme(root.context, root, homePalette(root.context))
        // The bar can only be fitted once it has a height to fit into.
        root.doOnLayout { HomeUiHelper.fitDriverBar(it, homeSettings.homeButtonScalePercent) }
    }

    private fun refreshProjectionHealthSuggestion() {
        val card = healthCard ?: return
        // The connection issue banner sits in the same place and says what stops the next
        // connection, which matters more than a setting for the next projection.
        val issueBannerUp = activity?.findViewById<View>(R.id.connection_issue_banner)
            ?.visibility == View.VISIBLE
        if (commManager.isConnected || issueBannerUp) {
            pendingHealthAction = null
            card.visibility = View.GONE
            return
        }
        val appSettings = App.provide(requireContext()).settings
        val action = ProjectionHealthPolicy.evaluate(
            ProjectionHealthStore(requireContext()).state(appSettings)
        )
        if (action == null) {
            pendingHealthAction = null
            card.visibility = View.GONE
            return
        }
        pendingHealthAction = action
        healthMessage?.text = getString(healthMessageRes(action))
        card.visibility = View.VISIBLE
    }

    /**
     * Follows the connection issue banner: it comes and goes on its own, and this one has to step
     * aside while it is up and come back once it is gone. A layout pass is the one signal both have.
     */
    private val issueBannerWatcher = ViewTreeObserver.OnGlobalLayoutListener {
        val up = activity?.findViewById<View>(R.id.connection_issue_banner)?.visibility == View.VISIBLE
        if (up != lastIssueBannerUp) {
            lastIssueBannerUp = up
            refreshProjectionHealthSuggestion()
        }
    }
    private var lastIssueBannerUp: Boolean? = null

    private fun applyProjectionHealthSuggestion() {
        val action = pendingHealthAction ?: return
        val appSettings = App.provide(requireContext()).settings
        when (action) {
            ProjectionHealthPolicy.Action.CODEC_H264 -> {
                appSettings.videoCodec = "H.264"
                // Android Auto forces H.265 above Full HD, so H.264 only takes effect if the
                // resolution comes down with it.
                val stored = Settings.Resolution.fromId(appSettings.resolutionId)
                when {
                    stored == null || stored == Settings.Resolution.AUTO -> {
                        val panel = ProjectionHealthStore(requireContext())
                            .state(appSettings)
                            .panelCeiling
                        if (panel.width > Settings.Resolution._1920x1080.width) {
                            appSettings.resolutionId = Settings.Resolution._1920x1080.id
                        }
                    }
                    stored.width > Settings.Resolution._1920x1080.width ->
                        appSettings.resolutionId = Settings.Resolution._1920x1080.id
                }
            }
            ProjectionHealthPolicy.Action.RENDERER_SURFACE -> {
                appSettings.viewMode = Settings.ViewMode.SURFACE
                appSettings.pendingRendererConfirm = true
            }
            ProjectionHealthPolicy.Action.FPS_30 -> appSettings.fpsLimit = 30
            ProjectionHealthPolicy.Action.RESOLUTION_720 ->
                appSettings.resolutionId = Settings.Resolution._1280x720.id
        }
        appSettings.commit()
        ProjectionHealthStore(requireContext()).dismiss(action)
        pendingHealthAction = null
        healthCard?.visibility = View.GONE
        ToastUtils.showToast(
            requireContext(),
            R.string.projection_health_applied,
            force = true,
        )
    }

    private fun dismissProjectionHealthSuggestion() {
        val action = pendingHealthAction ?: return
        ProjectionHealthStore(requireContext()).dismiss(action)
        pendingHealthAction = null
        healthCard?.visibility = View.GONE
    }

    /** What went wrong and what the button changes, in the words Settings uses for it. */
    private fun healthMessageRes(action: ProjectionHealthPolicy.Action): Int = when (action) {
        ProjectionHealthPolicy.Action.CODEC_H264 -> R.string.projection_health_suggest_h264
        ProjectionHealthPolicy.Action.RENDERER_SURFACE -> R.string.projection_health_suggest_surface
        ProjectionHealthPolicy.Action.FPS_30 -> R.string.projection_health_suggest_fps30
        ProjectionHealthPolicy.Action.RESOLUTION_720 -> R.string.projection_health_suggest_720p
    }

    /** The offered phones an automatic pick may name, from the same wake list the poke loop reads. */
    private fun autoScopeMacs(cands: BluetoothHelper.DriverCandidates, settings: Settings): List<String> {
        val notPhones = settings.nativePokeBtMacs.filter {
            cands.verdictOf(it) == DriverCandidatePolicy.Verdict.NOT_A_PHONE
        }.toSet()
        val targets = PokeTargetPolicy.targets(settings.nativePokeBtMacs, settings.nativePokeAllPairedDevices, notPhones)
        return NativeDriverSelectionPolicy.autoScope(cands.offered.map { it.address }, targets)
    }

    /** @return whether this put something on screen, or started connecting, so nothing else should. */
    private fun checkNativeDriverSelectionOnStartup(): Boolean {
        if (!isAdded) return false
        hasCheckedNativeDriverSelection = true
        if (ExternalBtTransportPolicy.usesExternalModule(NativeAaHandshakeManager.transportRoute(requireContext()))) {
            return false
        }
        val appSettings = App.provide(requireContext()).settings
        if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.DISABLED) return false
        // Every answer this check can reach pokes a phone and arms the stack, so a unit the user
        // set to cable only is never asked. The WiFi button still reaches the selector.
        if (WirelessSelectionPolicy.refusesBringUp(
                wirelessSelected = appSettings.showsWifi(),
                userRequested = false,
            )
        ) {
            AppLog.i("HomeFragment: driver selection skipped, wireless is not a chosen connection mode")
            return false
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        if (adapter == null || !adapter.isEnabled) return false

        // Only the classified phones count, so a connected watch neither becomes the driver nor
        // hides the one phone that is unambiguously here.
        val cands = BluetoothHelper.driverCandidates(
            requireContext(), appSettings.nativePreferredDeviceMac, appSettings.lastConnectedNativeMac
        )
        val targetList = cands.offered
        val connectedMacs = cands.connectedOffered.map { it.address }

        val effectiveLastUsedMac = NativeDriverSelectionPolicy.lastUsedMac(
            appSettings.lastConnectedNativeMac, appSettings.nativePokeBtMacs
        )
        val hasHistory = effectiveLastUsedMac.isNotEmpty() || appSettings.nativePreferredDeviceMac.isNotEmpty()

        val scope = autoScopeMacs(cands, appSettings)
        val shouldShow = NativeDriverSelectionPolicy.shouldShowSelector(
            mode = appSettings.nativeDriverSelectionMode,
            pairedCount = targetList.size,
            connectedCount = connectedMacs.size,
            scopedCount = scope.size,
            hasHistory = hasHistory
        )

        val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = appSettings.nativePreferredDeviceMac,
            lastUsedMac = effectiveLastUsedMac,
            connectedMacs = connectedMacs,
            pairedMacs = targetList.map { it.address },
            scopeMacs = scope
        )

        if (shouldShow) {
            showNativeAaDeviceSelector(autoCountdown = true)
            return true
        } else if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.AUTO && autoTargetMac != null) {
            val targetDev = cands.deviceFor(autoTargetMac)
            val devName = targetDev?.name ?: autoTargetMac
            AppLog.i("HomeFragment: Unambiguous driver ($devName) - auto-connecting directly without prompt")
            connectToNativeDevice(autoTargetMac, devName, connectedMacs, explicit = false)
            return true
        }
        return false
    }

    private fun showNativeAaDeviceSelector(autoCountdown: Boolean = false) {
        if (!isAdded) return
        if (ExternalBtTransportPolicy.usesExternalModule(NativeAaHandshakeManager.transportRoute(requireContext()))) {
            AppLog.i("HomeFragment: Android Bluetooth driver selection does not apply to the external-module route")
            return
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())

        if (adapter == null || !adapter.isEnabled) {
            if (adapter == null && BluetoothDevicePicker.showManufacturerDevices(requireContext())) return
            ToastUtils.showToast(requireContext(), getString(R.string.bt_not_enabled), Toast.LENGTH_SHORT, force = true)
            return
        }

        val appSettings = App.provide(requireContext()).settings
        val preferredMac = appSettings.nativePreferredDeviceMac
        val lastUsedMac = appSettings.lastConnectedNativeMac
        val cands = BluetoothHelper.driverCandidates(requireContext(), preferredMac, lastUsedMac)
        val bondedDevices = cands.all.map { it.device }
        if (bondedDevices.isEmpty()) {
            if (BluetoothDevicePicker.showManufacturerDevices(requireContext(), cands.inventory)) return
            ToastUtils.showToast(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT, force = true)
            return
        }

        // Row labels say who is here, phone or not. The count and the countdown target below ask
        // only the phones, and an unreadable presence leaves every row unlabelled.
        val connectedMacs = cands.connectedAll.map { it.address }
        val presenceReadable = cands.presenceReadable

        val likelyPhones = cands.offered
        var showAllDevices = likelyPhones.isEmpty()
        val initialCandidates = if (showAllDevices) bondedDevices else likelyPhones

        fun sortDeviceList(devices: List<BluetoothDevice>): List<BluetoothDevice> {
            return devices.sortedWith(
                compareByDescending<BluetoothDevice> { it.address in connectedMacs }
                    .thenByDescending { it.address.equals(preferredMac, ignoreCase = true) }
                    .thenByDescending { it.address.equals(lastUsedMac, ignoreCase = true) }
                    .thenBy { it.name ?: "" }
            )
        }

        val currentDevices = mutableListOf<BluetoothDevice>()
        currentDevices.addAll(sortDeviceList(initialCandidates))

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_driver_selector, null)
        val countdownContainer = dialogView.findViewById<View>(R.id.countdownContainer)
        val countdownSubtitle = dialogView.findViewById<TextView>(R.id.countdownSubtitle)
        val countdownProgress = dialogView.findViewById<ProgressBar>(R.id.countdownProgress)
        val deviceListView = dialogView.findViewById<ListView>(R.id.driverDeviceList)
        val btnToggleFilter = dialogView.findViewById<TextView>(R.id.btnToggleFilter)

        val brandPrimary = ContextCompat.getColor(requireContext(), R.color.brand_primary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            countdownProgress.progressTintList = ColorStateList.valueOf(brandPrimary)
        }

        val connectedMacSet = connectedMacs.toSet()
        val listAdapter = object : ArrayAdapter<BluetoothDevice>(requireContext(), R.layout.list_item_driver_device, currentDevices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_driver_device, parent, false)
                val device = getItem(position) ?: return view
                val nameView = view.findViewById<TextView>(R.id.deviceName)
                val statusView = view.findViewById<TextView>(R.id.deviceStatus)
                val badgeView = view.findViewById<TextView>(R.id.badgeText)

                val iconView = view.findViewById<ImageView>(R.id.deviceIcon)
                if (cands.verdictOf(device.address) != DriverCandidatePolicy.Verdict.NOT_A_PHONE) {
                    iconView.setImageResource(R.drawable.ic_phone)
                } else {
                    iconView.setImageResource(R.drawable.ic_headphones)
                }

                nameView.text = device.name ?: "Unknown Device"

                when (NativeDriverSelectionPolicy.presenceOf(device.address, connectedMacSet, presenceReadable)) {
                    NativeDriverSelectionPolicy.DriverPresence.CONNECTED -> {
                        statusView.text = "🟢 " + context.getString(R.string.driver_device_connected)
                        statusView.setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
                    }
                    NativeDriverSelectionPolicy.DriverPresence.DISCONNECTED -> {
                        statusView.text = context.getString(R.string.driver_device_disconnected)
                        statusView.setTextColor(Color.LTGRAY)
                    }
                    NativeDriverSelectionPolicy.DriverPresence.UNKNOWN -> {
                        statusView.text = context.getString(R.string.driver_device_paired)
                        statusView.setTextColor(Color.LTGRAY)
                    }
                }

                when {
                    device.address.equals(preferredMac, ignoreCase = true) -> {
                        badgeView.text = "⭐ " + context.getString(R.string.driver_device_preferred)
                        badgeView.visibility = View.VISIBLE
                        badgeView.setBackgroundResource(R.drawable.bg_setting_single)
                    }
                    device.address.equals(lastUsedMac, ignoreCase = true) -> {
                        badgeView.text = context.getString(R.string.driver_device_last_used)
                        badgeView.visibility = View.VISIBLE
                        badgeView.setBackgroundResource(R.drawable.bg_setting_single)
                    }
                    else -> {
                        badgeView.visibility = View.GONE
                    }
                }

                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }

        val dm = resources.displayMetrics
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val maxListHeight = if (isLandscape) {
            (dm.heightPixels * 0.38).toInt().coerceIn((120 * dm.density).toInt(), (200 * dm.density).toInt())
        } else {
            (dm.heightPixels * 0.45).toInt().coerceIn((180 * dm.density).toInt(), (320 * dm.density).toInt())
        }
        deviceListView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxListHeight
        )
        deviceListView.adapter = listAdapter

        val hiddenCount = bondedDevices.size - likelyPhones.size
        if (hiddenCount > 0 && likelyPhones.isNotEmpty()) {
            btnToggleFilter.visibility = View.VISIBLE
            fun updateToggleLabel() {
                btnToggleFilter.text = if (showAllDevices) {
                    getString(R.string.show_only_phones)
                } else {
                    "${getString(R.string.show_all_devices)} ($hiddenCount)"
                }
            }
            updateToggleLabel()
            btnToggleFilter.setOnClickListener {
                // Pause/cancel auto-countdown timer since the user is actively interacting with the device list
                driverCountdownTimer?.cancel()
                driverCountdownTimer = null
                countdownContainer.visibility = View.GONE

                showAllDevices = !showAllDevices
                currentDevices.clear()
                val nextCandidates = if (showAllDevices) bondedDevices else likelyPhones
                currentDevices.addAll(sortDeviceList(nextCandidates))
                listAdapter.notifyDataSetChanged()
                updateToggleLabel()
            }
        } else {
            btnToggleFilter.visibility = View.GONE
        }

        // Set by every path that ends the prompt with an answer, so the dismiss below can tell
        // "the user left" from "the user chose".
        var selectionResolved = false

        val cancelDriverSelection = {
            selectionResolved = true
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            activeDialog = null
            val cancelIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_CANCEL_POKE
            }
            ContextCompat.startForegroundService(requireContext(), cancelIntent)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.driver_selection_dialog_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel) { d, _ ->
                cancelDriverSelection()
                d.dismiss()
            }
            .create()

        dialog.setOnCancelListener {
            cancelDriverSelection()
        }

        dialog.setOnDismissListener {
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            activeDialog = null
            val ctx = context
            if (!selectionResolved && ctx != null) {
                // onPause dismisses the dialog, which reaches here and never onCancel. Without
                // this the prompt flag stayed set and every phone was refused.
                val dismissIntent = Intent(ctx, AapService::class.java).apply {
                    action = AapService.ACTION_NATIVE_AA_PROMPT_DISMISSED
                }
                ContextCompat.startForegroundService(ctx, dismissIntent)
            }
        }

        val effectiveLastUsed = NativeDriverSelectionPolicy.lastUsedMac(
            lastUsedMac, appSettings.nativePokeBtMacs
        )
        // Scoped to the phones whatever "Show all" is showing, so widening the list never changes
        // what the countdown connects to.
        val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = preferredMac,
            lastUsedMac = effectiveLastUsed,
            connectedMacs = cands.connectedOffered.map { it.address },
            pairedMacs = likelyPhones.map { it.address },
            scopeMacs = autoScopeMacs(cands, appSettings)
        )
        val autoTargetDevice = cands.deviceFor(autoTargetMac)
        val targetName = autoTargetDevice?.name ?: autoTargetMac ?: ""

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            selectionResolved = true
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            dialog.dismiss()
            val chosen = currentDevices.getOrNull(position) ?: return@setOnItemClickListener
            connectToNativeDevice(chosen.address, chosen.name ?: "Device", connectedMacs, explicit = true)
        }

        val timeoutSec = NativeDriverSelectionPolicy.sanitizeTimeout(appSettings.nativeDriverSelectionTimeoutSec)

        if (autoCountdown && autoTargetMac != null) {
            countdownContainer.visibility = View.VISIBLE
            countdownProgress.max = timeoutSec * 1000
            countdownProgress.progress = timeoutSec * 1000

            var lastSecondsRemaining = -1
            driverCountdownTimer?.cancel()
            driverCountdownTimer = object : CountDownTimer(timeoutSec * 1000L, 100L) {
                override fun onTick(millisUntilFinished: Long) {
                    if (!isAdded || dialog.isShowing != true) return
                    countdownProgress.progress = millisUntilFinished.toInt()
                    val secondsRemaining = ((millisUntilFinished + 999) / 1000).toInt()
                    if (secondsRemaining != lastSecondsRemaining) {
                        lastSecondsRemaining = secondsRemaining
                        countdownSubtitle.text = getString(R.string.driver_selection_auto_in, secondsRemaining, targetName)
                    }
                }

                override fun onFinish() {
                    if (!isAdded || dialog.isShowing != true) return
                    selectionResolved = true
                    dialog.dismiss()
                    connectToNativeDevice(autoTargetMac, targetName, connectedMacs, explicit = false)
                }
            }
            countdownSubtitle.text = getString(R.string.driver_selection_auto_in, timeoutSec, targetName)
        } else {
            countdownContainer.visibility = View.GONE
        }

        dialog.setOnShowListener {
            // An activity relaunch between show() and this callback leaves it queued against a
            // destroyed fragment; the fragment that replaced this one opens its own selector.
            val ctx = context ?: return@setOnShowListener
            (activity as? MainActivity)?.dismissSplashImmediately()
            val promptIntent = Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_PROMPT_SHOWN
            }
            ContextCompat.startForegroundService(ctx, promptIntent)
            if (autoCountdown && autoTargetMac != null) {
                driverCountdownTimer?.start()
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    /**
     * Wakes [mac] and shows the connect UI. The wake may take several rounds or never be answered,
     * so every path gets the non-blocking pill with its step line; the full-screen overlay takes
     * over once the phone answers. The pill names the phone, so no toast repeats it.
     */
    private fun connectToNativeDevice(mac: String, name: String, connectedMacs: Collection<String>, explicit: Boolean) {
        val reachable = NativeDriverSelectionPolicy.connectUiIsImmediate(mac, connectedMacs)
        AppLog.i("HomeFragment: Connecting to Native-AA device: $name ($mac), btConnected=$reachable")
        // Without a status text the indicator falls back to "Android Auto is starting", which is the
        // claim the pill exists to avoid making about a phone that still has to be woken.
        val statusText = if (reachable) getString(R.string.connecting_driver, name)
                         else getString(R.string.connecting_driver_disconnected, name)
        (requireActivity() as? MainActivity)?.beginAutoConnect(
            "Native-AA driver: $name",
            MainActivity.ConnectionUiMode.PILL_THEN_OVERLAY,
            statusText,
            statusTextIsWakeClaim = !reachable
        )
        val intent = Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_NATIVE_AA_POKE
            putExtra(AapService.EXTRA_MAC, mac)
            putExtra(AapService.EXTRA_EXPLICIT_PICK, explicit)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    /** Manufacturer-paired phones must go to the HCT carrier, never Android RFCOMM. */
    private fun showManufacturerPhoneSelector() {
        if (activeDialog?.isShowing == true || actionDialog?.isShowing == true || manufacturerPickerLoading) return
        val context = requireContext()
        val owner = viewLifecycleOwner
        val originView = view
        manufacturerPickerLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val peers = try {
                withContext(Dispatchers.IO) { BluetoothPeerDiscovery.manufacturerInventory(context).peers }
            } finally {
                manufacturerPickerLoading = false
            }
            // Inventory reads can finish after Settings opened or another dialog appeared.
            // Do not restart wireless from a result belonging to a paused/replaced home view.
            if (!isAdded || view !== originView || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                activeDialog?.isShowing == true || actionDialog?.isShowing == true) return@launch
            if (App.provide(context).settings.wifiConnectionMode != WifiLauncherMode.NATIVE ||
                NativeAaHandshakeManager.transportRoute(context) != ExternalBtTransportPolicy.Route.HCT) return@launch
            if (peers.isEmpty()) {
                // An unavailable inventory must not strand a carrier whose OEM binding died.
                // No MAC means re-arm observation only: HCT requires an explicit paired-phone
                // selection before it may send a manufacturer reconnect command.
                ContextCompat.startForegroundService(context,
                    Intent(context, AapService::class.java).setAction(AapService.ACTION_NATIVE_AA_POKE))
                // This notice is not a selection prompt and must not hold/cancel fresh handoffs.
                MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                    .setTitle(R.string.bt_manufacturer_devices_title)
                    .setMessage(R.string.hct_aa_no_paired_phone)
                    .setPositiveButton(android.R.string.ok, null)
                    .create().also { notice ->
                        notice.setOnDismissListener { if (activeDialog === notice) activeDialog = null }
                        activeDialog = notice
                        notice.show()
                    }
                return@launch
            }
            var selectionResolved = false
            fun notifySelection(action: String) {
                ContextCompat.startForegroundService(context,
                    Intent(context, AapService::class.java).setAction(action))
            }
            fun cancelSelection() {
                selectionResolved = true
                notifySelection(AapService.ACTION_NATIVE_AA_CANCEL_POKE)
            }
            val builder = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                .setTitle(R.string.bt_manufacturer_devices_title)
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancelSelection() }
            val labels = peers.map { peer -> peer.name?.let { "$it (${peer.address})" } ?: peer.address }
            builder.setItems(labels.toTypedArray()) { _, index ->
                selectionResolved = true
                val peer = peers[index]
                connectToNativeDevice(peer.address, peer.name ?: peer.address, emptyList(), explicit = true)
            }
            val dialog = builder.create()
            dialog.setOnShowListener {
                (activity as? MainActivity)?.dismissSplashImmediately()
                notifySelection(AapService.ACTION_NATIVE_AA_PROMPT_SHOWN)
            }
            dialog.setOnCancelListener { cancelSelection() }
            dialog.setOnDismissListener {
                if (activeDialog === dialog) activeDialog = null
                if (!selectionResolved) notifySelection(AapService.ACTION_NATIVE_AA_PROMPT_DISMISSED)
            }
            activeDialog = dialog
            dialog.show()
        }
    }

    private fun showNearbyDeviceSelector() {
        // Ensure NearbyManager discovery is running via AapService
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_nearby_selection, null)
        val deviceListView = dialogView.findViewById<ListView>(R.id.deviceList)
        val searchingText = dialogView.findViewById<TextView>(R.id.searchingText)
        val connectionProgress = dialogView.findViewById<ProgressBar>(R.id.connectionProgress)

        // Ensure the loading spinner is visible in both Light and Dark modes by forcing our brand color.
        val brandPrimary = ContextCompat.getColor(requireContext(), R.color.brand_primary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectionProgress.indeterminateTintList = ColorStateList.valueOf(brandPrimary)
            connectionProgress.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        } else {
            @Suppress("DEPRECATION")
            connectionProgress.indeterminateDrawable?.setColorFilter(brandPrimary, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        // Custom adapter to handle rounded backgrounds like in USB/Network lists
        val listAdapter = object : ArrayAdapter<NearbyManager.DiscoveredEndpoint>(requireContext(), R.layout.list_item_nearby) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_nearby, parent, false)
                val endpoint = getItem(position)
                view.findViewById<TextView>(R.id.deviceName).text = endpoint?.name ?: "Unknown"

                // Apply rounded backgrounds based on position
                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }
        deviceListView.adapter = listAdapter

        var collectJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.searching)) // Initial title
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                collectJob?.cancel()
                if (activeDialog == it) activeDialog = null
            }
            .create()

        activeDialog = dialog

        deviceListView.setOnItemClickListener { _, _, which, _ ->
            val endpoints = NearbyManager.discoveredEndpoints.value
            if (which < endpoints.size) {
                val endpoint = endpoints[which]
                AppLog.i("HomeFragment: Selected Nearby device: ${endpoint.name} (${endpoint.id})")

                // Hand off to the auto-connect overlay so the user gets the same
                // visual treatment as every other connection path. Pass the
                // endpoint name so the loading screen can show "Connecting to
                // <device>…" instead of the generic status text.
                val statusText = getString(R.string.connecting_to_nearby, endpoint.name)
                dialog.dismiss()
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual Nearby select ${endpoint.name}",
                    MainActivity.ConnectionUiMode.OVERLAY,
                    statusText
                )

                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NEARBY_CONNECT
                    putExtra(AapService.EXTRA_ENDPOINT_ID, endpoint.id)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
            }
        }

        dialog.show()

        // Live-update the dialog list as endpoints are discovered
        collectJob = viewLifecycleOwner.lifecycleScope.launch {
            NearbyManager.discoveredEndpoints.collect { endpoints ->
                listAdapter.clear()
                listAdapter.addAll(endpoints)
                listAdapter.notifyDataSetChanged()

                if (endpoints.isEmpty()) {
                    dialog.setTitle(getString(R.string.searching))
                    searchingText.visibility = View.GONE
                } else {
                    dialog.setTitle(getString(R.string.nearby_device_found))
                    searchingText.visibility = View.VISIBLE
                    searchingText.text = getString(R.string.select_nearby_device) + " (${endpoints.size})"
                }
            }
        }
    }

    companion object {
        private const val STATE_PENDING_WIDGET_ID = "home_pending_widget_id"
        private const val STATE_PENDING_WIDGET_PAGE = "home_pending_widget_page"
        private const val STATE_PENDING_SELF_VPN = "home_pending_self_vpn"
        private const val STATE_PENDING_SELF_TICKET = "home_pending_self_ticket"
        private const val UNAVAILABLE_FAVORITE_ALPHA = 0.45f
        private var hasAutoStarted = false
        private var hasCheckedNativeDriverSelection = false
        var forceSelfModeLaunch = false
        var requestDriverSelection = false
        private const val MINUTE_MS = 60_000L
        private const val CLOCK_SLACK_MS = 250L
        private const val SPEED_INTERVAL_MS = 1_000L
        /** Older than this and the speed is shown as unknown: a stale reading is worse than none. */
        private const val SPEED_MAX_AGE_MS = 10_000L
        /** Where the main button's card stops fitting and the favorites take over. */
        private const val COMPACT_HEIGHT_DP = 360
        /** How long the home waits before the pages hint, so notices and auto-connect go first. */
        private const val PAGES_HINT_DELAY_MS = 1500L
        /** Below this height the pages hint uses its smaller callout, as on an 800x480 unit. */
        private const val PAGES_HINT_SMALL_HEIGHT_DP = 520
        /** Narrower than this the callout's title would break into too many lines. */
        private const val PAGES_HINT_MIN_CALLOUT_DP = 240
        /** The smallest empty spot the hint lights up; the hand and ring shrink to fit it. */
        private const val PAGES_HINT_MIN_SPOT_DP = 64
        /** Below this the spotlight only shrinks when no callout, small or narrow, fits otherwise. */
        private const val PAGES_HINT_PREFERRED_SPOT_DP = 120

        private const val COMPACT_WIDTH_DP = 400
        /** The idle page dots, about half as bright as the muted text. */
        private const val DOT_IDLE_ALPHA = 0x80
        fun resetAutoStart() {
            hasAutoStarted = false
            hasCheckedNativeDriverSelection = false
        }
    }
}
