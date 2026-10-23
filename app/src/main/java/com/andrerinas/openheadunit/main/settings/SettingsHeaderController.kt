package com.andrerinas.openheadunit.main.settings

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * The settings screen's header (back, title, Basic/Advanced, search, reset, save) and its
 * category navigation (side rail in landscape, chips in portrait).
 *
 * Which pieces show depends on the panel size, through the bools in bools_settings.xml, so the
 * same code serves a 480x240 strip and a 1280x800 tablet. The fragment keeps the settings logic
 * and talks to this class through the callbacks and the setters below.
 */
class SettingsHeaderController(
    private val root: View,
    advanced: Boolean,
    private val onBack: () -> Unit,
    private val onReset: () -> Unit,
    private val onSave: () -> Unit,
    private val onTierChanged: (advanced: Boolean) -> Unit,
    private val onQueryChanged: (query: String) -> Unit
) {
    private val context: Context = root.context
    private val palette = SettingsPalette.from(context)

    private val header: View = root.findViewById(R.id.settingsHeader)
    private val title: View = root.findViewById(R.id.settingsTitle)
    private val spacer: View? = root.findViewById(R.id.settingsHeaderSpacer)
    private val tabGroup: MaterialButtonToggleGroup = root.findViewById(R.id.settingsTabGroup)
    private val tabBasic: MaterialButton = root.findViewById(R.id.tabBasic)
    private val tabAdvanced: MaterialButton = root.findViewById(R.id.tabAdvanced)
    private val searchBar: View = root.findViewById(R.id.settingsSearchBar)
    private val searchInput: EditText = root.findViewById(R.id.settingsSearch)
    private val searchClear: View = root.findViewById(R.id.settingsSearchClear)
    private val searchButton: View = root.findViewById(R.id.settingsSearchButton)
    private val resetButton: View = root.findViewById(R.id.settingsReset)
    private val saveButton: MaterialButton = root.findViewById(R.id.settingsSave)
    private val categoryList: RecyclerView = root.findViewById(R.id.settingsCategories)
    private val contentList: RecyclerView = root.findViewById(R.id.settingsRecyclerView)

    private val res = context.resources
    private val searchInline = res.getBoolean(R.bool.settings_search_inline)
    private val saveFullLabel = res.getBoolean(R.bool.settings_save_full_label)
    private val tabCheckIcon = res.getBoolean(R.bool.settings_tab_check_icon)
    private val showCategories = res.getBoolean(R.bool.settings_show_categories)
    private val categoriesAsRail = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** In landscape the search field shares the header row; in portrait it is a row of its own. */
    private val searchInHeaderRow = searchBar.parent === header

    private var searchExpanded = false
    /** Set by a tap on a category so the scroll it starts does not move the highlight back. */
    private var categoryPinned = false
    private var headerPositions = IntArray(0)

    private val categoryAdapter = SettingsCategoryAdapter(categoriesAsRail) { jumpTo(it) }

    init {
        styleControls()
        // Before the listener is wired, so opening the screen does not report a tab change.
        tabGroup.check(if (advanced) R.id.tabAdvanced else R.id.tabBasic)
        updateTabIcons()
        wireControls()
        setUpCategories()
        title.visibility = if (res.getBoolean(R.bool.settings_show_title)) View.VISIBLE else View.GONE
        applySearchMode()
    }

    // --- State the fragment pushes in ---

    fun setSaveState(enabled: Boolean, needsReconnect: Boolean) {
        saveButton.isEnabled = enabled
        val compact = needsReconnect && !saveFullLabel
        saveButton.text = context.getString(if (needsReconnect && !compact) R.string.save_and_restart else R.string.save)
        saveButton.icon = if (compact) SettingsDrawables.tinted(context, R.drawable.ic_sym_sync, palette.onPrimary) else null
        saveButton.contentDescription = context.getString(if (needsReconnect) R.string.save_and_restart else R.string.save)
    }

    /** The search spans both tiers, so the tabs grey out while a query is typed. */
    fun setSearching(searching: Boolean) {
        tabGroup.isEnabled = !searching
        tabGroup.alpha = if (searching) DISABLED_ALPHA else 1f
        searchClear.visibility = if (searching) View.VISIBLE else View.GONE
    }

    /** Fills the search field (opening it where it hides behind a button) without focusing it. */
    fun showQuery(query: String) {
        if (!searchInline) {
            searchExpanded = true
            applySearchMode()
        }
        searchInput.setText(query)
        searchInput.setSelection(query.length)
        // Focus would open the keyboard over the rows just filtered to, which on a short head
        // unit panel is the whole list.
        searchInput.clearFocus()
    }

    fun submitCategories(categories: List<SettingsCategory>) {
        headerPositions = IntArray(categories.size) { categories[it].headerPosition }
        categoryAdapter.submit(categories)
        syncActiveCategory()
    }

    /** Back closes an opened search before it leaves the screen. Returns true if it did. */
    fun handleBack(): Boolean {
        if (!searchExpanded) return false
        collapseSearch()
        return true
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // --- Setup ---

    private fun styleControls() {
        val tabBackground = SettingsDrawables.checkedColors(palette.secondaryContainer, Color.TRANSPARENT)
        val tabText = SettingsDrawables.checkedColors(palette.onSecondaryContainer, palette.onSurface)
        val tabRadius = res.getDimensionPixelSize(R.dimen.settings_tab_height) / 2
        for (tab in listOf(tabBasic, tabAdvanced)) {
            tab.backgroundTintList = tabBackground
            tab.setTextColor(tabText)
            tab.strokeColor = ColorStateList.valueOf(palette.outline)
            tab.rippleColor = ColorStateList.valueOf(palette.secondaryContainer)
            tab.iconTint = ColorStateList.valueOf(palette.onSecondaryContainer)
            tab.cornerRadius = tabRadius
        }
        styleFilledButton(saveButton, palette)
        saveButton.cornerRadius = res.getDimensionPixelSize(R.dimen.settings_header_control_height) / 2
        val fieldRadius = res.getDimension(R.dimen.settings_header_control_height) / 2f
        searchBar.background = SettingsDrawables.searchField(context, palette, fieldRadius)
    }

    private fun wireControls() {
        root.findViewById<View>(R.id.settingsBack).setOnClickListener { if (!handleBack()) onBack() }
        resetButton.setOnClickListener { onReset() }
        saveButton.setOnClickListener { onSave() }
        searchButton.setOnClickListener { expandSearch() }
        tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateTabIcons()
            onTierChanged(checkedId == R.id.tabAdvanced)
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, before: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onQueryChanged(s?.toString() ?: "")
            }
        })
        // The keyboard's search key only closes the keyboard: the list already filters as you type.
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            searchInput.clearFocus()
            hideKeyboard()
            true
        }
        // The clear button also lowers the keyboard and drops focus. Some Chinese head units
        // cannot dismiss the keyboard easily once it is open, so give an explicit way out.
        searchClear.setOnClickListener {
            searchInput.setText("")
            searchInput.clearFocus()
            hideKeyboard()
        }
    }

    private fun setUpCategories() {
        categoryList.visibility = if (showCategories) View.VISIBLE else View.GONE
        categoryList.layoutManager = LinearLayoutManager(
            context,
            if (categoriesAsRail) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL,
            false
        )
        categoryList.adapter = categoryAdapter
        categoryList.itemAnimator = null
        contentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) categoryPinned = false
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!categoryPinned) syncActiveCategory()
            }
        })
    }

    // --- Search ---

    private fun expandSearch() {
        searchExpanded = true
        applySearchMode()
        searchInput.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseSearch() {
        searchExpanded = false
        searchInput.setText("")
        searchInput.clearFocus()
        hideKeyboard()
        applySearchMode()
    }

    private fun applySearchMode() {
        val fieldShown = searchInline || searchExpanded
        val takeover = searchExpanded && !searchInline
        searchBar.visibility = if (fieldShown) View.VISIBLE else View.GONE
        searchButton.visibility = if (searchInline || searchExpanded) View.GONE else View.VISIBLE
        spacer?.visibility = if (fieldShown) View.GONE else View.VISIBLE
        if (searchInHeaderRow) {
            // Landscape: the opened field takes over the header row.
            val keep = if (takeover) View.GONE else View.VISIBLE
            tabGroup.visibility = keep
            resetButton.visibility = keep
            title.visibility = if (takeover || !res.getBoolean(R.bool.settings_show_title)) View.GONE else View.VISIBLE
        } else {
            // Portrait: the field is its own row; tabs and chips step aside while it is open.
            tabGroup.visibility = if (takeover) View.GONE else View.VISIBLE
            categoryList.visibility = if (takeover || !showCategories) View.GONE else View.VISIBLE
        }
    }

    // --- Tabs ---

    private fun updateTabIcons() {
        for (tab in listOf(tabBasic, tabAdvanced)) {
            tab.icon = if (tabCheckIcon && tab.isChecked) {
                SettingsDrawables.tinted(context, R.drawable.ic_sym_check, palette.onSecondaryContainer)
            } else {
                null
            }
        }
    }

    // --- Categories ---

    private fun jumpTo(category: SettingsCategory) {
        categoryPinned = true
        categoryAdapter.setActive(category.id)
        (contentList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(category.headerPosition, 0)
    }

    private fun syncActiveCategory() {
        val manager = contentList.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val index = SettingsLayoutPolicy.activeCategoryIndex(headerPositions, first)
        if (index < 0) return
        val position = categoryAdapter.setActive(categoryAdapter.categoryIdAt(index))
        if (position >= 0) categoryList.scrollToPosition(position)
    }

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}
