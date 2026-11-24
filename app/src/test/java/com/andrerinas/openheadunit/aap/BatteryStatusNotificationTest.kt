package com.andrerinas.openheadunit.aap

import android.util.Log
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

class BatteryStatusNotificationTest {
    @Test fun `battery notifications need no reply or error diagnostic`() {
        val transport = mock<AapTransport>()
        val control = AapControlService(transport, mock(), mock(), mock())
        val notification = Control.BatteryStatusNotification.newBuilder().setBatteryLevel(42).build()
        val errors = mutableListOf<String>()
        val previous = AppLog.LOGGER
        try {
            AppLog.LOGGER = object : AppLog.Logger {
                override fun println(priority: Int, tag: String, msg: String) {
                    if (priority >= Log.ERROR) errors.add(msg)
                }
            }
            assertEquals(0, control.execute(AapMessage(Channel.ID_CTR,
                Control.ControlMsgType.MESSAGE_BATTERY_STATUS_NOTIFICATION_VALUE, notification)))
            verifyNoInteractions(transport)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally {
            AppLog.LOGGER = previous
        }
    }
}
