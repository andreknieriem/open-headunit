package com.andrerinas.openheadunit.main.settings

import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaysSummaryPolicyTest {

    @Test
    fun `second screen off shows the projection label alone`() {
        val s = DisplaysSummaryPolicy.summarize("Built-in display", false, "Network stream")
        assertEquals(DisplaysSummaryPolicy.Summary(null, listOf("Built-in display")), s)
    }

    @Test
    fun `second screen on shows both parts for each output label`() {
        listOf("Network stream", "RTP push to an address", "USB HDMI adapter").forEach { label ->
            val s = DisplaysSummaryPolicy.summarize("Any external display", true, label)
            assertEquals(R.string.displays_value_both, s.textResId)
            assertEquals(listOf("Any external display", label), s.args)
        }
    }

    @Test
    fun `an empty output label falls back to the projection label`() {
        assertEquals(
            DisplaysSummaryPolicy.Summary(null, listOf("Built-in display")),
            DisplaysSummaryPolicy.summarize("Built-in display", true, "")
        )
    }

    @Test
    fun `an empty projection label names the second screen only`() {
        assertEquals(
            DisplaysSummaryPolicy.Summary(R.string.displays_value_second, listOf("Network stream")),
            DisplaysSummaryPolicy.summarize("", true, "Network stream")
        )
        assertEquals(R.string.displays_value_second_off, DisplaysSummaryPolicy.summarize("", false, "Network stream").textResId)
        assertEquals(R.string.displays_value_second_off, DisplaysSummaryPolicy.summarize("", true, "").textResId)
    }
}
