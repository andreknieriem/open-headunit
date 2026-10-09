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
import com.andrerinas.openheadunit.contract.KeyIntent
import com.andrerinas.openheadunit.input.BydPanelKey
import com.andrerinas.openheadunit.input.KeyCode
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
    private lateinit var keypressDebuggerTextView: TextView
    private lateinit var adapter: KeymapAdapter
    private lateinit var settings: Settings
    private val RESET_ITEM_ID = 1003

    private var assignTargetCode = KeyEvent.KEYCODE_UNKNOWN
    private var assignDialog: androidx.appcompat.app.AlertDialog? = null

    data class KeymapItem(val nameResId: Int, val keyCode: Int)

    private val keyList = listOf(
        KeymapItem(R.string.key_soft_left, KeyEvent.KEYCODE_SOFT_LEFT),
        KeymapItem(R.string.key_soft_right, KeyEvent.KEYCODE_SOFT_RIGHT),
        KeymapItem(R.string.key_back, KeyEvent.KEYCODE_BACK),
        KeymapItem(R.string.key_dpad_up, KeyEvent.KEYCODE_DPAD_UP),
        KeymapItem(R.string.key_dpad_down, KeyEvent.KEYCODE_DPAD_DOWN),
        KeymapItem(R.string.key_dpad_left, KeyEvent.KEYCODE_DPAD_LEFT),
        KeymapItem(R.string.key_dpad_right, KeyEvent.KEYCODE_DPAD_RIGHT),
        KeymapItem(R.string.key_dpad_center, KeyEvent.KEYCODE_DPAD_CENTER),
        KeymapItem(R.string.key_media_play, KeyEvent.KEYCODE_MEDIA_PLAY),
        KeymapItem(R.string.key_media_pause, KeyEvent.KEYCODE_MEDIA_PAUSE),
        KeymapItem(R.string.key_media_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        KeymapItem(R.string.key_media_next, KeyEvent.KEYCODE_MEDIA_NEXT),
        KeymapItem(R.string.key_media_prev, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        KeymapItem(R.string.key_search, KeyEvent.KEYCODE_SEARCH),
        KeymapItem(R.string.key_call, KeyEvent.KEYCODE_CALL),
        KeymapItem(R.string.key_endcall, KeyEvent.KEYCODE_ENDCALL),
        KeymapItem(R.string.key_music, KeyEvent.KEYCODE_MUSIC),
        KeymapItem(R.string.key_nav, 65538),
        KeymapItem(R.string.key_night, KeyCode.KEY_NIGHT_MODE),
        KeymapItem(R.string.key_enter, KeyEvent.KEYCODE_ENTER),
        KeymapItem(R.string.key_home, KeyEvent.KEYCODE_HOME),
        KeymapItem(R.string.key_app_switch, KeyEvent.KEYCODE_APP_SWITCH),
        KeymapItem(R.string.key_button_a, KeyEvent.KEYCODE_BUTTON_A),
        KeymapItem(R.string.key_button_b, KeyEvent.KEYCODE_BUTTON_B)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_keymap, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        settings = Settings(requireContext())
        
        keypressDebuggerTextView = view.findViewById(R.id.keypress_debugger_text)
        toolbar = view.findViewById(R.id.toolbar)
        recyclerView = view.findViewById(R.id.recycler_view)
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = KeymapAdapter(keyList, settings.keyCodes) { item ->
            showAssignDialog(item)
        }
        recyclerView.adapter = adapter

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
        
        val resetItem = toolbar.menu.add(0, RESET_ITEM_ID, 0, getString(R.string.reset))
        resetItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        resetItem.setActionView(R.layout.layout_reset_button)
        
        val resetButton = resetItem.actionView?.findViewById<MaterialButton>(R.id.reset_button_widget)
        resetButton?.setOnClickListener {
            settings.keyCodes = mutableMapOf()
            adapter.updateCodes(settings.keyCodes)
            ToastUtils.showToast(requireContext(), getString(R.string.key_mappings_reset), Toast.LENGTH_SHORT, force = true)
        }
    }

    private fun showAssignDialog(item: KeymapItem) {
        assignTargetCode = item.keyCode
        val name = getString(item.nameResId)
        
        // Using MaterialAlertDialogBuilder with DarkAlertDialog style for consistency and compatibility
        assignDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(name)
            .setMessage(getString(R.string.press_key_to_assign, name))
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                assignTargetCode = KeyEvent.KEYCODE_UNKNOWN
                dialog.dismiss()
            }
            .setOnDismissListener {
                assignTargetCode = KeyEvent.KEYCODE_UNKNOWN
                assignDialog = null
            }
            .create()

        // Important: We need to set the listener on the dialog to catch keys like ENTER
        assignDialog?.setOnKeyListener { _, _, event ->
            onKeyEvent(event)
        }

        assignDialog?.show()
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
                
                keypressDebuggerTextView.text = displayText
                keypressDebuggerTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_teal))
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
        if (event == null) return false

        val keyCode = event.keyCode
        // Only ignore BACK if we are NOT in assignment mode
        if (keyCode == KeyEvent.KEYCODE_BACK && assignTargetCode == KeyEvent.KEYCODE_UNKNOWN) return false

        val keyName = physicalKeyName(keyCode)
        val actionName = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        
        AppLog.i("KeymapFragment: Captured $keyName ($keyCode) $actionName")
        
        keypressDebuggerTextView.text = "Key: $keyName ($keyCode) - $actionName"
        keypressDebuggerTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_teal))

        if (assignTargetCode != KeyEvent.KEYCODE_UNKNOWN) {
            // Only finalize assignment on ACTION_UP to avoid accidental multiple mappings
            if (event.action == KeyEvent.ACTION_UP) {
                val codesMap = settings.keyCodes
                
                // Map: Logical (AA) -> Physical (HW)
                codesMap[assignTargetCode] = keyCode
                
                settings.keyCodes = codesMap
                adapter.updateCodes(codesMap)
                
                val targetName = getString(keyList.find { it.keyCode == assignTargetCode }?.nameResId ?: R.string.keymap)
                ToastUtils.showToast(requireContext(), getString(R.string.key_assigned, keyName, targetName), Toast.LENGTH_SHORT, force = true)
                
                assignDialog?.dismiss()
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

    inner class KeymapAdapter(
        private val items: List<KeymapItem>,
        private var codesMap: Map<Int, Int>,
        private val onClick: (KeymapItem) -> Unit
    ) : RecyclerView.Adapter<KeymapAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.key_name)
            val valueText: TextView = view.findViewById(R.id.key_value)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_keymap, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.nameText.text = getString(item.nameResId)
            
            // Map: Logical -> Physical
            val physicalKey = codesMap[item.keyCode]
            
            if (physicalKey != null) {
                holder.valueText.text = physicalKeyName(physicalKey)
            } else {
                holder.valueText.text = getString(R.string.not_set)
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
        
        fun updateCodes(newMap: Map<Int, Int>) {
            codesMap = newMap
            notifyDataSetChanged()
        }
    }
}
