package com.andrerinas.openheadunit.main

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingHelpDialog
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.contract.KeyIntent
import com.andrerinas.openheadunit.input.BydPanelKey
import com.andrerinas.openheadunit.input.InputAction
import com.andrerinas.openheadunit.input.InputActionCodec
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.IntentFilters
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class KeymapFragment : Fragment(), MainActivity.KeyListener {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SettingsAdapter
    /** What the key tester banner shows: the last key seen, or null for the hint. */
    private var testerText: String? = null
    private lateinit var settings: Settings
    private val RESET_ITEM_ID = 1003

    /** True while the "press a key" dialog waits for the key to bind. */
    private var assigning = false
    private var assignDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(REQUEST_KEY, this) { _, result ->
            val physical = result.getString(ActionPickerFragment.RESULT_TAG)?.toIntOrNull()
                ?: return@setFragmentResultListener
            val action = InputActionCodec.decode(result.getString(ActionPickerFragment.RESULT_ACTION))
            val settings = Settings(requireContext())
            val bindings = settings.keyBindings.toMutableMap()
            if (action == InputAction.None) bindings.remove(physical) else bindings[physical] = action
            settings.keyBindings = bindings
            if (::adapter.isInitialized) render()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_keymap, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        settings = Settings(requireContext())
        
        toolbar = view.findViewById(R.id.toolbar)
        recyclerView = view.findViewById(R.id.recycler_view)
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SettingsAdapter()
        recyclerView.adapter = adapter
        render()

        setupToolbar()

        // Ensure fragment can receive key events
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, _, event ->
            onKeyEvent(event)
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        SettingHelpDialog.addToolbarHelp(toolbar, toolbar.title, R.string.keymap_screen_help, "keymap")

        val resetItem = toolbar.menu.add(0, RESET_ITEM_ID, 1, getString(R.string.reset))
        resetItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        resetItem.setActionView(R.layout.layout_reset_button)
        
        val resetButton = resetItem.actionView?.findViewById<MaterialButton>(R.id.reset_button_widget)
        resetButton?.setOnClickListener {
            settings.keyCodes = mutableMapOf()
            settings.keyBindings = emptyMap()
            render()
            ToastUtils.showToast(requireContext(), getString(R.string.key_mappings_reset), Toast.LENGTH_SHORT, force = true)
        }
    }

    private fun showAssignDialog() {
        assigning = true

        // Using MaterialAlertDialogBuilder with DarkAlertDialog style for consistency and compatibility
        assignDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.keymap_add_binding)
            .setMessage(R.string.keymap_press_new_key)
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener {
                assigning = false
                assignDialog = null
            }
            .create()

        // Important: We need to set the listener on the dialog to catch keys like ENTER
        assignDialog?.setOnKeyListener { _, _, event ->
            onKeyEvent(event)
        }

        assignDialog?.show()
    }

    private fun openActionPicker(physicalKey: Int) {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.keymapFragment) return
        controller.navigate(
            R.id.action_keymapFragment_to_actionPickerFragment,
            ActionPickerFragment.args(
                requestKey = REQUEST_KEY,
                tag = physicalKey.toString(),
                title = physicalKeyName(physicalKey),
                current = settings.keyBindings[physicalKey] ?: InputAction.None,
                noneLabelResId = R.string.action_remove_binding
            )
        )
    }

    private val keyCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.andrerinas.openheadunit.DEBUG_KEY") {
                val action = (intent.getStringExtra("action") ?: "unknown").replace("com.andrerinas.openheadunit.", "")
                
                // Try to find a keycode in various common extras
                val extractedCode = intent.getIntExtra("keyCode", -1).takeIf { it != -1 }
                    ?: intent.getByteExtra("extra_key_value", 0).toInt().takeIf { it != 0 }
                    ?: intent.getIntExtra("CLICK_KEY", -1).takeIf { it != -1 }
                    ?: intent.getIntExtra("com.winca.service.Setting.KEY_ACTION_EXTRA", -1).takeIf { it != -1 }

                val extras = intent.extras?.keySet()
                    ?.filter { it != "action" && it != "keyCode" && it != "extra_key_value" }
                    ?.joinToString { "$it=${intent.extras?.get(it)}" } ?: ""
                
                val codeText = if (extractedCode != null) "CODE: $extractedCode" else "NO CODE"
                val displayText = "Action: $action\n$codeText\n$extras"
                
                showTester(displayText)
                return
            }

            val event: KeyEvent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            onKeyEvent(event)
        }
    }

    override fun onResume() {
        super.onResume()
        App.provide(requireContext()).carKeysManager.startLearning(requireContext())
        val filter = IntentFilters.keyEvent
        filter.addAction("com.andrerinas.openheadunit.DEBUG_KEY")
        ContextCompat.registerReceiver(requireContext(), keyCodeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        context?.let { App.provide(it).carKeysManager.stopLearning() }
        context?.unregisterReceiver(keyCodeReceiver)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        (activity as? MainActivity)?.let {
            it.setDefaultKeyMode(Activity.DEFAULT_KEYS_DISABLE)
            it.keyListener = this
        }
    }

    override fun onDetach() {
        (activity as? MainActivity)?.let {
            it.setDefaultKeyMode(Activity.DEFAULT_KEYS_SHORTCUT)
            it.keyListener = null
        }
        super.onDetach()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        // Still attached while the action picker is on top of it: keys are not ours then.
        if (event == null || view == null) return false

        val keyCode = event.keyCode
        // Only ignore BACK if we are NOT in assignment mode
        if (keyCode == KeyEvent.KEYCODE_BACK && !assigning) return false

        val keyName = physicalKeyName(keyCode)
        val actionName = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        
        AppLog.i("KeymapFragment: Captured $keyName ($keyCode) $actionName")
        
        showTester(getString(R.string.keymap_tester_last, keyName, keyCode, actionName))

        if (assigning) {
            // Only finish on ACTION_UP, so the release of the key never reaches the next screen.
            if (event.action == KeyEvent.ACTION_UP) {
                assignDialog?.dismiss()
                openActionPicker(keyCode)
            }
            return true
        }

        return false
    }

    /**
     * What to call a physical button in this screen. Proprietary buttons are carried as virtual key
     * codes that `KeyEvent.keyCodeToString` renders as a bare number, which tells a user nothing
     * about the button they just pressed, so the ones we can name get named.
     */
    private fun physicalKeyName(keyCode: Int): String {
        BydPanelKey.label(keyCode)?.let { return it }
        return try {
            KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "")
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    private fun showTester(text: String) {
        testerText = text
        render()
    }

    /** Key tester first, then the bindings sorted by key code, then the button that adds one. */
    private fun render() {
        val bindings = settings.keyBindings
        val items = mutableListOf<SettingItem>(
            SettingItem.InfoBanner(
                stableId = "tester",
                textResId = R.string.keymap_tester_hint,
                text = testerText
            ),
            SettingItem.CategoryHeader("bindings", R.string.keymap_bindings_header)
        )
        if (bindings.isEmpty()) {
            items += SettingItem.InfoBanner("empty", R.string.keymap_no_bindings)
        } else {
            bindings.keys.sorted().forEach { physical ->
                items += SettingItem.SettingEntry(
                    stableId = "key_$physical",
                    nameResId = R.string.keymap,
                    nameOverride = physicalKeyName(physical),
                    value = ActionCatalog.label(requireContext(), bindings.getValue(physical)),
                    onClick = { openActionPicker(physical) }
                )
            }
        }
        items += SettingItem.ActionButton("add", R.string.keymap_add_binding) { showAssignDialog() }
        adapter.submitList(items)
    }

    companion object {
        private const val REQUEST_KEY = "keymap_action"
    }
}
