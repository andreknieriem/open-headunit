package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.CommManager.EndCause

/**
 * Whether a USB session end schedules a re-check of the bus. With auto-connect on the answer is
 * always yes. With it off, only a formed session lost to a link failure, with the phone still
 * attached, resumes: the setting starts sessions, it does not forbid resuming one.
 */
object UsbLinkLossRecheckPolicy {
    fun shouldRecheck(
        lastWasUsb: Boolean,
        autoConnectSetting: Boolean,
        isUserExit: Boolean,
        endedBy: EndCause,
        reachedSsl: Boolean,
        accessoryOnBus: Boolean,
    ): Boolean {
        if (!lastWasUsb) return false
        if (autoConnectSetting) return true
        return !isUserExit && endedBy == EndCause.LINK_FAILED && reachedSsl && accessoryOnBus
    }
}
