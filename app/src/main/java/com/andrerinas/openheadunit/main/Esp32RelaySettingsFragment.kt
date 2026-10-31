package com.andrerinas.openheadunit.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.esp32.Esp32RelayClient
import com.andrerinas.openheadunit.connection.esp32.Esp32RelayProtocol
import com.andrerinas.openheadunit.connection.esp32.Esp32RelaySession
import com.andrerinas.openheadunit.connection.esp32.Esp32RelayStore
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.google.android.material.appbar.MaterialToolbar

/**
 * Chooses the Bluetooth relay and pairs a phone through it.
 */
class Esp32RelaySettingsFragment : Fragment() {
    private lateinit var store: Esp32RelayStore
    private lateinit var scanner: Esp32RelayClient
    private val found = linkedMapOf<String, String>()
    private var scanning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_esp32_relay, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!Esp32RelaySession.isSupported()) {
            leave()
            return
        }
        store = Esp32RelayStore(requireContext())
        scanner = Esp32RelayClient(requireContext())
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        val adapter = SettingsAdapter()
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { leave() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        fun render() {
            adapter.submitList(rows())
        }
        Esp32RelaySession.setListener { render() }
        render()
    }

    override fun onDestroyView() {
        // API < 23 leaves() before scanner is assigned; still reachable via deep navigation.
        if (::scanner.isInitialized) scanner.stopScan()
        Esp32RelaySession.setListener(null)
        super.onDestroyView()
    }

    private fun rows(): List<SettingItem> {
        val items = mutableListOf<SettingItem>()
        val enabled = store.enabled
        val saved = store.deviceName ?: store.deviceAddress
        val esp32RelaySaved = store.deviceAddress != null

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "esp32RelayEnabled",
            nameResId = R.string.esp32_relay_enabled,
            descriptionResId = R.string.esp32_relay_enabled_description,
            isChecked = enabled,
            onCheckedChanged = { checked ->
                store.enabled = checked
                if (!checked) {
                    scanner.stopScan()
                    scanning = false
                    found.clear()
                    Esp32RelaySession.close()
                }
                Esp32RelaySession.syncWatchdog(requireContext())
                refresh()
            },
        ))
        items.add(SettingItem.InfoBanner(
            stableId = "esp32RelaySaved",
            textResId = R.string.esp32_relay_saved_device,
            text = getString(
                R.string.esp32_relay_saved_notice,
                saved ?: getString(R.string.esp32_relay_none),
            ),
        ))
        items.add(SettingItem.InfoBanner(
            stableId = "esp32RelayStatus",
            textResId = R.string.esp32_relay_status,
            text = getString(
                R.string.esp32_relay_status_notice,
                if (enabled) statusText() else getString(R.string.esp32_relay_phase_idle),
            ),
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "esp32RelayScan",
            nameResId = R.string.esp32_relay_scan,
            value = if (scanning) getString(R.string.esp32_relay_scanning) else getString(R.string.esp32_relay_scan_action),
            isEnabled = enabled,
            onClick = { requestScan() },
        ))
        if (enabled) {
            found.forEach { (address, name) ->
                items.add(SettingItem.SettingEntry(
                    stableId = "esp32Relay-$address",
                    nameResId = R.string.esp32_relay_found_device,
                    nameOverride = name,
                    value = address,
                    onClick = { select(address, name) },
                ))
            }
        }
        items.add(SettingItem.CategoryHeader("esp32RelayPairing", R.string.esp32_relay_pairing_header))
        items.add(SettingItem.InfoBanner("esp32RelayPairingSteps", R.string.esp32_relay_pairing_steps))
        items.add(SettingItem.SettingEntry(
            stableId = "esp32RelayPair",
            nameResId = R.string.esp32_relay_pair,
            value = getString(R.string.esp32_relay_pair_value),
            isEnabled = enabled && esp32RelaySaved,
            onClick = {
                Esp32RelaySession.openPairing(requireContext())
                refresh()
            },
        ))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "esp32RelayEndAaOnDisconnect",
            nameResId = R.string.esp32_relay_end_aa_on_disconnect,
            descriptionResId = R.string.esp32_relay_end_aa_on_disconnect_description,
            isChecked = store.endAaOnDisconnect,
            isEnabled = enabled,
            onCheckedChanged = { checked ->
                store.endAaOnDisconnect = checked
                Esp32RelaySession.syncWatchdog(requireContext())
                refresh()
            },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "esp32RelayForget",
            nameResId = R.string.esp32_relay_forget,
            value = getString(R.string.esp32_relay_forget_value),
            isEnabled = enabled && esp32RelaySaved,
            onClick = {
                store.clear()
                Esp32RelaySession.close()
                found.clear()
                refresh()
            },
        ))
        return items
    }

    private fun statusText(): String {
        val status = Esp32RelaySession.current()
        return when (status.phase) {
            Esp32RelaySession.Phase.IDLE -> getString(R.string.esp32_relay_phase_idle)
            Esp32RelaySession.Phase.CONNECTING -> getString(R.string.esp32_relay_phase_connecting)
            Esp32RelaySession.Phase.READY -> getString(R.string.esp32_relay_phase_ready)
            // Incomplete Wi-Fi Direct is not shown; credentials are offered at AA start.
            Esp32RelaySession.Phase.INCOMPLETE -> getString(R.string.esp32_relay_phase_idle)
            Esp32RelaySession.Phase.PAIRING -> getString(R.string.esp32_relay_phase_pairing)
            Esp32RelaySession.Phase.FAILED -> getString(R.string.esp32_relay_phase_failed, status.detail)
        }
    }

    private fun requestScan() {
        if (!store.enabled) return
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isEmpty()) startScan() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startScan() {
        if (!store.enabled) return
        scanning = true
        found.clear()
        refresh()
        scanner.scan(
            onDevice = { device, name ->
                val label = name?.trim().orEmpty()
                if (!Esp32RelayProtocol.isEsp32RelayBleName(label)) return@scan
                found[device.address] = label
                refresh()
            },
            onFinished = {
                scanning = false
                refresh()
            },
        )
    }

    private fun select(address: String, name: String) {
        if (!store.enabled) return
        store.deviceAddress = address
        store.deviceName = name
        found.remove(address)
        scanner.stopScan()
        scanning = false
        // Always open the Classic pairing window after the board is saved. Wi-Fi credentials
        // are pushed later when Native AA starts; offering them here can arm an AA dial.
        Esp32RelaySession.openPairing(requireContext())
        refresh()
    }

    private fun refresh() {
        val recycler = view?.findViewById<RecyclerView>(R.id.recycler_view) ?: return
        (recycler.adapter as? SettingsAdapter)?.submitList(rows())
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun leave() {
        try {
            if (!findNavController().navigateUp()) requireActivity().finish()
        } catch (_: Exception) {
            requireActivity().finish()
        }
    }
}
