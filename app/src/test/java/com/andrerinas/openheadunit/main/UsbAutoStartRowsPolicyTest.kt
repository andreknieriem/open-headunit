package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.UsbAutoStartRowsPolicy.Rows
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAutoStartRowsPolicyTest {

    private fun rows(showsUsb: Boolean = true, listen: Boolean = true, connect: Boolean = true,
                     autoStart: Boolean = true, carLauncher: Boolean = false) =
        UsbAutoStartRowsPolicy.rows(showsUsb, listen, connect, autoStart, carLauncher)

    @Test
    fun `all three rows show with everything on`() {
        assertEquals(Rows(connect = true, connectEnabled = true, open = true, reopen = true), rows())
    }

    @Test
    fun `detection off shows connect disabled and hides the other two`() {
        assertEquals(Rows(connect = true, connectEnabled = false, open = false, reopen = false), rows(listen = false))
    }

    @Test
    fun `connect off hides open and reopen`() {
        assertEquals(Rows(connect = true, connectEnabled = true, open = false, reopen = false), rows(connect = false))
    }

    @Test
    fun `reopen needs open on and no car launcher`() {
        assertEquals(false, rows(autoStart = false).reopen)
        assertEquals(true, rows(autoStart = false).open)
        assertEquals(false, rows(carLauncher = true).reopen)
    }

    @Test
    fun `no USB rows when USB is not a chosen connection type`() {
        assertEquals(Rows(connect = false, connectEnabled = false, open = false, reopen = false), rows(showsUsb = false))
    }
}
