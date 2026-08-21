package com.andrerinas.openheadunit.main

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.NetworkDiscovery
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.changeLastBit
import com.andrerinas.openheadunit.utils.toInetAddress
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.net.Inet4Address
import java.net.InetAddress

class NetworkListFragment : Fragment(), NetworkDiscovery.Listener {
    private lateinit var adapter: AddressAdapter
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var toolbar: MaterialToolbar
    private lateinit var networkDiscovery: NetworkDiscovery

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val ADD_ITEM_ID = 1002
    private val SCAN_ITEM_ID = 1003
    private var scanDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networkDiscovery = NetworkDiscovery(requireContext(), this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)
        val recyclerView = view.findViewById<RecyclerView>(android.R.id.list)
        toolbar = view.findViewById(R.id.toolbar)

        connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateCurrentAddress()
                }

                override fun onLost(network: Network) {
                    updateCurrentAddress()
                }
            }
        }

        adapter = AddressAdapter(requireContext(), childFragmentManager, viewLifecycleOwner.lifecycleScope)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        recyclerView.setPadding(
            resources.getDimensionPixelSize(R.dimen.list_padding),
            resources.getDimensionPixelSize(R.dimen.list_padding),
            resources.getDimensionPixelSize(R.dimen.list_padding),
            resources.getDimensionPixelSize(R.dimen.list_padding)
        )
        recyclerView.clipToPadding = false

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.title = getString(R.string.wifi)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        toolbar.navigationIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_back_white)

        setupToolbarMenu()
    }

    private fun setupToolbarMenu() {
        // Scan Button (Custom Layout)
        val scanItem = toolbar.menu.add(0, SCAN_ITEM_ID, 0, getString(R.string.scan))
        scanItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        scanItem.setActionView(R.layout.layout_scan_button)

        val scanButton = scanItem.actionView?.findViewById<MaterialButton>(R.id.scan_button_widget)
        scanButton?.setOnClickListener {
            startScan()
        }

        // Add Button (Custom Layout)
        val addItem = toolbar.menu.add(0, ADD_ITEM_ID, 0, getString(R.string.add_new))
        addItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        addItem.setActionView(R.layout.layout_add_button)

        val addButton = addItem.actionView?.findViewById<MaterialButton>(R.id.add_button_widget)
        addButton?.setOnClickListener {
            showAddAddressDialog()
        }
    }

    private fun startScan() {
        showScanDialog()
        networkDiscovery.startScan(oneShot = true)
    }

    private fun showScanDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
        val progressBar = ProgressBar(requireContext())
        progressBar.setPadding(32, 32, 32, 32)
        builder.setView(progressBar)
        builder.setTitle(R.string.scanning_network)
        builder.setNegativeButton(R.string.cancel) { _, _ ->
             networkDiscovery.stop()
        }
        builder.setCancelable(false)
        scanDialog = builder.show()

        scanDialog?.window?.decorView?.apply {
            @Suppress("DEPRECATION")
            systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onServiceFound(ip: String, port: Int, socket: java.net.Socket?) {
        if (port != 5277) {
            // Only interested in Headunit Server (5277) for manual connection list
            // Ignore WifiLauncher (5289) here as that is for auto-triggering
            try { socket?.close() } catch (e: Exception) {}
            return
        }
        activity?.runOnUiThread {
            // Save immediately so it stays in the list permanently
            try {
                AppLog.i("Found Infotainment Device. Try to connect to $ip:$port")
                adapter.addNewAddress(InetAddress.getByName(ip))
            } catch (e: Exception) {
                AppLog.e("Failed to add discovered address", e)
            }

            // Auto-connect to the first found device during a manual scan
            if (scanDialog?.isShowing == true) {
                scanDialog?.dismiss()
                Toast.makeText(context, getString(R.string.found_connecting, ip), Toast.LENGTH_SHORT).show()

                val ctx = context ?: return@runOnUiThread
                (activity as? MainActivity)?.beginAutoConnect(
                    "manual WiFi scan-found $ip",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    if (socket != null && socket.isConnected)
                        App.provide(ctx).commManager.connect(socket)
                    else
                        App.provide(ctx).commManager.connect(ip, 5277)
                    ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                }
            } else {
                // If not in auto-connect dialog, close the probe socket
                try { socket?.close() } catch (e: Exception) {}
            }
        }
    }

    // This scan is always one-shot, so the flag says nothing this fragment does not already know:
    // the dialog closing is the end of it, and nothing re-arms.
    override fun onScanFinished(oneShot: Boolean) {
        activity?.runOnUiThread {
            if (scanDialog?.isShowing == true) {
                scanDialog?.dismiss()
                if (adapter.addressList.size <= 2) { // Only localhost and current IP
                    Toast.makeText(context, getString(R.string.no_devices_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAddAddressDialog() {
        var ip: InetAddress? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            ip = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address
        } else {
            val wifiManager = App.provide(requireContext()).wifiManager
            @Suppress("DEPRECATION")
            val currentIp = wifiManager.connectionInfo.ipAddress
            if (currentIp != 0) {
                ip = currentIp.toInetAddress()
            }
        }
        com.andrerinas.openheadunit.main.AddNetworkAddressDialog.show(ip, childFragmentManager)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            networkCallback?.let {
                connectivityManager.registerNetworkCallback(request, it)
            }
        }
        updateCurrentAddress()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }
    }

    private fun updateCurrentAddress() {
        var ipAddress: InetAddress? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // API 23+ (for getActiveNetwork)
            val activeNetwork = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            ipAddress = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address
        } else { // API 19, 20, 21, 22
            val wifiManager = App.provide(requireContext()).wifiManager
            @Suppress("DEPRECATION")
            val currentIp = wifiManager.connectionInfo.ipAddress
            if (currentIp != 0) {
                ipAddress = currentIp.toInetAddress()
            }
        }

        activity?.runOnUiThread {
            adapter.currentAddress = ipAddress?.changeLastBit(1)?.hostAddress ?: ""
            adapter.loadAddresses()
        }
    }

    fun addAddress(ip: InetAddress) {
        adapter.addNewAddress(ip)
    }

    private class DeviceViewHolder internal constructor(itemView: View) : RecyclerView.ViewHolder(itemView) {
        internal val removeButton = itemView.findViewById<Button>(android.R.id.button1)
        internal val startButton = itemView.findViewById<Button>(android.R.id.button2)
    }

    private class AddressAdapter(
        private val context: Context,
        private val fragmentManager: FragmentManager,
        private val scope: kotlinx.coroutines.CoroutineScope
    ) : RecyclerView.Adapter<DeviceViewHolder>(), View.OnClickListener {

        val addressList = ArrayList<String>()
        var currentAddress: String = ""
        private val settings: Settings = Settings(context)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.list_item_device, parent, false)
            val holder = DeviceViewHolder(view)

            holder.startButton.setOnClickListener(this)
            holder.removeButton.setOnClickListener(this)
            holder.removeButton.setText(R.string.remove)
            return holder
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val ipAddress = addressList[position]

            val isTop = position == 0
            val isBottom = position == itemCount - 1

            val bgRes = when {
                isTop && isBottom -> R.drawable.bg_setting_single
                isTop -> R.drawable.bg_setting_top
                isBottom -> R.drawable.bg_setting_bottom
                else -> R.drawable.bg_setting_middle
            }
            holder.itemView.setBackgroundResource(bgRes)


            val line1: String = ipAddress
            holder.removeButton.visibility = if (ipAddress == "127.0.0.1" || (currentAddress.isNotEmpty() && ipAddress == currentAddress)) View.GONE else View.VISIBLE

            holder.startButton.setTag(R.integer.key_position, position)
            holder.startButton.text = line1
            holder.startButton.setTag(R.integer.key_data, ipAddress)
            holder.removeButton.setTag(R.integer.key_data, ipAddress)
        }

        override fun getItemCount(): Int {
            return addressList.size
        }

        override fun onClick(v: View) {
            if (v.id == android.R.id.button2) {
                val ip = v.getTag(R.integer.key_data) as String
                (context as? MainActivity)?.beginAutoConnect(
                    "manual WiFi list click $ip",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                ContextCompat.startForegroundService(context, Intent(context, AapService::class.java).apply {
                    action = AapService.ACTION_CONNECT_SOCKET
                })
                scope.launch(Dispatchers.IO) { App.provide(context).commManager.connect(ip, 5277) }
            } else {
                this.removeAddress(v.getTag(R.integer.key_data) as String)
            }
        }

        internal fun addNewAddress(ip: InetAddress) {
            val newAddrs = HashSet(settings.networkAddresses)
            if (newAddrs.add(ip.hostAddress)) {
                settings.networkAddresses = newAddrs
                loadAddresses()
            }
        }

        internal fun loadAddresses() {
            set(settings.networkAddresses)
        }

        private fun set(addrs: Collection<String>) {
            addressList.clear()
            addressList.add("127.0.0.1")
            if (currentAddress.isNotEmpty()) {
                if (!addressList.contains(currentAddress)) {
                    addressList.add(currentAddress)
                }
            }
            addressList.addAll(addrs.filterNotNull())

            // Deduplicate
            val uniqueList = addressList.distinct()
            addressList.clear()
            addressList.addAll(uniqueList)

            notifyDataSetChanged()
        }

        private fun removeAddress(ipAddress: String) {
            val newAddrs = HashSet(settings.networkAddresses)
            if (newAddrs.remove(ipAddress)) {
                settings.networkAddresses = newAddrs
            }
            set(newAddrs)
        }
    }

    companion object {
        const val TAG = "NetworkListFragment"
    }
}
