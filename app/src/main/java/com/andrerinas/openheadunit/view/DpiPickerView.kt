package com.andrerinas.openheadunit.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.roundToInt

/**
 * Reusable DPI picker: a live [DpiPreviewView] graphic, a slider, an editable value field and
 * Small/Medium/Large tabs, all kept in sync. Used by the onboarding wizard and the DPI
 * settings sub-screen.
 *
 * The slider covers the full range of real device densities (up to [MAX]) so "Automatic"
 * values like 440 DPI are reachable. The text field lets the user type any exact value; the
 * tabs are handy presets and only light up when the current value matches one.
 */
@SuppressLint("ClickableViewAccessibility")
class DpiPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val preview: DpiPreviewView
    private val slider: Slider
    private val input: TextInputEditText
    private val tabs: MaterialButtonToggleGroup

    private var isBinding = false
    private var onDpiChanged: ((Int) -> Unit)? = null
    // onVisibilityChanged can be reached from View's own constructor, before the children below
    // have been found; nothing here is usable until init has finished.
    private var ready = false

    // Self-running demo that sweeps only the preview illustration (not the slider or the number), so
    // the user sees what DPI does before touching anything. It stops for good the moment the user
    // interacts with the DPI controls.
    private var demoAnimator: ValueAnimator? = null
    private var userInteracted = false
    // The "real" value (from init or a user action). The demo only animates the illustration and
    // never touches this, so [dpi] keeps returning the intended value even mid-sweep.
    private var committedDpi = MIN

    init {
        orientation = VERTICAL
        // Somewhere for focus to land when the value field gives it up: clearFocus() on the only
        // focusable view in a hierarchy hands focus straight back to it. This also keeps the field
        // from taking focus — and opening the keyboard — the moment the picker is first shown.
        isFocusableInTouchMode = true
        LayoutInflater.from(context).inflate(R.layout.view_dpi_picker, this, true)
        preview = findViewById(R.id.dpi_preview)
        slider = findViewById(R.id.dpi_slider)
        input = findViewById(R.id.dpi_input)
        tabs = findViewById(R.id.dpi_tabs)

        slider.addOnChangeListener { _, value, _ ->
            if (!isBinding) { stopDemo(userDriven = true); applyDpi(value.toInt(), fromSlider = true) }
        }
        // ACTION_DOWN also covers tapping the thumb on its current value (no value change), so the
        // demo still stops even when the touch does not move the slider.
        slider.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) stopDemo(userDriven = true)
            false
        }
        tabs.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked && !isBinding) {
                stopDemo(userDriven = true)
                val rep = when (checkedId) {
                    R.id.dpi_tab_small -> SMALL_REP
                    R.id.dpi_tab_large -> LARGE_REP
                    R.id.dpi_tab_huge -> HUGE_REP
                    else -> MEDIUM_REP
                }
                applyDpi(rep, fromSlider = false)
            }
        }
        // Consuming the action (returning true) skips TextView's own handling of DONE, which is
        // what would otherwise lower the keyboard — so the dismissal has to be explicit, and the
        // repo already prefers it that way (see hideKeyboard in SettingsFragment: some head units
        // will not put the keyboard away on their own). IME_NULL is the hardware Enter key and the
        // OEM keyboards that report no action id at all.
        input.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (actionId == EditorInfo.IME_NULL && (event == null || event.action == KeyEvent.ACTION_DOWN))
            if (done) { commitInput(); dismissKeyboard(); true } else false
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) stopDemo(userDriven = true) else commitInput()
        }

        // Initialise the field/preview/tab from the slider's default value.
        applyDpi(slider.value.toInt(), fromSlider = true, silent = true)
        ready = true
    }

    /**
     * The chosen DPI. A value typed into the field but not yet confirmed does not appear here —
     * call [commitPendingInput] first.
     *
     * The commit deliberately does not live in this getter: it would run
     * commitInput -> applyDpi -> onDpiChanged -> a listener that reads [dpi] -> commitInput, which
     * does not terminate.
     */
    var dpi: Int
        // While the demo sweeps, report the intended value, not the animated frame.
        get() = if (demoAnimator != null) committedDpi else slider.value.toInt()
        set(value) = applyDpi(value, fromSlider = false, silent = true)

    val hasUserInteracted: Boolean
        get() = userInteracted

    fun setOnDpiChanged(cb: (Int) -> Unit) { onDpiChanged = cb }

    /**
     * Folds a value typed into the field into the picker. Call this before reading [dpi] from a
     * button handler: buttons do not take focus in touch mode, so tapping Next or Save never moves
     * focus off the field and the typed number would otherwise be dropped. Safe to call repeatedly.
     */
    fun commitPendingInput() = commitInput()

    fun setPanelResolution(widthPx: Int, heightPx: Int) = preview.setPanelResolution(widthPx, heightPx)

    /**
     * Starts a gentle looping demo that sweeps only the preview illustration through the DPI range,
     * so the graphic shows what DPI does while the slider and number stay put. No-op once the user
     * has interacted, or if the picker is disabled. Safe to call repeatedly (e.g. each time the
     * step/screen becomes visible).
     */
    fun startDemo() {
        if (userInteracted || demoAnimator != null || !slider.isEnabled) return
        demoAnimator = ValueAnimator.ofFloat(DEMO_LOW.toFloat(), DEMO_HIGH.toFloat()).apply {
            duration = DEMO_SWEEP_MS
            startDelay = DEMO_START_DELAY_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                // Only the illustration animates; the slider and the DPI number stay at the real value.
                preview.setDpi((anim.animatedValue as Float).toInt())
            }
            start()
        }
    }

    /** Stops the demo (if running) without counting as user interaction, e.g. when the screen is
     * hidden; the illustration is restored to the intended value. */
    fun stopDemo() = stopDemo(userDriven = false)

    private fun stopDemo(userDriven: Boolean) {
        if (userDriven) userInteracted = true
        val anim = demoAnimator ?: return
        demoAnimator = null
        anim.cancel()
        // Resync the illustration to the real DPI (the slider and number never moved during the demo).
        preview.setDpi(committedDpi)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopDemo(userDriven = false)
    }

    /**
     * The wizard swaps steps through a ViewFlipper and the DPI screen hides the picker when
     * Automatic is switched on, so both leave through here: keep whatever was typed, and do not
     * leave the keyboard covering the screen that comes next.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready && visibility != VISIBLE) {
            commitPendingInput()
            dismissKeyboard()
        }
    }

    private fun dismissKeyboard() {
        // No token once the view is off the window, and this is also reached from teardown.
        windowToken?.let { token ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(token, 0)
        }
        input.clearFocus()
    }

    fun setPickerEnabled(enabled: Boolean) {
        slider.isEnabled = enabled
        input.isEnabled = enabled
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
        if (!enabled) stopDemo(userDriven = false)
    }

    /** Parse the typed value, clamp it into range and apply; restore on invalid input. */
    private fun commitInput() {
        if (isBinding) return
        val typed = input.text?.toString()?.trim()?.toIntOrNull()
        if (typed == null) {
            isBinding = true
            input.setText(slider.value.toInt().toString())
            isBinding = false
            return
        }
        applyDpi(typed, fromSlider = false)
    }

    private fun applyDpi(raw: Int, fromSlider: Boolean, silent: Boolean = false) {
        val v = snap(raw)
        committedDpi = v
        isBinding = true
        if (!fromSlider) slider.value = v.toFloat()
        tabs.check(tabFor(v))
        preview.setDpi(v)
        if (input.text?.toString() != v.toString()) input.setText(v.toString())
        isBinding = false
        if (!silent) onDpiChanged?.invoke(v)
    }

    /** Rounds to a value the slider can actually hold, then clamps into range. */
    private fun snap(value: Int): Int {
        val stepped = ((value - MIN) / STEP.toFloat()).roundToInt() * STEP + MIN
        return stepped.coerceIn(MIN, MAX)
    }

    /**
     * Classifies any value into a tab. Small covers everything below and Huge everything
     * above (so a high custom value like 440 reads as Huge); Medium and Large stay capped to
     * their narrow central bands.
     */
    private fun tabFor(v: Int): Int = when {
        v <= SMALL_MEDIUM_MAX -> R.id.dpi_tab_small
        v <= MEDIUM_LARGE_MAX -> R.id.dpi_tab_medium
        v <= LARGE_HUGE_MAX -> R.id.dpi_tab_large
        else -> R.id.dpi_tab_huge
    }

    companion object {
        private const val MIN = 110
        private const val MAX = 640
        // Must stay equal to android:stepSize on dpi_slider in view_dpi_picker.xml: applyDpi
        // assigns slider.value, and a Slider throws IllegalStateException for a value that is not
        // valueFrom plus a whole number of steps. 1 so a typed 175 stays 175 rather than snapping.
        private const val STEP = 1
        private const val SMALL_REP = 132
        private const val MEDIUM_REP = 175
        private const val LARGE_REP = 218
        private const val HUGE_REP = 320
        // Small covers everything below and Huge everything above; Medium and Large are
        // the narrow central bands. Boundaries are the midpoints between representatives.
        private const val SMALL_MEDIUM_MAX = (SMALL_REP + MEDIUM_REP) / 2   // 153
        private const val MEDIUM_LARGE_MAX = (MEDIUM_REP + LARGE_REP) / 2   // 196
        private const val LARGE_HUGE_MAX = (LARGE_REP + HUGE_REP) / 2       // 269

        // Demo sweep range and timing: the illustration alone sweeps the full 160..640 DPI range so
        // the effect on how much content fits is clearly visible.
        private const val DEMO_LOW = 160
        private const val DEMO_HIGH = 640
        private const val DEMO_SWEEP_MS = 3200L
        // No wait: the illustration should start sweeping as soon as the step is shown.
        private const val DEMO_START_DELAY_MS = 0L
    }
}
