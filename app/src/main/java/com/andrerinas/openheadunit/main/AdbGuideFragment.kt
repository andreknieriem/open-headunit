package com.andrerinas.openheadunit.main

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.HiddenMenuCodes
import com.andrerinas.openheadunit.main.settings.SettingsDrawables
import com.andrerinas.openheadunit.main.settings.SettingsPalette
import com.andrerinas.openheadunit.main.settings.styleFilledButton
import com.andrerinas.openheadunit.utils.AppLog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat

/**
 * How to turn on ADB over Wi-Fi from the maker's hidden menu, opened from the auto-start check
 * when it has no root or ADB (#1122). The known codes ship with the app because many head units
 * have no internet; a QR code leads to the full list for a phone to open.
 */
class AdbGuideFragment : Fragment() {

    private lateinit var colors: SettingsPalette
    private var codesTab = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_adb_guide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        colors = SettingsPalette.from(context)
        codesTab = savedInstanceState?.getBoolean(STATE_CODES_TAB) ?: false

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { findNavController().navigateUp() }

        addSteps(view)
        styleWarning(view)
        bindMatch(view, arguments?.getStringArrayList(ARG_REPORTED).orEmpty())
        bindSearch(view)
        styleFooter(view)

        val again = view.findViewById<MaterialButton>(R.id.adbGuideAgain)
        styleFilledButton(again, colors)
        again.setOnClickListener {
            AppLog.i("AdbGuideFragment: back to the check after the guide")
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_CHECK_AGAIN to true))
            findNavController().navigateUp()
        }

        view.findViewById<View?>(R.id.adbGuideCodesCard)?.background =
            SettingsDrawables.box(context, colors.container, resources.getDimension(R.dimen.settings_group_radius), colors)
        val tabs = view.findViewById<View?>(R.id.adbGuideTabs)
        if (tabs != null) {
            // The tabs already name both panes.
            view.findViewById<View>(R.id.adbGuideStepsHeader).visibility = View.GONE
            view.findViewById<View>(R.id.adbGuideCodesHeader).visibility = View.GONE
            tabs.background = SettingsDrawables.box(context, colors.container, SettingsDrawables.dp(context, 24f), colors)
            view.findViewById<View>(R.id.adbGuideTabSteps).setOnClickListener { showTab(view, codes = false, animate = true) }
            view.findViewById<View>(R.id.adbGuideTabCodes).setOnClickListener { showTab(view, codes = true, animate = true) }
            showTab(view, codesTab, animate = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CODES_TAB, codesTab)
    }

    private fun addSteps(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.adbGuideStepList)
        val inflater = LayoutInflater.from(view.context)
        STEPS.forEachIndexed { i, text ->
            val row = inflater.inflate(R.layout.item_adb_guide_step, list, false)
            val number = row.findViewById<TextView>(R.id.stepNumber)
            number.text = NumberFormat.getIntegerInstance().format(i + 1)
            number.background = SettingsDrawables.dot(colors.secondaryContainer)
            number.setTextColor(colors.onSecondaryContainer)
            row.findViewById<TextView>(R.id.stepText).setText(text)
            list.addView(row)
        }
    }

    private fun styleWarning(view: View) {
        val context = view.context
        val problem = ContextCompat.getColor(context, R.color.settings_status_problem)
        val container = ContextCompat.getColor(context, R.color.settings_status_problem_container)
        view.findViewById<View>(R.id.adbGuideWarning).background =
            SettingsDrawables.box(context, container, resources.getDimension(R.dimen.settings_group_radius), colors)
        view.findViewById<ImageView>(R.id.adbGuideWarningIcon)
            .setImageDrawable(SettingsDrawables.tinted(context, R.drawable.ic_warning_white, problem))
        view.findViewById<TextView>(R.id.adbGuideWarningText).setTextColor(problem)
    }

    /** The code that matches what the unit reports, if any, on top of the list. */
    private fun bindMatch(view: View, reported: List<String>) {
        val card = view.findViewById<View>(R.id.adbGuideMatch)
        val match = HiddenMenuCodes.forUnit(reported).firstOrNull()
        if (match == null) {
            card.visibility = View.GONE
            return
        }
        AppLog.i("AdbGuideFragment: code ${match.code} matches this unit")
        val context = view.context
        card.background = SettingsDrawables.box(context, colors.secondaryContainer, resources.getDimension(R.dimen.settings_group_radius), colors)
        view.findViewById<TextView>(R.id.adbGuideMatchLabel).setTextColor(colors.onSecondaryContainer)
        view.findViewById<TextView>(R.id.adbGuideMatchUnits).text = units(match)
        view.findViewById<TextView>(R.id.adbGuideMatchOpens).setText(opens(match.opens))
        digits(view.findViewById(R.id.adbGuideMatchCode), match.code)
        badge(view.findViewById(R.id.adbGuideMatchBadge), match.tested)
    }

    private fun bindSearch(view: View) {
        val context = view.context
        view.findViewById<View>(R.id.adbGuideSearchBox).background =
            SettingsDrawables.searchField(context, colors, SettingsDrawables.dp(context, 24f))
        view.findViewById<ImageView>(R.id.adbGuideSearchIcon)
            .setImageDrawable(SettingsDrawables.tinted(context, R.drawable.ic_sym_search, colors.onSurfaceVariant))
        val search = view.findViewById<EditText>(R.id.adbGuideSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                showCodes(view, s?.toString().orEmpty(), animate = true)
            }
        })
        showCodes(view, search.text.toString(), animate = false)
    }

    private fun showCodes(view: View, query: String, animate: Boolean) {
        val list = view.findViewById<LinearLayout>(R.id.adbGuideCodeList)
        val empty = view.findViewById<TextView>(R.id.adbGuideNoResults)
        val codes = HiddenMenuCodes.search(query)
        list.removeAllViews()
        val inflater = LayoutInflater.from(view.context)
        codes.forEachIndexed { i, code ->
            if (i > 0) list.addView(divider(view))
            val row = inflater.inflate(R.layout.item_hidden_menu_code, list, false)
            digits(row.findViewById(R.id.codeDigits), code.code)
            row.findViewById<TextView>(R.id.codeUnits).text = units(code)
            row.findViewById<TextView>(R.id.codeOpens).setText(opens(code.opens))
            badge(row.findViewById(R.id.codeBadge), code.tested)
            list.addView(row)
        }
        empty.visibility = if (codes.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(R.string.adb_guide_no_results, query.trim())
        // A short fade shows the list changed; animators follow the system's animation scale.
        if (animate) {
            list.alpha = 0f
            list.animate().alpha(1f).setDuration(LIST_FADE_MS).start()
        }
    }

    private fun styleFooter(view: View) {
        view.findViewById<View>(R.id.adbGuideFooterLine).setBackgroundColor(colors.outlineVariant)
        // The QR is black on white in day and night, so any phone camera reads it.
        view.findViewById<View>(R.id.adbGuideQrBox).background = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = SettingsDrawables.dp(view.context, 8f)
        }
    }

    private fun showTab(view: View, codes: Boolean, animate: Boolean) {
        val changed = codes != codesTab
        codesTab = codes
        val steps = view.findViewById<ScrollView>(R.id.adbGuideStepsScroll)
        val list = view.findViewById<ScrollView>(R.id.adbGuideCodesScroll)
        steps.visibility = if (codes) View.GONE else View.VISIBLE
        list.visibility = if (codes) View.VISIBLE else View.GONE
        for ((tab, selected) in listOf(R.id.adbGuideTabSteps to !codes, R.id.adbGuideTabCodes to codes)) {
            val t = view.findViewById<TextView>(tab)
            t.isSelected = selected
            t.background = if (selected) SettingsDrawables.box(view.context, colors.primary, SettingsDrawables.dp(view.context, 20f), colors) else null
            t.setTextColor(if (selected) colors.onPrimary else colors.onSurfaceVariant)
        }
        // The new tab slides in from the side its button is on.
        if (animate && changed) {
            val shown = if (codes) list else steps
            shown.alpha = 0f
            shown.translationX = SettingsDrawables.dp(view.context, if (codes) TAB_SHIFT_DP else -TAB_SHIFT_DP)
            shown.animate().alpha(1f).translationX(0f).setDuration(TAB_MS)
                .setInterpolator(FastOutSlowInInterpolator()).start()
        }
    }

    private fun divider(view: View) = View(view.context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(colors.outlineVariant)
    }

    private fun units(code: HiddenMenuCodes.Code) = code.units ?: getString(R.string.adb_guide_generic_units)

    private fun opens(opens: HiddenMenuCodes.Opens) = when (opens) {
        HiddenMenuCodes.Opens.ADB_MENU -> R.string.adb_guide_opens_adb_menu
        HiddenMenuCodes.Opens.DEVELOPER_OPTIONS -> R.string.adb_guide_opens_developer
        HiddenMenuCodes.Opens.FACTORY_SETTINGS -> R.string.adb_guide_opens_factory
    }

    /** Digits that line up and read one by one, as on the unit's keypad. */
    private fun digits(view: TextView, code: String) {
        view.text = code
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.fontFeatureSettings = "tnum"
            view.letterSpacing = DIGIT_SPACING
        }
    }

    private fun badge(view: TextView, tested: Boolean) {
        val context = view.context
        view.setText(if (tested) R.string.adb_guide_tested else R.string.adb_guide_reported)
        if (tested) {
            view.background = SettingsDrawables.box(context, colors.primary, SettingsDrawables.dp(context, 12f), colors)
            view.setTextColor(colors.onPrimary)
        } else {
            view.background = SettingsDrawables.outlinedPill(context, colors.outline)
            view.setTextColor(colors.onSurfaceVariant)
        }
    }

    companion object {
        const val ARG_REPORTED = "reported"
        const val RESULT_KEY = "adbGuide"
        const val RESULT_CHECK_AGAIN = "checkAgain"

        /** What the unit reports about itself (device, platform, firmware), to find its code. */
        fun args(reported: List<String>) = bundleOf(ARG_REPORTED to ArrayList(reported))

        private const val STATE_CODES_TAB = "codesTab"
        private const val LIST_FADE_MS = 120L
        private const val TAB_MS = 220L
        private const val TAB_SHIFT_DP = 24f
        private const val DIGIT_SPACING = 0.06f

        private val STEPS = listOf(
            R.string.adb_guide_step_about,
            R.string.adb_guide_step_tap,
            R.string.adb_guide_step_code,
            R.string.adb_guide_step_switch,
            R.string.adb_guide_step_back
        )
    }
}
