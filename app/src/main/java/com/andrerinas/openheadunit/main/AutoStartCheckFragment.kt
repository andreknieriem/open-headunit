package com.andrerinas.openheadunit.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.AutoStartCheckPolicy
import com.andrerinas.openheadunit.app.AutoStartCheckScreenPolicy
import com.andrerinas.openheadunit.app.AutoStartFixPolicy
import com.andrerinas.openheadunit.app.MakerWhitelistPolicy
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AutoStartCheck
import com.andrerinas.openheadunit.utils.AutoStartFixes
import com.andrerinas.openheadunit.utils.MakerWhitelist
import com.andrerinas.openheadunit.utils.OemAppManager
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Read-only check of why the head unit may be closing the app (#1122). Asks for root when opened,
 * so units with a root manager show its prompt here, and nothing on the unit is changed.
 */
class AutoStartCheckFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val adapter = SettingsAdapter()
    private var findings: AutoStartCheckPolicy.Findings? = null
    /** True while a check runs to show what a fix changed: the old list stays until it is done. */
    private var refreshing = false
    /** The rows only cascade in once per check that started from an empty screen. */
    private var cascadePending = false

    /** Beside the list on wide screens; null there means it is the list's first item. */
    private var sidePanel: AutoStartVerdictPanel? = null
    private val verdictAdapter = AutoStartVerdictAdapter(
        onFix = { applyRecommended() }, onAgain = { runCheck() },
        onCopy = { findings?.let { copyReport(it) } }, onShare = { findings?.let { shareReport(it) } },
        onAdbGuide = { openAdbGuide() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "Check again" at the end of the ADB guide: ADB may be on now.
        parentFragmentManager.setFragmentResultListener(AdbGuideFragment.RESULT_KEY, this) { _, result ->
            if (result.getBoolean(AdbGuideFragment.RESULT_CHECK_AGAIN)) runCheck()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_auto_start_check, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        val side = view.findViewById<View?>(R.id.verdictPanel)
        sidePanel = side?.let {
            AutoStartVerdictPanel(it, onFix = { applyRecommended() }, onAgain = { runCheck() },
                onCopy = { findings?.let { f -> copyReport(f) } }, onShare = { findings?.let { f -> shareReport(f) } },
                onAdbGuide = { openAdbGuide() })
        }
        recyclerView.adapter = if (sidePanel != null) adapter else ConcatAdapter(verdictAdapter, adapter)

        if (findings == null) runCheck() else updateList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sidePanel = null
    }

    /** [keepList] leaves the current rows up while it runs, for a check right after a fix. */
    private fun runCheck(keepList: Boolean = false) {
        refreshing = keepList && findings != null
        if (!refreshing) {
            findings = null
            cascadePending = true
            updateList()
        }
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            OemAppManager.requestRoot()
            findings = try {
                AutoStartCheck.run(appContext)
            } catch (e: Exception) {
                AppLog.e("AutoStartCheckFragment: check failed: ${e.message}", e)
                null
            }
            refreshing = false
            updateList()
        }
    }

    private fun canAct(f: AutoStartCheckPolicy.Findings) = f.route != OemAppManager.ExecutionBackend.NONE.name.lowercase()

    private fun screenState(f: AutoStartCheckPolicy.Findings): AutoStartCheckScreenPolicy.State {
        val settings = App.provide(requireContext()).settings
        val format = MakerWhitelistPolicy.detect(f.listFiles.map { it.path })
        return AutoStartCheckScreenPolicy.State(
            verdict = AutoStartCheckPolicy.verdict(f),
            canAct = canAct(f),
            exemptionsApply = AutoStartFixPolicy.exemptionsApply(Build.VERSION.SDK_INT),
            exempt = f.batteryExempt == true && f.backgroundRestricted != true,
            duraSpeedInstalled = f.duraSpeed == true,
            duraSpeedOffOnBoot = settings.duraSpeedOffOnBoot,
            makerList = format?.let { MakerWhitelistPolicy.status(it, f.listFiles.filter { l -> l.hasApp }.map { l -> l.path }) }
        )
    }

    private fun updateList() {
        val f = findings
        if (f == null) {
            showVerdict(AutoStartVerdict(title = null, body = getString(R.string.auto_start_check_intro),
                problem = false, access = null, showFix = false, reportReady = false))
            adapter.submitList(emptyList())
            return
        }

        val state = screenState(f)
        val headline = AutoStartCheckScreenPolicy.headline(state)
        val (titleRes, bodyRes) = when (headline) {
            AutoStartCheckScreenPolicy.Headline.FORCE_STOPPED ->
                R.string.auto_start_check_headline_force_stopped to R.string.auto_start_check_verdict_force_stopped
            AutoStartCheckScreenPolicy.Headline.BATTERY ->
                R.string.auto_start_check_headline_battery to R.string.auto_start_check_verdict_battery
            AutoStartCheckScreenPolicy.Headline.DURASPEED ->
                R.string.auto_start_check_headline_duraspeed to R.string.auto_start_check_duraspeed_off_description
            AutoStartCheckScreenPolicy.Headline.MAKER_LIST ->
                R.string.auto_start_check_headline_maker_list to R.string.auto_start_check_maker_list_body
            AutoStartCheckScreenPolicy.Headline.NO_ACCESS ->
                R.string.auto_start_check_headline_no_access to R.string.auto_start_check_verdict_no_access
            AutoStartCheckScreenPolicy.Headline.NOTHING_FOUND ->
                R.string.auto_start_check_headline_nothing to R.string.auto_start_check_verdict_unknown
        }
        showVerdict(AutoStartVerdict(
            title = getString(titleRes),
            body = getString(bodyRes),
            problem = headline != AutoStartCheckScreenPolicy.Headline.NOTHING_FOUND,
            access = getString(when (f.route) {
                OemAppManager.ExecutionBackend.ROOT.name.lowercase() -> R.string.auto_start_check_access_root
                OemAppManager.ExecutionBackend.ADB.name.lowercase() -> R.string.auto_start_check_access_adb
                AutoStartCheckPolicy.ROUTE_ADB_ROOT -> R.string.auto_start_check_access_adb_root
                else -> R.string.auto_start_check_access_none
            }),
            showFix = AutoStartCheckScreenPolicy.recommendedFixes(state).isNotEmpty(),
            reportReady = true,
            showAdbGuide = !state.canAct
        ))

        val items = mutableListOf<SettingItem>()
        items.add(SettingItem.CategoryHeader("checkCauses", R.string.auto_start_check_causes))
        addCauses(items, f, state)
        if (!state.canAct) {
            items.add(SettingItem.InfoBanner("checkNoAccess", R.string.auto_start_check_no_access_hint))
        }
        addDetails(items, f, state)

        val cascade = cascadePending
        cascadePending = false
        adapter.submitList(items) { if (cascade) cascadeRows() }
    }

    private fun openAdbGuide() {
        val f = findings ?: return
        AppLog.i("AutoStartCheckFragment: opening the ADB guide")
        findNavController().navigate(R.id.action_autoStartCheckFragment_to_adbGuideFragment,
            AdbGuideFragment.args(listOf(f.device, f.platform, f.firmware)))
    }

    private fun showVerdict(v: AutoStartVerdict) {
        val side = sidePanel
        if (side != null) side.bind(v) else verdictAdapter.verdict = v
    }

    /** One row per thing that can close the app, each with its own fix when there is one. */
    private fun addCauses(items: MutableList<SettingItem>, f: AutoStartCheckPolicy.Findings, s: AutoStartCheckScreenPolicy.State) {
        val good = SettingItem.StatusTone.GOOD
        val problem = SettingItem.StatusTone.PROBLEM

        if (s.exemptionsApply) {
            items.add(SettingItem.StatusEntry(
                stableId = "checkExemptions",
                nameResId = R.string.auto_start_check_battery,
                state = getString(if (s.exempt) R.string.auto_start_check_battery_state_exempt
                    else R.string.auto_start_check_battery_state_not_exempt),
                tone = if (s.exempt) good else problem,
                switchChecked = s.exempt,
                isEnabled = s.canAct,
                helpResId = R.string.auto_start_check_exemptions_description,
                onSwitch = { grant -> runFix { AutoStartFixes.setExemptions(it, grant) } }
            ))
        }

        // Shown while on even if DuraSpeed is no longer detected, so it can always be turned off.
        if (s.duraSpeedInstalled || s.duraSpeedOffOnBoot) {
            val settings = App.provide(requireContext()).settings
            items.add(SettingItem.StatusEntry(
                stableId = "checkDuraSpeedOff",
                nameResId = R.string.auto_start_check_duraspeed,
                state = getString(if (s.duraSpeedOffOnBoot) R.string.auto_start_check_duraspeed_state_off
                    else R.string.auto_start_check_duraspeed_state_on),
                tone = if (s.duraSpeedOffOnBoot) good else problem,
                switchChecked = s.duraSpeedOffOnBoot,
                isEnabled = s.canAct || s.duraSpeedOffOnBoot,
                helpResId = R.string.auto_start_check_duraspeed_off_description,
                onSwitch = { on ->
                    settings.duraSpeedOffOnBoot = on
                    AppLog.i("AutoStartCheckFragment: DuraSpeed off on boot set to $on")
                    // Turning it off only stops doing it; DuraSpeed itself returns on the next restart.
                    if (on) runFix { AutoStartFixes.turnDuraSpeedOff(it, "settings") } else updateList()
                }
            ))
        }

        // Phase three of #1122: the maker's own lists in /system, for formats that are known.
        val format = MakerWhitelistPolicy.detect(f.listFiles.map { it.path })
        if (format != null && s.makerList != null) {
            val applied = s.makerList == MakerWhitelistPolicy.Status.APPLIED
            items.add(SettingItem.StatusEntry(
                stableId = "checkMakerWhitelist",
                nameResId = R.string.auto_start_check_whitelist,
                state = if (!s.canAct) getString(R.string.auto_start_check_whitelist_needs_root)
                    else getString(when (s.makerList) {
                        MakerWhitelistPolicy.Status.NOT_APPLIED -> R.string.auto_start_check_whitelist_not_applied
                        MakerWhitelistPolicy.Status.PARTLY_APPLIED -> R.string.auto_start_check_whitelist_partly
                        MakerWhitelistPolicy.Status.APPLIED -> R.string.auto_start_check_whitelist_applied
                    }, format.name),
                tone = if (applied) good else problem,
                changesSystem = true,
                actionResId = if (applied) R.string.auto_start_check_whitelist_remove else R.string.auto_start_check_whitelist_apply,
                isEnabled = s.canAct,
                onAction = { if (applied) confirmRemove(format) else confirmApply(format) }
            ))
        }

        // Without root or ADB the log was not read, so an empty result proves nothing.
        val logRead = s.canAct
        val closers = f.stoppedBy.joinToString(", ")
        items.add(SettingItem.StatusEntry(
            stableId = "checkCloser",
            nameResId = R.string.auto_start_check_closer,
            state = closers.ifEmpty {
                getString(if (logRead && f.forceStops.isEmpty()) R.string.auto_start_check_closer_none
                else R.string.auto_start_check_unknown)
            },
            tone = if (s.verdict == AutoStartCheckPolicy.Verdict.FORCE_STOPPED) problem else SettingItem.StatusTone.NEUTRAL
        ))
    }

    /** What the unit is, after the causes: it explains the report, it does not fix anything. */
    private fun addDetails(items: MutableList<SettingItem>, f: AutoStartCheckPolicy.Findings, s: AutoStartCheckScreenPolicy.State) {
        items.add(SettingItem.CategoryHeader("checkDetailsHeader", R.string.auto_start_check_unit_details))
        items.add(row("checkDevice", R.string.auto_start_check_device, "${f.device} · Android ${f.android}"))
        items.add(row("checkPlatform", R.string.auto_start_check_platform, "${f.platform} · ${f.firmware}"))
        items.add(row("checkHome", R.string.auto_start_check_home_app, yesNo(f.isHomeApp)))
        items.add(SettingItem.SettingEntry(
            stableId = "checkLists",
            nameResId = R.string.auto_start_check_lists,
            value = getString(R.string.auto_start_check_lists_value,
                f.listFiles.size, f.listFiles.count { it.hasApp }),
            onClick = { showLists(f) }
        ))
        if (s.makerList != null) {
            items.add(SettingItem.InfoBanner("checkNotOffered", R.string.auto_start_check_not_offered))
        }
    }

    /** Applies every fix the main button stands for, then checks once to show the result. */
    private fun applyRecommended() {
        val f = findings ?: return
        val fixes = AutoStartCheckScreenPolicy.recommendedFixes(screenState(f))
        if (fixes.isEmpty()) return
        val settings = App.provide(requireContext()).settings
        if (AutoStartCheckScreenPolicy.Fix.DURASPEED_OFF in fixes) settings.duraSpeedOffOnBoot = true
        runFix { context ->
            var ok = true
            if (AutoStartCheckScreenPolicy.Fix.EXEMPTIONS in fixes) ok = AutoStartFixes.setExemptions(context, true) && ok
            if (AutoStartCheckScreenPolicy.Fix.DURASPEED_OFF in fixes) ok = AutoStartFixes.turnDuraSpeedOff(context, "settings") && ok
            ok
        }
    }

    /**
     * The rows arrive one after the other, 40 ms apart, rising a few dp. ViewPropertyAnimator
     * follows the system's animation scale, so with animations off they simply appear.
     */
    private fun cascadeRows() {
        val list = recyclerView
        list.post {
            val rise = resources.displayMetrics.density * CASCADE_RISE_DP
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                if (list.getChildAdapterPosition(child) == RecyclerView.NO_POSITION) continue
                if (child.id == R.id.verdictPanel) continue
                child.alpha = 0f
                child.translationY = rise
                child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * CASCADE_STEP_MS).setDuration(CASCADE_MS)
                    .setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
    }

    private fun confirmApply(format: MakerWhitelistPolicy.Format) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.auto_start_check_whitelist)
            .setMessage(getString(R.string.auto_start_check_whitelist_apply_message, format.files.size, format.name))
            .setNeutralButton(R.string.oem_app_btn_commands) { _, _ -> showCommands(format) }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.auto_start_check_whitelist_apply) { _, _ ->
                runWhitelistChange { MakerWhitelist.apply(it, format) }
            }
            .show()
    }

    private fun confirmRemove(format: MakerWhitelistPolicy.Format) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.auto_start_check_whitelist)
            .setMessage(getString(R.string.auto_start_check_whitelist_remove_message, format.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.auto_start_check_whitelist_remove) { _, _ ->
                runWhitelistChange { MakerWhitelist.remove(it, format) }
            }
            .show()
    }

    private fun showCommands(format: MakerWhitelistPolicy.Format) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.oem_app_commands_title)
            .setMessage(MakerWhitelist.describe(requireContext(), format))
            .setPositiveButton(R.string.close) { _, _ -> confirmApply(format) }
            .show()
    }

    private fun runWhitelistChange(change: suspend (Context) -> MakerWhitelist.Result) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = change(appContext)
            runCheck(keepList = true)
            val message = when (result) {
                MakerWhitelist.Result.DONE -> null
                MakerWhitelist.Result.NO_ROUTE -> R.string.auto_start_check_whitelist_needs_root
                MakerWhitelist.Result.VERITY -> R.string.auto_start_check_whitelist_failed_verity
                MakerWhitelist.Result.READ_FAILED -> R.string.auto_start_check_whitelist_failed_read
                MakerWhitelist.Result.REMOUNT_FAILED -> R.string.auto_start_check_whitelist_failed_remount
                MakerWhitelist.Result.WRITE_FAILED -> R.string.auto_start_check_whitelist_failed_write
            }
            if (!isAdded) return@launch
            if (message != null) {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.auto_start_check_whitelist)
                    .setMessage(message)
                    .setPositiveButton(R.string.close, null)
                    .show()
                return@launch
            }
            // The firmware reads its lists when it starts, so the change counts from the next boot.
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.auto_start_check_whitelist)
                .setMessage(R.string.auto_start_check_whitelist_done)
                .setNegativeButton(R.string.auto_start_check_later, null)
                .setPositiveButton(R.string.auto_start_check_restart_now) { _, _ ->
                    lifecycleScope.launch { MakerWhitelist.reboot(appContext) }
                }
                .show()
        }
    }

    /** Runs [fix], says so if it failed, and checks again so every switch shows the real state. */
    private fun runFix(fix: suspend (Context) -> Boolean) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            if (!fix(appContext)) {
                ToastUtils.showToast(context, getString(R.string.auto_start_check_fix_failed), Toast.LENGTH_LONG, force = true)
            }
            runCheck(keepList = true)
        }
    }

    private fun row(id: String, nameResId: Int, value: String) =
        SettingItem.SettingEntry(stableId = id, nameResId = nameResId, value = value, onClick = {})

    private fun yesNo(value: Boolean): String =
        getString(if (value) R.string.auto_start_check_yes else R.string.auto_start_check_no)

    private fun showLists(f: AutoStartCheckPolicy.Findings) {
        if (f.listFiles.isEmpty()) return
        val text = f.listFiles.joinToString("\n") { (if (it.hasApp) "✓ " else "· ") + it.path }
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.auto_start_check_lists)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun copyReport(f: AutoStartCheckPolicy.Findings) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.auto_start_check_title), AutoStartCheckPolicy.report(f)))
        ToastUtils.showToast(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT, force = true)
    }

    private fun shareReport(f: AutoStartCheckPolicy.Findings) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, AutoStartCheckPolicy.report(f))
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.auto_start_check_share)))
        } catch (e: Exception) {
            AppLog.w("AutoStartCheckFragment: no app to share with: ${e.message}")
            copyReport(f)
        }
    }

    private companion object {
        const val CASCADE_STEP_MS = 40L
        const val CASCADE_MS = 220L
        const val CASCADE_RISE_DP = 6f
    }
}
