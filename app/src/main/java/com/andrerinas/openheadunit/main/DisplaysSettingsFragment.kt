package com.andrerinas.openheadunit.main

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.usb.UsbPermissionAsks
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.decoder.video.AuxDisplayProfilePolicy
import com.andrerinas.openheadunit.main.settings.DisplaysChoices
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.main.settings.showNumericInputDialog
import com.andrerinas.openheadunit.secondscreen.SecondScreenHub
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import com.andrerinas.openheadunit.secondscreen.UsbDisplayAdapterPolicy
import com.andrerinas.openheadunit.secondscreen.ms912x.Ms912xModes
import com.andrerinas.openheadunit.secondscreen.network.NetworkStreamPolicy
import com.andrerinas.openheadunit.secondscreen.rtp.RtpDestinationPolicy
import com.andrerinas.openheadunit.secondscreen.rtp.RtpRefreshPolicy
import com.andrerinas.openheadunit.secondscreen.usbdisplay.UsbDisplayProbe
import com.andrerinas.openheadunit.utils.DisplayTargets
import com.andrerinas.openheadunit.utils.NetworkAddresses
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The projection display and the second screen, with the rows of the chosen output.
 * Every row saves at once; the main list reads the values again when this page closes.
 */
class DisplaysSettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var adapter: SettingsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_displays, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = App.provide(requireContext()).settings
        adapter = SettingsAdapter()
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { leave() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        // The USB display and the attached displays change while another screen is on top.
        if (::adapter.isInitialized) render()
    }

    private fun render() {
        adapter.submitList(rows())
    }

    private fun leave() {
        try {
            if (!findNavController().navigateUp()) requireActivity().finish()
        } catch (_: Exception) {
            requireActivity().finish()
        }
    }

    private fun rows(): List<SettingItem> {
        val items = mutableListOf<SettingItem>()
        // The projection moves only where the display service allows it, so the sub header goes with the row.
        if (DisplayTargets.canMoveProjection) {
            items.add(SettingItem.SubHeader("displaysProjection", R.string.projection_display))
            addProjectionDisplayRow(items)
        }
        items.add(SettingItem.SubHeader("displaysSecondScreen", R.string.displays_second_screen))
        addAuxDisplayRows(items)
        return items
    }

    /**
     * Which Android display the projection uses. Saved at once, because it only takes effect at
     * the next connect. The row names each attached panel, so a unit with two shows which it picked.
     */
    private fun addProjectionDisplayRow(items: MutableList<SettingItem>) {
        val choices = DisplaysChoices.projectionChoices(requireContext(), settings)
        items.add(SettingItem.SettingEntry(
            stableId = "projectionDisplay",
            nameResId = R.string.projection_display,
            helpResId = R.string.projection_display_help,
            value = choices.labels[choices.selected],
            searchKeywords = choices.labels.joinToString(" "),
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_projection_display)
                    .setSingleChoiceItems(choices.labels.toTypedArray(), choices.selected) { dialog, which ->
                        choices.picks.getOrNull(which)?.invoke()
                        settings.commit()
                        dialog.dismiss()
                        render()
                    }
                    .show()
            }
        ))
    }

    /** Which Android display carries the second screen, among those attached besides the projection's. */
    private fun addAuxAndroidDisplayRow(items: MutableList<SettingItem>) {
        val projectionDisplayId = DisplayTargets.choose(requireContext(), settings).displayId
        val attached = DisplayTargets.candidates(requireContext()).filter { it.displayId != projectionDisplayId }
        val labels = attached.map { DisplaysChoices.displayLabel(it) }
        val selected = attached.indexOfFirst { it.displayId == settings.auxDisplayId }
        items.add(SettingItem.SettingEntry(
            stableId = "auxAndroidDisplay",
            nameResId = R.string.aux_android_display,
            helpResId = R.string.aux_android_display_help,
            value = labels.getOrNull(selected) ?: getString(R.string.aux_android_display_none),
            searchKeywords = labels.joinToString(" "),
            onClick = { _ ->
                if (labels.isNotEmpty()) {
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.aux_android_display)
                        .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                            attached.getOrNull(which)?.let { if (it.displayId != settings.auxDisplayId) settings.auxDisplayId = it.displayId }
                            settings.commit()
                            dialog.dismiss()
                            render()
                        }
                        .show()
                }
            }
        ))
    }

    /** The stream size of the network and RTP outputs. */
    private fun addAuxStreamSizeRow(items: MutableList<SettingItem>) {
        val labels = DisplaysChoices.streamSizeLabels()
        val sizeIndex = settings.auxNetworkSize.coerceIn(0, labels.size - 1)
        items.add(SettingItem.SettingEntry(
            stableId = "auxNetworkSize",
            nameResId = R.string.aux_network_size,
            helpResId = R.string.aux_network_size_help,
            value = labels[sizeIndex],
            searchKeywords = labels.joinToString(" "),
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.aux_network_size)
                    .setSingleChoiceItems(labels.toTypedArray(), sizeIndex) { dialog, which ->
                        if (which != settings.auxNetworkSize) settings.auxNetworkSize = which
                        settings.commit()
                        dialog.dismiss()
                        render()
                    }
                    .show()
            }
        ))
    }

    /** The network stream's size and port, and the command that shows it on a computer. */
    private fun addAuxNetworkRows(items: MutableList<SettingItem>) {
        addAuxStreamSizeRow(items)
        items.add(SettingItem.SettingEntry(
            stableId = "auxNetworkPort",
            nameResId = R.string.aux_network_port,
            helpResId = R.string.aux_network_port_help,
            value = settings.auxNetworkPort.toString(),
            onClick = { _ ->
                showNumericInputDialog(requireContext(), getString(R.string.aux_network_port), null, settings.auxNetworkPort) {
                    val port = NetworkStreamPolicy.portOrDefault(it)
                    if (port != settings.auxNetworkPort) settings.auxNetworkPort = port
                    settings.commit()
                    render()
                }
            }
        ))
        val host = NetworkAddresses.stationIpv4(requireContext()) ?: "127.0.0.1"
        items.add(SettingItem.InfoBanner(
            stableId = "auxNetworkHint",
            textResId = R.string.aux_network_hint,
            text = getString(
                R.string.aux_network_hint,
                NetworkStreamPolicy.ffplayCommand(host, settings.auxNetworkPort),
                settings.auxNetworkPort,
            ),
        ))
    }

    /** The RTP push's stream size, destination and port, and the SDP a receiver opens. */
    private fun addAuxRtpPushRows(items: MutableList<SettingItem>) {
        addAuxStreamSizeRow(items)
        items.add(SettingItem.SettingEntry(
            stableId = "auxRtpDestination",
            nameResId = R.string.aux_rtp_destination,
            helpResId = R.string.aux_rtp_destination_help,
            value = settings.auxRtpDestination.ifEmpty { getString(R.string.aux_rtp_destination_unset) },
            onClick = { _ ->
                showTextInputDialog(requireContext(), R.string.aux_rtp_destination, settings.auxRtpDestination) { typed ->
                    val text = typed.trim()
                    if (text.isNotEmpty() && RtpDestinationPolicy.parse(text) == null) {
                        ToastUtils.showToast(requireContext(), R.string.aux_rtp_destination_invalid, Toast.LENGTH_LONG, force = true)
                    } else {
                        if (text != settings.auxRtpDestination) settings.auxRtpDestination = text
                        settings.commit()
                        render()
                    }
                }
            }
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "auxRtpPort",
            nameResId = R.string.aux_rtp_port,
            helpResId = R.string.aux_rtp_port_help,
            value = settings.auxRtpPort.toString(),
            onClick = { _ ->
                showNumericInputDialog(requireContext(), getString(R.string.aux_rtp_port), null, settings.auxRtpPort) {
                    val port = RtpDestinationPolicy.portOrDefault(it)
                    if (port != settings.auxRtpPort) settings.auxRtpPort = port
                    settings.commit()
                    render()
                }
            }
        ))
        val refreshes = RtpRefreshPolicy.INTERVALS_S
        val refreshLabels = DisplaysChoices.rtpRefreshLabels(requireContext())
        val refreshIndex = refreshes.indexOf(settings.auxRtpRefreshSeconds).coerceAtLeast(0)
        items.add(SettingItem.SettingEntry(
            stableId = "auxRtpRefresh",
            nameResId = R.string.aux_rtp_refresh,
            helpResId = R.string.aux_rtp_refresh_help,
            value = refreshLabels[refreshIndex],
            searchKeywords = refreshLabels.joinToString(" "),
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.aux_rtp_refresh)
                    .setSingleChoiceItems(refreshLabels.toTypedArray(), refreshIndex) { dialog, which ->
                        if (refreshes[which] != settings.auxRtpRefreshSeconds) settings.auxRtpRefreshSeconds = refreshes[which]
                        settings.commit()
                        dialog.dismiss()
                        render()
                    }
                    .show()
            }
        ))
        items.add(SettingItem.InfoBanner(
            stableId = "auxRtpHint",
            textResId = R.string.aux_rtp_hint,
            text = getString(
                R.string.aux_rtp_hint,
                RtpDestinationPolicy.sdp(settings.auxRtpDestination, settings.auxRtpPort, settings.auxRtpStreamParameters),
                RtpDestinationPolicy.FFPLAY_COMMAND,
            ),
        ))
    }

    /** The MacroSilicon adapter's mode and pixel format, and whether one is plugged in. */
    private fun addAuxMs912xRows(items: MutableList<SettingItem>) {
        val modes = DisplaysChoices.MS912X_MODES
        val modeLabels = DisplaysChoices.ms912xModeLabels()
        val modeIndex = modes.indexOf(settings.ms912xMode)
        items.add(SettingItem.SettingEntry(
            stableId = "auxMs912xMode",
            nameResId = R.string.aux_ms912x_mode,
            helpResId = R.string.aux_ms912x_mode_help,
            value = modeLabels[modeIndex],
            searchKeywords = modeLabels.joinToString(" "),
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.aux_ms912x_mode)
                    .setSingleChoiceItems(modeLabels.toTypedArray(), modeIndex) { dialog, which ->
                        if (modes[which] != settings.ms912xMode) settings.ms912xMode = modes[which]
                        settings.commit()
                        dialog.dismiss()
                        render()
                    }
                    .show()
            }
        ))
        val formats = DisplaysChoices.MS912X_FORMATS
        val formatLabels = DisplaysChoices.ms912xFormatLabels(requireContext())
        val effective = Ms912xModes.effectiveFormat(settings.ms912xMode, settings.ms912xFormat)
        items.add(SettingItem.SettingEntry(
            stableId = "auxMs912xFormat",
            nameResId = R.string.aux_ms912x_format,
            helpResId = R.string.aux_ms912x_format_help,
            value = formatLabels[formats.indexOf(effective)],
            searchKeywords = formatLabels.joinToString(" "),
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.aux_ms912x_format)
                    .setSingleChoiceItems(formatLabels.toTypedArray(), formats.indexOf(effective)) { dialog, which ->
                        if (formats[which] != settings.ms912xFormat) settings.ms912xFormat = formats[which]
                        settings.commit()
                        dialog.dismiss()
                        render()
                    }
                    .show()
            }
        ))
        val usb = requireContext().getSystemService(Context.USB_SERVICE) as? UsbManager
        val attached = usb?.deviceList?.values?.any {
            UsbDisplayAdapterPolicy.kindOf(it.vendorId, it.productId, emptyList()) != null
        } == true
        items.add(SettingItem.InfoBanner(
            stableId = "auxMs912xHint",
            textResId = if (attached) R.string.aux_ms912x_hint_attached else R.string.aux_ms912x_hint_missing,
        ))
    }

    /** Whether an Open Headunit USB display is attached, what it last said, and a way to ask it. */
    private fun addAuxUsbDisplayRows(items: MutableList<SettingItem>) {
        val usb = requireContext().getSystemService(Context.USB_SERVICE) as? UsbManager
        val found = usb?.let { UsbDisplayProbe.find(it) }
        val last = settings.usbDisplayLastTarget
        val status = when {
            found == null -> getString(R.string.aux_usb_display_missing)
            last == null -> getString(R.string.aux_usb_display_unread)
            else -> getString(R.string.aux_usb_display_ready, last.widthPx, last.heightPx, last.densityDpi)
        }
        items.add(SettingItem.InfoBanner(stableId = "auxUsbDisplayStatus", textResId = R.string.aux_usb_display_unread, text = status))
        if (found == null || usb == null) return
        items.add(SettingItem.ActionButton(
            stableId = "auxUsbDisplayCheck",
            textResId = R.string.aux_usb_display_check,
            onClick = {
                if (!usb.hasPermission(found.device)) {
                    usb.requestPermission(found.device, UsbReceiver.createPermissionPendingIntent(requireContext(), UsbPermissionAsks.newToken()))
                } else {
                    SecondScreenHub.usbDisplayTarget(requireContext(), settings)
                    render()
                }
            }
        ))
    }

    /** A second Android Auto picture for a cluster or passenger screen, and the chosen output's rows. */
    private fun addAuxDisplayRows(items: MutableList<SettingItem>) {
        val output = DisplaysChoices.outputChoices(requireContext(), settings)
        items.add(SettingItem.SettingEntry(
            stableId = "auxDisplay",
            nameResId = R.string.aux_display,
            helpResId = R.string.aux_display_help,
            value = output.labels[output.selected],
            searchKeywords = output.labels.joinToString(" "),
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_aux_display)
                    .setSingleChoiceItems(output.labels.toTypedArray(), output.selected) { dialog, which ->
                        val enabled = which > 0
                        if (enabled != settings.auxDisplayEnabled) settings.auxDisplayEnabled = enabled
                        DisplaysChoices.OFFERED_OUTPUTS.getOrNull(which - 1)?.let { if (it != settings.auxOutput) settings.auxOutput = it }
                        settings.commit()
                        dialog.dismiss()
                        render()
                    }
                    .show()
            }
        ))

        if (!settings.auxDisplayEnabled) return
        when (settings.auxOutput) {
            SecondScreenOutputPolicy.Output.ANDROID_DISPLAY -> addAuxAndroidDisplayRow(items)
            SecondScreenOutputPolicy.Output.NETWORK -> addAuxNetworkRows(items)
            SecondScreenOutputPolicy.Output.RTP_PUSH -> addAuxRtpPushRows(items)
            SecondScreenOutputPolicy.Output.MS912X -> addAuxMs912xRows(items)
            SecondScreenOutputPolicy.Output.USB_DISPLAY -> addAuxUsbDisplayRows(items)
            else -> {}
        }

        val roleLabels = DisplaysChoices.roleLabels(requireContext()).toTypedArray()
        val roleIndex = if (settings.auxDisplayRole == AuxDisplayProfilePolicy.Role.CLUSTER) 1 else 0
        items.add(SettingItem.SettingEntry(
            stableId = "auxDisplayRole",
            nameResId = R.string.aux_display_role,
            helpResId = R.string.aux_display_role_help,
            value = roleLabels[roleIndex],
            searchKeywords = roleLabels.joinToString(" "),
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_aux_display_role)
                    .setSingleChoiceItems(roleLabels, roleIndex) { dialog, which ->
                        val role = if (which == 1) AuxDisplayProfilePolicy.Role.CLUSTER else AuxDisplayProfilePolicy.Role.AUXILIARY
                        if (role != settings.auxDisplayRole) settings.auxDisplayRole = role
                        settings.commit()
                        dialog.dismiss()
                        render()
                    }
                    .show()
            }
        ))

        // A cluster shows what the phone chooses, so there is nothing to pick.
        if (!AuxDisplayProfilePolicy.announcesContent(settings.auxDisplayRole)) return

        val contentLabels = DisplaysChoices.contentLabels(requireContext()).toTypedArray()
        val contentIndex =
            if (AuxDisplayProfilePolicy.contentKeycodeOrDefault(settings.auxDisplayContent) ==
                AuxDisplayProfilePolicy.KEYCODE_TURN_CARD
            ) 1 else 0
        items.add(SettingItem.SettingEntry(
            stableId = "auxDisplayContent",
            nameResId = R.string.aux_display_content,
            helpResId = R.string.aux_display_content_help,
            value = contentLabels[contentIndex],
            searchKeywords = contentLabels.joinToString(" "),
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_aux_display_content)
                    .setSingleChoiceItems(contentLabels, contentIndex) { dialog, which ->
                        val content = if (which == 1) {
                            AuxDisplayProfilePolicy.KEYCODE_TURN_CARD
                        } else {
                            AuxDisplayProfilePolicy.KEYCODE_NAVIGATION
                        }
                        if (content != settings.auxDisplayContent) settings.auxDisplayContent = content
                        settings.commit()
                        dialog.dismiss()
                        render()
                    }
                    .show()
            }
        ))
    }

    private fun showTextInputDialog(
        context: Context,
        titleResId: Int,
        initialValue: String,
        onConfirm: (String) -> Unit
    ) {
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initialValue)
        }

        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        params.setMargins(margin, 8, margin, 8)
        container.addView(editView, params)

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(editView.text.toString())
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editView.windowToken, 0)
            }
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editView.requestFocus()
    }
}
