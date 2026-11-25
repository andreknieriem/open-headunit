package com.andrerinas.openheadunit.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingsDrawables
import com.andrerinas.openheadunit.main.settings.SettingsPalette
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Choice
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Pane
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Pick
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText

/**
 * Two steps for the Android Auto density: a layout card, then a text size or Auto. A slider and a
 * number field sit under Advanced. A preview shows the result, beside the controls on a wide screen.
 * It holds no rule: [DisplayLayoutPolicy] answers every question. Used by the wizard and the DPI screen.
 */
@SuppressLint("ClickableViewAccessibility")
class DpiLayoutPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val controls: View
    private val previewCard: MaterialCardView
    private val cards: LinearLayout
    private val sketch: DpiLayoutSketchView
    private val sizes: MaterialButtonToggleGroup
    private val advancedToggle: MaterialButton
    private val advanced: View
    private val slider: Slider
    private val input: TextInputEditText
    private val expected: TextView
    private val edgeNote: TextView

    private val palette = SettingsPalette.from(context)
    private val twoPane = resources.getBoolean(R.bool.dpi_picker_two_pane)
    private val cardViews = ArrayList<MaterialCardView>()
    private var builtSteps: List<Int>? = null
    private var advancedOpen = false

    private var cw = 0
    private var ch = 0
    private var clamp = 0
    private var portrait = false
    private var choices: List<Choice> = emptyList()
    private var pane: Pane? = null
    private var auto = true
    private var manualDpi = DisplayLayoutPolicy.SLIDER_MIN
    private var userInteracted = false
    private var isBinding = false
    private var onPickChanged: (() -> Unit)? = null
    // onVisibilityChanged can run from View's constructor, before the children are found.
    private var ready = false

    init {
        // Focus lands here when the number field gives it up, so the keyboard does not open on show.
        isFocusableInTouchMode = true
        LayoutInflater.from(context).inflate(R.layout.view_dpi_layout_picker, this, true)
        controls = findViewById(R.id.dpi_controls)
        previewCard = findViewById(R.id.dpi_preview_card)
        cards = findViewById(R.id.dpi_cards)
        sketch = findViewById(R.id.dpi_sketch)
        sizes = findViewById(R.id.dpi_sizes)
        advancedToggle = findViewById(R.id.dpi_advanced_toggle)
        advanced = findViewById(R.id.dpi_advanced)
        slider = findViewById(R.id.dpi_slider)
        input = findViewById(R.id.dpi_input)
        expected = findViewById(R.id.dpi_expected)
        edgeNote = findViewById(R.id.dpi_edge_note)
        arrange()

        slider.addOnChangeListener { _, value, _ ->
            if (!isBinding) setManual(value.toInt())
        }
        // A touch on the thumb at its current value moves nothing, but still counts as a pick.
        slider.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) userInteracted = true
            false
        }
        sizes.addOnButtonCheckedListener { _, id, checked ->
            if (isBinding) return@addOnButtonCheckedListener
            if (!checked) { post { render() }; return@addOnButtonCheckedListener }
            val tag = findViewById<View>(id)?.tag
            if (tag == AUTO_TAG) setAuto() else if (tag is Int) setManual(tag)
        }
        advancedToggle.setOnClickListener {
            if (advancedOpen) { commitInput(); dismissKeyboard() }
            advancedOpen = !advancedOpen
            renderAdvanced()
        }
        // Consuming DONE skips TextView's own handling, so the keyboard is lowered by hand: some
        // head units never lower it. IME_NULL is the hardware Enter key.
        input.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (actionId == EditorInfo.IME_NULL && (event == null || event.action == KeyEvent.ACTION_DOWN))
            if (done) { commitInput(); dismissKeyboard(); true } else false
        }
        input.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) userInteracted = true else commitInput() }
        ready = true
    }

    /** The current pick. A number typed and not yet confirmed is not in it: call [commitPendingInput] first. */
    val pick: Pick
        get() {
            val p = pane
            return if (auto && p != null) Pick.Auto(p) else Pick.Manual(manualDpi)
        }

    val hasUserInteracted: Boolean get() = userInteracted

    /** The panes the screen reaches, one card each. */
    val reachablePanes: List<Pane> get() = choices.map { it.pane }

    fun setOnPickChanged(cb: () -> Unit) { onPickChanged = cb }

    /** Folds a typed number into the pick; buttons take no focus, so Next or Save never would. */
    fun commitPendingInput() = commitInput()

    /**
     * Shows the layouts the screen can reach. [storedDpi] 0 opens on Auto inside [openPane];
     * a number opens on that density. [fresh] forgets any earlier interaction.
     */
    fun configure(cw: Int, ch: Int, clamp: Int, portrait: Boolean, storedDpi: Int, openPane: Pane?, fresh: Boolean) {
        this.cw = cw
        this.ch = ch
        this.clamp = clamp
        this.portrait = portrait
        choices = DisplayLayoutPolicy.choices(cw, ch, clamp)
        if (fresh) userInteracted = false
        auto = storedDpi <= 0 && choices.isNotEmpty()
        manualDpi = if (storedDpi > 0) storedDpi else (choices.firstOrNull()?.let { DisplayLayoutPolicy.autoDensity(it) } ?: DisplayLayoutPolicy.SLIDER_MIN)
        pane = if (auto) openPane?.takeIf { p -> choices.any { it.pane == p } } ?: choices.firstOrNull()?.pane
        else DisplayLayoutPolicy.layoutOf(cw, ch, manualDpi).pane?.takeIf { p -> choices.any { it.pane == p } } ?: choices.firstOrNull()?.pane
        // A density no size button names is visible only on the slider, so open it.
        val onStep = choiceOf(pane)?.let { manualDpi in DisplayLayoutPolicy.sizeSteps(it) } ?: false
        if (!auto && !onStep) advancedOpen = true
        val max = DisplayLayoutPolicy.sliderMax(portrait, clamp).coerceAtLeast(DisplayLayoutPolicy.SLIDER_MIN + 1)
        isBinding = true
        // A Slider throws when its value is above valueTo, so drop it to valueFrom first.
        slider.value = slider.valueFrom
        slider.valueTo = max.toFloat()
        isBinding = false
        buildCards()
        render()
    }

    /** One column with the preview on top, or the preview beside the controls on a wide screen. */
    private fun arrange() {
        orientation = if (twoPane) HORIZONTAL else VERTICAL
        isBaselineAligned = false
        if (twoPane) {
            removeView(previewCard)
            addView(previewCard)
            controls.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
            previewCard.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply {
                MarginLayoutParamsCompat.setMarginStart(this, dp(24))
            }
        } else {
            (previewCard.layoutParams as LayoutParams).bottomMargin = dp(16)
        }
        sketch.maxHeightPx = dp(if (twoPane) 240 else 160)
        previewCard.setCardBackgroundColor(palette.container)
        previewCard.strokeColor = palette.outlineVariant
        previewCard.strokeWidth = if (palette.outlinedGroups) dp(1) else 0
        edgeNote.setTextColor(palette.onSurfaceVariant)
        val tint = ColorStateList.valueOf(palette.primary)
        advancedToggle.setTextColor(tint)
        advancedToggle.iconTint = tint
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun choiceOf(p: Pane?): Choice? = choices.firstOrNull { it.pane == p }

    /** The density the pick sends before any clamp. */
    private fun effectiveDpi(): Int {
        val c = choiceOf(pane)
        return if (auto && c != null) DisplayLayoutPolicy.autoDensity(c) else manualDpi
    }

    private fun pickPane(p: Pane) {
        userInteracted = true
        val wasStep = choiceOf(pane)?.let { DisplayLayoutPolicy.sizeSteps(it).indexOf(manualDpi) } ?: -1
        pane = p
        val c = choiceOf(p) ?: return
        if (!auto) {
            val steps = DisplayLayoutPolicy.sizeSteps(c)
            // A card tap keeps the size step; a free number goes to the new layout's large step.
            manualDpi = if (wasStep >= 0) steps[wasStep.coerceAtMost(steps.size - 1)] else DisplayLayoutPolicy.autoDensity(c)
        }
        render()
        onPickChanged?.invoke()
    }

    private fun setAuto() {
        userInteracted = true
        if (choiceOf(pane) == null) { render(); return }
        auto = true
        render()
        onPickChanged?.invoke()
    }

    private fun setManual(dpi: Int) {
        userInteracted = true
        auto = false
        manualDpi = dpi.coerceIn(DisplayLayoutPolicy.SLIDER_MIN, DisplayLayoutPolicy.sliderMax(portrait, clamp))
        DisplayLayoutPolicy.layoutOf(cw, ch, manualDpi).pane?.takeIf { choiceOf(it) != null }?.let { pane = it }
        render()
        onPickChanged?.invoke()
    }

    /** Cards share a row at equal width; a narrow screen wraps three or four into rows of two. */
    private fun columnsFor(count: Int): Int {
        if (count <= 2) return count
        val available = (resources.configuration.screenWidthDp - 48) * (if (twoPane) 3 else 5) / 5
        return if (available / count >= MIN_CARD_WIDTH_DP) count else 2
    }

    private fun buildCards() {
        cards.removeAllViews()
        cardViews.clear()
        if (choices.isEmpty()) return
        val columns = columnsFor(choices.size)
        val inflater = LayoutInflater.from(context)
        choices.chunked(columns).forEachIndexed { rowIndex, rowChoices ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                isBaselineAligned = false
            }
            rowChoices.forEachIndexed { i, c ->
                val card = inflater.inflate(R.layout.view_dpi_layout_card, row, false) as MaterialCardView
                card.tag = c.pane
                card.findViewById<TextView>(R.id.dpi_card_label).text = paneLabel(context, c.pane)
                card.findViewById<DpiLayoutSketchView>(R.id.dpi_card_thumb).apply {
                    // A lone card would repeat the preview, so it shows its name only.
                    visibility = if (choices.size > 1) VISIBLE else GONE
                    maxHeightPx = dp(THUMB_MAX_HEIGHT_DP)
                    setLayout(DisplayLayoutPolicy.layoutOf(cw, ch, c.high), DisplayLayoutPolicy.dp(cw, c.high), DisplayLayoutPolicy.dp(ch, c.high))
                }
                // The only layout a screen reaches is shown, not offered.
                if (choices.size > 1) card.setOnClickListener { pickPane(c.pane) } else {
                    card.isClickable = false
                    card.isFocusable = false
                }
                if (i > 0) MarginLayoutParamsCompat.setMarginStart(card.layoutParams as LayoutParams, dp(8))
                row.addView(card)
                cardViews += card
            }
            // An empty cell keeps a short last row at the width of the rows above.
            repeat(columns - rowChoices.size) {
                row.addView(Space(context), LayoutParams(0, 0, 1f).apply { MarginLayoutParamsCompat.setMarginStart(this, dp(8)) })
            }
            cards.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = dp(8)
            })
        }
    }

    private fun render() {
        isBinding = true
        val d = effectiveDpi()
        val c = choiceOf(pane)
        for (card in cardViews) renderCard(card, card.tag == pane)
        sketch.setLayout(DisplayLayoutPolicy.layoutOf(cw, ch, d), DisplayLayoutPolicy.dp(cw, d), DisplayLayoutPolicy.dp(ch, d))
        renderSizeButtons(c)
        val max = slider.valueTo.toInt()
        slider.value = d.coerceIn(DisplayLayoutPolicy.SLIDER_MIN, max).toFloat()
        if (input.text?.toString() != d.toString()) input.setText(d.toString())
        val name = DisplayLayoutPolicy.nameFor(cw, ch, d, clamp)
        expected.text = describe(context, name)?.let { context.getString(R.string.dpi_expected_format, it) }
            ?: context.getString(R.string.dpi_value_format, d)
        edgeNote.visibility = if (c != null && DisplayLayoutPolicy.sizeSteps(c).size > 1) VISIBLE else GONE
        renderAdvanced()
        isBinding = false
    }

    /** The picked card takes the selected container colour, a primary outline and a check by its name. */
    private fun renderCard(card: MaterialCardView, on: Boolean) {
        card.isChecked = on
        card.setCardBackgroundColor(if (on) palette.secondaryContainer else palette.surface)
        card.strokeColor = if (on) palette.primary else palette.outline
        card.strokeWidth = dp(if (on) 2 else 1)
        val label = card.findViewById<TextView>(R.id.dpi_card_label)
        val color = if (on) palette.onSecondaryContainer else palette.onSurface
        label.setTextColor(color)
        val check = if (on) AppCompatResources.getDrawable(context, R.drawable.ic_sym_check)?.let {
            DrawableCompat.wrap(it.mutate()).apply {
                DrawableCompat.setTint(this, color)
                setBounds(0, 0, dp(18), dp(18))
            }
        } else null
        TextViewCompat.setCompoundDrawablesRelative(label, check, null, null, null)
    }

    private fun renderSizeButtons(c: Choice?) {
        val steps = c?.let { DisplayLayoutPolicy.sizeSteps(it) } ?: emptyList()
        if (steps != builtSteps) buildSizeButtons(steps, c != null)
        var checkedId = View.NO_ID
        for (i in 0 until sizes.childCount) {
            val b = sizes.getChildAt(i) as MaterialButton
            val on = if (b.tag == AUTO_TAG) auto else !auto && b.tag == manualDpi
            if (on) { checkedId = b.id; b.setIconResource(R.drawable.ic_sym_check) } else b.icon = null
        }
        sizes.clearChecked()
        if (checkedId != View.NO_ID) sizes.check(checkedId)
    }

    private fun buildSizeButtons(steps: List<Int>, withAuto: Boolean) {
        builtSteps = steps
        sizes.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val background = SettingsDrawables.checkedColors(palette.secondaryContainer, Color.TRANSPARENT)
        val text = SettingsDrawables.checkedColors(palette.onSecondaryContainer, palette.onSurface)
        fun add(label: String, tag: Any) {
            val b = inflater.inflate(R.layout.view_dpi_size_button, sizes, false) as MaterialButton
            b.id = ViewCompat.generateViewId()
            b.text = label
            b.tag = tag
            b.backgroundTintList = background
            b.setTextColor(text)
            b.iconTint = text
            b.strokeColor = ColorStateList.valueOf(palette.outline)
            b.rippleColor = ColorStateList.valueOf(palette.secondaryContainer)
            sizes.addView(b)
        }
        steps.forEachIndexed { i, step -> add(stepLabel(context, i), step) }
        if (withAuto) add(context.getString(R.string.auto), AUTO_TAG)
        sizes.visibility = if (sizes.childCount > 0) VISIBLE else GONE
    }

    private fun renderAdvanced() {
        advanced.visibility = if (advancedOpen) VISIBLE else GONE
        advancedToggle.setIconResource(if (advancedOpen) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down)
    }

    /** Parses the typed number, clamps it into the slider's range and applies it; restores on bad input. */
    private fun commitInput() {
        if (isBinding || !ready) return
        val typed = input.text?.toString()?.trim()?.toIntOrNull()
        if (typed == null || typed == effectiveDpi()) {
            if (typed == null) { isBinding = true; input.setText(effectiveDpi().toString()); isBinding = false }
            return
        }
        setManual(typed)
    }

    /** The wizard swaps steps through a ViewFlipper: keep what was typed, and lower the keyboard. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready && visibility != VISIBLE) {
            commitPendingInput()
            dismissKeyboard()
        }
    }

    private fun dismissKeyboard() {
        windowToken?.let { token ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(token, 0)
        }
        input.clearFocus()
    }

    companion object {
        private const val AUTO_TAG = "auto"
        private const val MIN_CARD_WIDTH_DP = 132
        private const val THUMB_MAX_HEIGHT_DP = 96

        fun paneLabel(context: Context, pane: Pane): String = context.getString(
            when (pane) {
                Pane.MAP_AND_MEDIA -> R.string.dpi_pane_map_and_media
                Pane.ONE_APP -> R.string.dpi_pane_one_app
                Pane.COMPACT -> R.string.dpi_pane_compact
                Pane.MAP_OVER_MEDIA -> R.string.dpi_pane_map_over_media
            }
        )

        fun stepLabel(context: Context, index: Int): String = context.getString(
            when (index) {
                0 -> R.string.onb_dpi_large
                1 -> R.string.onb_dpi_medium
                else -> R.string.onb_dpi_small
            }
        )

        /** "Map and media, Large" for a derived name; null for a layout no card names. */
        fun describe(context: Context, name: DisplayLayoutPolicy.Name): String? {
            val pane = name.pane ?: return null
            val step = name.stepIndex ?: return paneLabel(context, pane)
            return paneLabel(context, pane) + ", " + stepLabel(context, step)
        }
    }
}
