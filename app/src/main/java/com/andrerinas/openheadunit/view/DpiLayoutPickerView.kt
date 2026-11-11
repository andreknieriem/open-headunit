package com.andrerinas.openheadunit.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.ViewCompat
import com.andrerinas.openheadunit.R
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
 * Two steps for the Android Auto density: a layout card, then a text size, a slider or Auto.
 * It holds no rule: [DisplayLayoutPolicy] answers every question. Used by the wizard and the DPI screen.
 */
@SuppressLint("ClickableViewAccessibility")
class DpiLayoutPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val cardsScroll: HorizontalScrollView
    private val cards: LinearLayout
    private val singleLabel: TextView
    private val sketch: DpiLayoutSketchView
    private val sizes: MaterialButtonToggleGroup
    private val slider: Slider
    private val input: TextInputEditText
    private val expected: TextView
    private val edgeNote: TextView

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
        orientation = VERTICAL
        // Focus lands here when the number field gives it up, so the keyboard does not open on show.
        isFocusableInTouchMode = true
        LayoutInflater.from(context).inflate(R.layout.view_dpi_layout_picker, this, true)
        cardsScroll = findViewById(R.id.dpi_cards_scroll)
        cards = findViewById(R.id.dpi_cards)
        singleLabel = findViewById(R.id.dpi_single_label)
        sketch = findViewById(R.id.dpi_sketch)
        sizes = findViewById(R.id.dpi_sizes)
        slider = findViewById(R.id.dpi_slider)
        input = findViewById(R.id.dpi_input)
        expected = findViewById(R.id.dpi_expected)
        edgeNote = findViewById(R.id.dpi_edge_note)

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
        val max = DisplayLayoutPolicy.sliderMax(portrait, clamp).coerceAtLeast(DisplayLayoutPolicy.SLIDER_MIN + 1)
        isBinding = true
        // A Slider throws when its value is above valueTo, so drop it to valueFrom first.
        slider.value = slider.valueFrom
        slider.valueTo = max.toFloat()
        isBinding = false
        buildCards()
        render()
    }

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

    private fun buildCards() {
        cards.removeAllViews()
        val single = choices.size <= 1
        cardsScroll.visibility = if (single) GONE else VISIBLE
        singleLabel.visibility = if (single && choices.isNotEmpty()) VISIBLE else GONE
        singleLabel.text = choices.firstOrNull()?.let { paneLabel(context, it.pane) } ?: ""
        if (single) return
        val density = resources.displayMetrics.density
        for (c in choices) {
            val card = MaterialCardView(context).apply {
                tag = c.pane
                isClickable = true
                isFocusable = true
                radius = 8 * density
                layoutParams = LinearLayout.LayoutParams((CARD_WIDTH_DP * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    MarginLayoutParamsCompat.setMarginEnd(this, (8 * density).toInt())
                }
                setOnClickListener { pickPane(c.pane) }
            }
            val body = LinearLayout(context).apply {
                orientation = VERTICAL
                val pad = (8 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val thumb = DpiLayoutSketchView(context).apply {
                val layout = DisplayLayoutPolicy.layoutOf(cw, ch, c.high)
                setLayout(layout, DisplayLayoutPolicy.dp(cw, c.high), DisplayLayoutPolicy.dp(ch, c.high))
            }
            body.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(TextView(context).apply {
                text = paneLabel(context, c.pane)
                gravity = android.view.Gravity.CENTER
                setPadding(0, (6 * density).toInt(), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(body)
            cards.addView(card)
        }
    }

    private fun render() {
        isBinding = true
        val d = effectiveDpi()
        val c = choiceOf(pane)
        for (i in 0 until cards.childCount) {
            val card = cards.getChildAt(i) as MaterialCardView
            val on = card.tag == pane
            card.strokeWidth = ((if (on) 3 else 1) * resources.displayMetrics.density).toInt()
            card.strokeColor = if (on) themeColor(com.google.android.material.R.attr.colorPrimary) else themeColor(android.R.attr.textColorSecondary)
        }
        sketch.setLayout(DisplayLayoutPolicy.layoutOf(cw, ch, d), DisplayLayoutPolicy.dp(cw, d), DisplayLayoutPolicy.dp(ch, d))
        buildSizeButtons(c, d)
        val max = slider.valueTo.toInt()
        slider.value = d.coerceIn(DisplayLayoutPolicy.SLIDER_MIN, max).toFloat()
        if (input.text?.toString() != d.toString()) input.setText(d.toString())
        val name = DisplayLayoutPolicy.nameFor(cw, ch, d, clamp)
        expected.text = describe(context, name) ?.let { context.getString(R.string.dpi_expected_format, it) } ?: ""
        edgeNote.visibility = if (c != null && DisplayLayoutPolicy.sizeSteps(c).size > 1) VISIBLE else GONE
        isBinding = false
    }

    private fun buildSizeButtons(c: Choice?, d: Int) {
        sizes.removeAllViews()
        val steps = c?.let { DisplayLayoutPolicy.sizeSteps(it) } ?: emptyList()
        var checkedId = View.NO_ID
        fun add(label: String, tag: Any, checked: Boolean) {
            val b = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = ViewCompat.generateViewId()
                text = label
                this.tag = tag
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            sizes.addView(b)
            if (checked) checkedId = b.id
        }
        steps.forEachIndexed { i, step -> add(stepLabel(context, i), step, !auto && manualDpi == step) }
        if (c != null) add(context.getString(R.string.auto), AUTO_TAG, auto)
        sizes.clearChecked()
        if (checkedId != View.NO_ID) sizes.check(checkedId)
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
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
        private const val CARD_WIDTH_DP = 150

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
