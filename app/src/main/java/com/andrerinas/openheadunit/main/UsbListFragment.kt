package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.usb.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbDeviceDiagnostics
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsbListFragment : Fragment() {
    private lateinit var adapter: DeviceAdapter
    private lateinit var settings: Settings
    private lateinit var noUsbDeviceTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: MaterialToolbar

    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)
        recyclerView = view.findViewById(android.R.id.list)
        noUsbDeviceTextView = view.findViewById(R.id.no_usb_device_text)
        toolbar = view.findViewById(R.id.toolbar)

        settings = Settings(requireContext())
        adapter = DeviceAdapter(requireContext(), settings, lifecycleScope)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Add padding
        val padding = resources.getDimensionPixelSize(R.dimen.list_padding)
        recyclerView.setPadding(padding, padding, padding, padding)
        recyclerView.clipToPadding = false

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.title = getString(R.string.usb)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        mainViewModel.usbDevices.observe(viewLifecycleOwner, Observer {
            val allowDevices = settings.allowedDevices
            adapter.setData(it, allowDevices)

            if (it.isEmpty()) {
                // A unit whose ROM never declared USB host enumerates nothing whatever is plugged
                // in, so "no device connected" reads as the user's fault when it is not.
                noUsbDeviceTextView.setText(
                    if (UsbDeviceDiagnostics.hasUsbHostFeature(requireContext())) R.string.no_usb_device_connected
                    else R.string.no_usb_host_support
                )
                noUsbDeviceTextView.visibility = VISIBLE
                recyclerView.visibility = GONE
            } else {
                noUsbDeviceTextView.visibility = GONE
                recyclerView.visibility = VISIBLE
            }
        })
    }

    override fun onPause() {
        super.onPause()
        settings.commit()
    }

    private class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val allowButton = itemView.findViewById<Button>(android.R.id.button1)
        val startButton = itemView.findViewById<Button>(android.R.id.button2)
    }

    private class DeviceAdapter(
        private val mContext: Context,
        private val mSettings: Settings,
        private val scope: CoroutineScope,
    ) : RecyclerView.Adapter<DeviceViewHolder>(), View.OnClickListener {
        private var allowedDevices: MutableSet<String> = mutableSetOf()
        private var deviceList: List<UsbDeviceCompat> = listOf()
        private var lastClickTime: Long = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(mContext).inflate(R.layout.list_item_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = deviceList[position]

            // Background styling logic
            val isTop = position == 0
            val isBottom = position == itemCount - 1
            val bgRes = when {
                isTop && isBottom -> R.drawable.bg_setting_single
                isTop -> R.drawable.bg_setting_top
                isBottom -> R.drawable.bg_setting_bottom
                else -> R.drawable.bg_setting_middle
            }
            holder.itemView.setBackgroundResource(bgRes)

            val vidPidText = " (${mSettings.formatUsbVidPidDisplay(device.vendorId, device.productId)})"
            val title = TextUtils.htmlEncode("${device.uniqueName}$vidPidText")
            val path = TextUtils.htmlEncode(device.deviceName)
            holder.startButton.text = HtmlCompat.fromHtml(
                "<b>$title</b><br/>$path",
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            holder.startButton.tag = position
            holder.startButton.setOnClickListener(this)

            val isBlacklisted = mSettings.isUsbDeviceBlacklisted(device.wrappedDevice)

            if (device.isInAccessoryMode) {
                holder.allowButton.setText(R.string.allowed)
                holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700))
                holder.allowButton.isEnabled = false
            } else if (isBlacklisted) {
                holder.allowButton.setText(R.string.blacklisted)
                holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_red_700))
                holder.allowButton.tag = position
                holder.allowButton.isEnabled = true
                holder.allowButton.setOnClickListener(this)
            } else {
                if (allowedDevices.contains(device.uniqueName)) {
                    holder.allowButton.setText(R.string.allowed)
                    holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700))
                } else {
                    holder.allowButton.setText(R.string.ignored)
                    holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_orange_700))
                }
                holder.allowButton.tag = position
                holder.allowButton.isEnabled = true
                holder.allowButton.setOnClickListener(this)
            }
        }

        override fun getItemCount(): Int {
            return deviceList.size
        }

        override fun onClick(v: View) {
            // Debounce clicks (prevent double tap)
            if (SystemClock.elapsedRealtime() - lastClickTime < 1000) {
                return
            }
            lastClickTime = SystemClock.elapsedRealtime()

            val position = v.tag as? Int ?: return
            if (position < 0 || position >= deviceList.size) {
                return
            }
            val device = deviceList[position]
            if (v.id == android.R.id.button1) {
                val isBlacklisted = mSettings.isUsbDeviceBlacklisted(device.wrappedDevice)
                val isAllowed = allowedDevices.contains(device.uniqueName)

                when {
                    isBlacklisted -> {
                        // Blacklisted -> Ignored
                        mSettings.removeUsbDeviceFromBlacklist(device.wrappedDevice)
                    }
                    isAllowed -> {
                        // Allowed -> Blacklisted
                        allowedDevices.remove(device.uniqueName)
                        mSettings.allowedDevices = allowedDevices
                        mSettings.addUsbDeviceToBlacklist(device.wrappedDevice)
                    }
                    else -> {
                        // Ignored -> Allowed
                        allowedDevices.add(device.uniqueName)
                        mSettings.allowedDevices = allowedDevices
                    }
                }
                notifyDataSetChanged()
            } else {
                if (mSettings.isUsbDeviceBlacklisted(device.wrappedDevice)) {
                    // The row already says "Blacklisted" and the button used to connect anyway.
                    ToastUtils.showToast(mContext, R.string.blacklisted, Toast.LENGTH_SHORT, force = true)
                    return
                }
                AapService.instance?.liftUsbCancel("a USB device was chosen from the list")
                if (App.provide(mContext).commManager.isConnected) {

                    // Already connected -> bring existing projection to front
                    val aapIntent = AapProjectionActivity.intent(mContext).apply {
                        putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    mContext.startActivity(aapIntent)
                } else if (device.isInAccessoryMode) {
                    // Device is in Accessory Mode but we are NOT connected.
                    // Start connection immediately.
                    ToastUtils.showToast(mContext, R.string.android_auto_starting, Toast.LENGTH_SHORT)
                    (mContext as? MainActivity)?.beginAutoConnect(
                        "manual USB list (accessory mode)",
                        MainActivity.ConnectionUiMode.OVERLAY
                    )
                    ContextCompat.startForegroundService(mContext, Intent(mContext, AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                        putExtra(AapService.EXTRA_USER_REQUESTED, true)
                    })
                } else {
                    // Standard connection flow
                    val usbManager = mContext.getSystemService(Context.USB_SERVICE) as UsbManager
                    if (usbManager.hasPermission(device.wrappedDevice)) {
                        val usbMode = UsbAccessoryMode(usbManager)
                        val useLibusb = mSettings.useLibusb
                        // Eight control transfers at a 1 s timeout each. On the main thread that is
                        // an ANR against any device that stops answering rather than refusing.
                        scope.launch(Dispatchers.IO) {
                            val switched = usbMode.connectAndSwitch(device.wrappedDevice, useLibusb)
                            withContext(Dispatchers.Main) {
                                if (switched) {
                                    ToastUtils.showToast(mContext, R.string.switching_to_android_auto, Toast.LENGTH_SHORT)
                                    (mContext as? MainActivity)?.beginAutoConnect(
                                        "manual USB list (AOA switch)",
                                        MainActivity.ConnectionUiMode.OVERLAY
                                    )
                                } else {
                                    ToastUtils.showToast(mContext, R.string.switch_failed, Toast.LENGTH_SHORT, force = true)
                                }
                                notifyDataSetChanged()
                            }
                        }
                    } else {
                        ToastUtils.showToast(mContext, R.string.requesting_usb_permission, Toast.LENGTH_SHORT)
                        ContextCompat.startForegroundService(mContext, Intent(mContext, AapService::class.java))
                        usbManager.requestPermission(
                            device.wrappedDevice,
                            UsbReceiver.createPermissionPendingIntent(mContext)
                        )
                    }
                }
            }
        }

        fun setData(deviceList: List<UsbDeviceCompat>, allowedDevices: Set<String>) {
            this.allowedDevices = allowedDevices.toMutableSet()
            this.deviceList = deviceList
            notifyDataSetChanged()
        }
    }

}
