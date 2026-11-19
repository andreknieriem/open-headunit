package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.HomePrimaryPolicy
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Connection
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUiHelperDongleLabelsTest {

    private fun decision(primary: Connection, status: Status) = HomePrimaryPolicy.Decision(
        primary = primary,
        status = status,
        barConnections = emptyList()
    )

    @Test
    fun `usb ready uses cable labels when dongle option is off`() {
        val d = decision(Connection.USB, Status.READY)
        assertEquals(R.string.home_kicker_cable, HomeUiHelper.kickerRes(d, usbAsWireless = false))
        assertEquals(R.string.home_subtitle_usb, HomeUiHelper.subtitleRes(d, usbAsWireless = false))
        assertEquals(R.string.home_action_connect_usb, HomeUiHelper.actionRes(d, usbAsWireless = false))
    }

    @Test
    fun `usb ready uses wireless labels when dongle option is on`() {
        val d = decision(Connection.USB, Status.READY)
        assertEquals(R.string.home_kicker_wireless, HomeUiHelper.kickerRes(d, usbAsWireless = true))
        assertEquals(R.string.home_subtitle_usb_dongle, HomeUiHelper.subtitleRes(d, usbAsWireless = true))
        assertEquals(R.string.home_action_connect_wireless_dongle, HomeUiHelper.actionRes(d, usbAsWireless = true))
    }

    @Test
    fun `usb active session shows wirelessly subtitle when dongle option is on`() {
        val d = decision(Connection.USB, Status.ACTIVE_SESSION)
        assertEquals(R.string.home_kicker_active, HomeUiHelper.kickerRes(d, usbAsWireless = true))
        assertEquals(R.string.home_subtitle_usb_dongle, HomeUiHelper.subtitleRes(d, usbAsWireless = true))
        assertEquals(R.string.home_action_back_to_android_auto, HomeUiHelper.actionRes(d, usbAsWireless = true))
    }

    @Test
    fun `wifi labels are unaffected by dongle option`() {
        val d = decision(Connection.WIFI, Status.READY)
        assertEquals(R.string.home_kicker_wireless, HomeUiHelper.kickerRes(d, usbAsWireless = true))
        assertEquals(R.string.home_subtitle_wifi, HomeUiHelper.subtitleRes(d, usbAsWireless = true))
        assertEquals(R.string.home_action_connect_wifi, HomeUiHelper.actionRes(d, usbAsWireless = true))
    }
}
