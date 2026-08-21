package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The classification that decides how much memory the video pipeline is allowed to hold.
 *
 * The two anchors worth naming: the 1 GB MediaTek unit from #839 has to come out CONSTRAINED, and
 * the tablets this app is tested on must not - shrinking the pipeline on a device that was fine is
 * how a fix for one report becomes a regression for everyone else.
 */
class DeviceMemoryProfileTest {

    @Test
    fun `the 1GB head unit this exists for is constrained`() {
        assertEquals(
            DeviceMemoryProfile.CONSTRAINED,
            DeviceMemoryProfile.classify(totalRamMb = 1024, heapLimitMb = 96, systemLowRamFlag = false)
        )
    }

    @Test
    fun `a modern tablet is ample and its buffers are left alone`() {
        assertEquals(
            DeviceMemoryProfile.AMPLE,
            DeviceMemoryProfile.classify(totalRamMb = 8192, heapLimitMb = 512, systemLowRamFlag = false)
        )
    }

    @Test
    fun `a mid-range unit is normal, which is today's behaviour`() {
        assertEquals(
            DeviceMemoryProfile.NORMAL,
            DeviceMemoryProfile.classify(totalRamMb = 2048, heapLimitMb = 192, systemLowRamFlag = false)
        )
    }

    @Test
    fun `the low-RAM flag is believed when it is set`() {
        assertEquals(
            DeviceMemoryProfile.CONSTRAINED,
            DeviceMemoryProfile.classify(totalRamMb = 8192, heapLimitMb = 512, systemLowRamFlag = true)
        )
    }

    @Test
    fun `a small heap ceiling is constraining whatever the total RAM says`() {
        // Units that report a lot of RAM and then hand the app a 64MB heap exist; the heap is what
        // the reassembly and pool buffers come out of.
        assertEquals(
            DeviceMemoryProfile.CONSTRAINED,
            DeviceMemoryProfile.classify(totalRamMb = 4096, heapLimitMb = 64, systemLowRamFlag = false)
        )
    }

    @Test
    fun `ample needs both numbers, not one`() {
        assertEquals(
            "plenty of RAM but a modest heap is not ample",
            DeviceMemoryProfile.NORMAL,
            DeviceMemoryProfile.classify(totalRamMb = 8192, heapLimitMb = 128, systemLowRamFlag = false)
        )
        assertEquals(
            "a large heap on a small device is not ample",
            DeviceMemoryProfile.NORMAL,
            DeviceMemoryProfile.classify(totalRamMb = 2048, heapLimitMb = 512, systemLowRamFlag = false)
        )
    }

    @Test
    fun `an unreadable number is unknown rather than constraining`() {
        // A platform that fails to report totalMem must not silently shrink the pipeline; zero means
        // "we do not know", and the decision falls to whatever else was readable.
        assertEquals(
            DeviceMemoryProfile.NORMAL,
            DeviceMemoryProfile.classify(totalRamMb = 0, heapLimitMb = 256, systemLowRamFlag = false)
        )
        assertEquals(
            DeviceMemoryProfile.NORMAL,
            DeviceMemoryProfile.classify(totalRamMb = 0, heapLimitMb = 0, systemLowRamFlag = false)
        )
        assertEquals(
            "a readable small heap still counts",
            DeviceMemoryProfile.CONSTRAINED,
            DeviceMemoryProfile.classify(totalRamMb = 0, heapLimitMb = 64, systemLowRamFlag = false)
        )
    }

    @Test
    fun `the boundaries fall on the documented side`() {
        assertEquals(
            DeviceMemoryProfile.CONSTRAINED,
            DeviceMemoryProfile.classify(DeviceMemoryProfile.CONSTRAINED_TOTAL_RAM_MB, 256, false)
        )
        assertEquals(
            DeviceMemoryProfile.NORMAL,
            DeviceMemoryProfile.classify(DeviceMemoryProfile.CONSTRAINED_TOTAL_RAM_MB + 1, 256, false)
        )
        assertEquals(
            DeviceMemoryProfile.AMPLE,
            DeviceMemoryProfile.classify(
                DeviceMemoryProfile.AMPLE_TOTAL_RAM_MB,
                DeviceMemoryProfile.AMPLE_HEAP_LIMIT_MB,
                false
            )
        )
        assertEquals(
            DeviceMemoryProfile.NORMAL,
            DeviceMemoryProfile.classify(
                DeviceMemoryProfile.AMPLE_TOTAL_RAM_MB,
                DeviceMemoryProfile.AMPLE_HEAP_LIMIT_MB - 1,
                false
            )
        )
    }

    @Test
    fun `the reading carries every input it decided from`() {
        val text = DeviceMemoryReading(
            profile = DeviceMemoryProfile.CONSTRAINED,
            totalRamMb = 1024,
            heapLimitMb = 96,
            memoryClassMb = 96,
            systemLowRamFlag = false,
        ).toString()
        for (field in listOf("CONSTRAINED", "totalRam=1024MB", "heapLimit=96MB", "memoryClass=96MB", "lowRamFlag=false")) {
            org.junit.Assert.assertTrue("reading is missing $field: $text", text.contains(field))
        }
    }
}
