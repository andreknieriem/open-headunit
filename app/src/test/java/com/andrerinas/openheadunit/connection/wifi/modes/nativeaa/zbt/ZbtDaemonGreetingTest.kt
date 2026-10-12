package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.utils.AppLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ZbtDaemonGreetingTest {
    @Test fun readsFrameBeforeWallClockDeadline() {
        val logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        try {
            val frame = ZbtFraming.encodeFrame(byteArrayOf(8, 1), ZbtMessages.INIT_INFO)
            ZbtByteChannel(ByteArrayInputStream(frame), ByteArrayOutputStream()).use {
                assertEquals(ZbtReachabilityPolicy.Verdict.ANSWERED,
                    ZbtDaemonReachability.awaitGreeting(it, System.currentTimeMillis() + 3000))
            }
        } finally { AppLog.LOGGER = logger }
    }

    @Test fun closedPeerIsNotPumpedRepeatedlyUntilDeadline() {
        val channel = mock(ZbtByteChannel::class.java)
        whenever(channel.pumpOnce(any(), any())).thenReturn(ZbtByteChannel.Pump.ENDED)
        assertEquals(ZbtReachabilityPolicy.Verdict.LISTENING_SILENT,
            ZbtDaemonReachability.awaitGreeting(channel, System.currentTimeMillis() + 3000))
        verify(channel, times(1)).pumpOnce(any(), any())
    }
}
