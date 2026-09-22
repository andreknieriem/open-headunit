package com.andrerinas.openheadunit.secondscreen

import android.content.Context
import com.andrerinas.openheadunit.utils.Settings
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class SecondScreenHubTest {
    @After fun reset() { SecondScreenHub.close() }

    @Test fun `with the setting off nothing is announced and the context is not touched`() {
        val context = mock<Context>()
        val settings = mock<Settings>()
        whenever(settings.auxDisplayEnabled).thenReturn(false)
        assertNull(SecondScreenHub.announce(context, settings))
        assertNull(SecondScreenHub.announced)
        verifyNoInteractions(context)
    }
}
