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
import com.andrerinas.openheadunit.connection.broker.NativeBTBrokerClient
import com.andrerinas.openheadunit.connection.broker.NativeBTBrokerProtocol
import com.andrerinas.openheadunit.connection.broker.NativeBTBrokerSession
import com.andrerinas.openheadunit.connection.broker.NativeBTBrokerStore
import com.andrerinas.openheadunit.connection.broker.WifiDirectDisplayInfo
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.google.android.material.appbar.MaterialToolbar

/**
 * Chooses the Native BT Broker, pairs a phone through it, and closes the BLE link.
 */
class BrokerSettingsFragment : Fragment() {
    private lateinit var store: NativeBTBrokerStore
    private lateinit var scanner: NativeBTBrokerClient
    private val found = linkedMapOf<String, String>()
    private var scanning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_broker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = NativeBTBrokerStore(requireContext())
        scanner = NativeBTBrokerClient(requireContext())
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
        NativeBTBrokerSession.setListener { render() }
        render()
    }

    override fun onDestroyView() {
        scanner.stopScan()
        NativeBTBrokerSession.setListener(null)
        super.onDestroyView()
    }

    private fun rows(): List<SettingItem> {
        val items = mutableListOf<SettingItem>()
        val saved = store.deviceName ?: store.deviceAddress
        val brokerSaved = store.deviceAddress != null
        val phase = NativeBTBrokerSession.current().phase
        val linked = phase != NativeBTBrokerSession.Phase.IDLE

        items.add(SettingItem.SettingEntry(
            stableId = "brokerSaved",
            nameResId = R.string.broker_saved_device,
            value = saved ?: getString(R.string.broker_none),
            onClick = { _ -> },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "brokerStatus",
            nameResId = R.string.broker_status,
            value = statusText(),
            onClick = { _ -> },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "brokerScan",
            nameResId = R.string.broker_scan,
            value = if (scanning) getString(R.string.broker_scanning) else getString(R.string.broker_scan_action),
            onClick = { requestScan() },
        ))
        found.forEach { (address, name) ->
            items.add(SettingItem.SettingEntry(
                stableId = "broker-$address",
                nameResId = R.string.broker_found_device,
                nameOverride = name,
                value = address,
                onClick = { select(address, name) },
            ))
        }
        items.add(SettingItem.SettingEntry(
            stableId = "brokerPair",
            nameResId = R.string.broker_pair,
            value = getString(R.string.broker_pair_value),
            isEnabled = brokerSaved,
            onClick = {
                NativeBTBrokerSession.openPairing(requireContext())
                refresh()
            },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "brokerDisconnect",
            nameResId = R.string.broker_disconnect,
            value = getString(R.string.broker_disconnect_value),
            isEnabled = brokerSaved && linked,
            onClick = {
                NativeBTBrokerSession.close()
                refresh()
            },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "brokerForget",
            nameResId = R.string.broker_forget,
            value = getString(R.string.broker_forget_value),
            isEnabled = brokerSaved,
            onClick = {
                store.clear()
                NativeBTBrokerSession.close()
                found.clear()
                refresh()
            },
        ))
        return items
    }

    private fun statusText(): String {
        val status = NativeBTBrokerSession.current()
        return when (status.phase) {
            NativeBTBrokerSession.Phase.IDLE -> getString(R.string.broker_phase_idle)
            NativeBTBrokerSession.Phase.CONNECTING -> getString(R.string.broker_phase_connecting)
            NativeBTBrokerSession.Phase.READY -> getString(R.string.broker_phase_ready)
            NativeBTBrokerSession.Phase.INCOMPLETE -> getString(R.string.broker_phase_incomplete)
            NativeBTBrokerSession.Phase.PAIRING -> getString(R.string.broker_phase_pairing)
            NativeBTBrokerSession.Phase.FAILED -> getString(R.string.broker_phase_failed, status.detail)
        }
    }

    private fun requestScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isEmpty()) startScan() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startScan() {
        scanning = true
        found.clear()
        refresh()
        scanner.scan(
            onDevice = { device, name ->
                val label = name?.trim().orEmpty()
                if (!label.startsWith(NativeBTBrokerProtocol.BLE_NAME_PREFIX)) return@scan
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
        store.deviceAddress = address
        store.deviceName = name
        scanner.stopScan()
        scanning = false
        WifiDirectDisplayInfo.read(requireContext()) { info ->
            if (info.isComplete) {
                NativeBTBrokerSession.offer(requireContext(), info)
            } else {
                NativeBTBrokerSession.openPairing(requireContext())
            }
            refresh()
        }
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
