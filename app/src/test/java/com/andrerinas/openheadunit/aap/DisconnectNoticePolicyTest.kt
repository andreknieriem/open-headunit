package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.DisconnectNoticePolicy.Notice
import com.andrerinas.openheadunit.connection.CommManager.EndCause
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection.Type
import org.junit.Assert.assertEquals
import org.junit.Test

class DisconnectNoticePolicyTest {
    private fun ask(
        endedBy: EndCause = EndCause.LINK_FAILED, type: Type? = Type.USB, ssl: Boolean = true,
        loopback: Boolean = false, shown: Boolean = false, attached: Boolean = true,
    ) = DisconnectNoticePolicy.notice(endedBy, type, ssl, loopback, shown, attached)

    @Test fun `a formed session lost on USB or WiFi names its transport`() {
        assertEquals(Notice.USB_LOST, ask(type = Type.USB))
        assertEquals(Notice.WIRELESS_TIP, ask(type = Type.WIFI))
    }

    @Test fun `a USB link failure with the phone gone is a pulled cable and stays silent`() {
        assertEquals(Notice.NONE, ask(type = Type.USB, attached = false))
        assertEquals(Notice.WIRELESS_TIP, ask(type = Type.WIFI, attached = false))
    }

    @Test fun `Self Mode and an unknown transport stay silent`() {
        assertEquals(Notice.NONE, ask(type = Type.WIFI, loopback = true))
        assertEquals(Notice.NONE, ask(type = null))
    }

    @Test fun `every end that is not a link failure stays silent`() {
        for (cause in EndCause.values()) {
            if (cause != EndCause.LINK_FAILED) assertEquals(cause.name, Notice.NONE, ask(endedBy = cause))
        }
    }

    @Test fun `a session that never reached SSL or a repeat in the episode stays silent`() {
        assertEquals(Notice.NONE, ask(ssl = false))
        assertEquals(Notice.NONE, ask(shown = true))
    }

    @Test fun `one formed session then eight failed attempts gives one notice`() {
        var shown = false
        var notices = 0
        val ends = listOf(true) + List(8) { false }
        for (ssl in ends) {
            val n = ask(ssl = ssl, shown = shown)
            if (n != Notice.NONE) { notices++; shown = true }
        }
        assertEquals(1, notices)
    }
}
