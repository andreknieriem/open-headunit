package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import org.junit.Assert.*
import org.junit.Test

class GalVersionOptionsTest {
    @Test fun `response tail retains ping values without interpreting unknown field as TCP`() {
        val ping = Control.PingConfiguration.newBuilder().setTimeoutMs(3000).setIntervalMs(1000)
            .setHighLatencyThresholdMs(200).setTrackedPingCount(-1).build()
        val configBytes = Control.ConnectionConfiguration.newBuilder().setPingConfiguration(ping)
            .build().toByteArray() + byteArrayOf(18, 2, 8, 7)
        val config = Control.ConnectionConfiguration.parseFrom(configBytes)
        assertEquals(ping, config.pingConfiguration)
        assertEquals(0xffff_ffffL, config.pingConfiguration.trackedPingCount.toLong() and 0xffff_ffffL)
        assertTrue(config.unknownFields.hasField(2))
        assertArrayEquals(byteArrayOf(8, 7), config.unknownFields.getField(2).lengthDelimitedList.single().toByteArray())
        val options = Control.VersionResponseOptions.newBuilder().setConnectionConfiguration(config).build()
        assertEquals(options, Control.VersionResponseOptions.parseFrom(options.toByteArray()))
    }

    @Test fun `request options are empty and unsupported tail fields remain uninterpreted`() {
        assertEquals(0, Control.VersionRequestOptions.getDefaultInstance().serializedSize)
        val raw = byteArrayOf(8, 7)
        val options = Control.VersionRequestOptions.parseFrom(raw)
        assertTrue(options.unknownFields.hasField(1))
        assertArrayEquals(raw, options.toByteArray())
    }
}
