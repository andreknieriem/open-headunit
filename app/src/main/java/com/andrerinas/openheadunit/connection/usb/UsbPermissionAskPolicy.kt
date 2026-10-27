package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device

/**
 * Decides whether the app asks for USB permission itself or defers the ask to Android.
 * A second dialog on top of Android's, or two asks for one bus path, is the bug this prevents.
 */
object UsbPermissionAskPolicy {

    enum class Source { AUTOMATIC, USER, RECOVERY }

    enum class Action { ASK, DEFER, ASKED, HOLD }

    /** The window after our own AOA switch in which the accessory identity is ours to ask for. */
    const val OWN_SWITCH_WINDOW_MS = 10_000L

    /** A second press inside this window is the same press, so it opens no second dialog. */
    const val USER_REPEAT_MS = 3_000L

    class Inputs(
        val accessory: Boolean,
        val mtp: Boolean,
        val filterMatches: Boolean,
        val activityEnabled: Boolean,
        val automotive: Boolean,
        val sdkInt: Int,
        val listenForUsb: Boolean,
        val attachAgeMs: Long?,
        val ownSwitchAgeMs: Long?,
        val alreadyAsked: Boolean,
        val source: Source,
    )

    class Decision(private val inputs: Inputs, val action: Action) {
        /** The part of the decision log line after the device name and path. */
        override fun toString(): String = "identity=" + (if (inputs.accessory) "accessory" else "normal") +
            " mtp=" + yesNo(inputs.mtp) +
            " filter=" + (if (inputs.filterMatches) "match" else "none") +
            " activity=" + onOff(inputs.activityEnabled) +
            " listen=" + onOff(inputs.listenForUsb) +
            " attach=" + (inputs.attachAgeMs?.let { "${it}ms" } ?: "none") +
            " source=" + inputs.source.name.lowercase(java.util.Locale.US) +
            " action=" + action.name.lowercase(java.util.Locale.US)

        private fun yesNo(value: Boolean) = if (value) "yes" else "no"
        private fun onOff(value: Boolean) = if (value) "on" else "off"
    }

    /** A copy of AOSP `MtpNotificationManager.isMtpDevice`. The name needs API 21, else null. */
    fun isMtp(device: Device): Boolean = device.interfaces.any {
        (it.ifaceClass == 6 && it.subclass == 1 && it.protocol == 1) ||
            (it.ifaceClass == 255 && it.subclass == 255 && it.protocol == 0 && it.name == "MTP")
    }

    /**
     * Android owns the ask: it shows its chooser or starts another app's saved default, and no
     * public API says which. From API 24 an MTP device gets a notification instead, unless on a car.
     */
    fun defersToAndroid(inputs: Inputs): Boolean {
        val mtpNotified = inputs.sdkInt >= 24 && inputs.mtp && !inputs.automotive
        return inputs.activityEnabled && inputs.filterMatches && !mtpNotified
    }

    /** A raised home screen may hide Android's chooser, and with no default nothing can answer it. */
    fun raiseDefersToAndroid(hasPermission: Boolean, inputs: Inputs): Boolean =
        !hasPermission && defersToAndroid(inputs)

    /** A visible home covers no chooser, so a deferred ask there gets a note pointing at the USB button. */
    fun hintsUserAsk(hasPermission: Boolean, action: Action): Boolean = !hasPermission && action == Action.DEFER

    /** A user ask may reopen a dialog whose answer was lost, but not one it opened a moment ago. */
    fun userPassesOpenEntry(entryAgeMs: Long): Boolean = entryAgeMs >= USER_REPEAT_MS

    /** Save asked for this reconnection, so its scan is the user's and passes the Listen-off hold. */
    fun sourceFor(userTier: Boolean, settingsRestart: Boolean): Source =
        if (userTier || settingsRestart) Source.USER else Source.AUTOMATIC

    fun decide(inputs: Inputs): Decision = Decision(inputs, action(inputs))

    private fun action(inputs: Inputs): Action {
        if (inputs.alreadyAsked && inputs.source != Source.USER) return Action.ASKED
        if (inputs.source == Source.USER) return Action.ASK
        if (!inputs.listenForUsb) {
            if (inputs.source == Source.RECOVERY) return Action.ASK
            val age = inputs.ownSwitchAgeMs
            return if (inputs.accessory && age != null && age < OWN_SWITCH_WINDOW_MS) Action.ASK else Action.HOLD
        }
        // An open chooser, a cancel and another app's default look the same from here, and a timer
        // would re-prompt the first two, so a plug this process saw stays Android's until it detaches.
        return if (defersToAndroid(inputs) && inputs.attachAgeMs != null) Action.DEFER else Action.ASK
    }
}
