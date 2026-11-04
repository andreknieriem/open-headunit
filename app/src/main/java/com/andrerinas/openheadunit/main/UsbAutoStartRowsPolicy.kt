package com.andrerinas.openheadunit.main

/**
 * Which USB rows the auto-start screen shows. "Connect when plugged in" stays visible but disabled
 * with detection off, so the user can see why it stopped; the rows that depend on it hide.
 */
object UsbAutoStartRowsPolicy {
    data class Rows(val connect: Boolean, val connectEnabled: Boolean, val open: Boolean, val reopen: Boolean)

    fun rows(showsUsb: Boolean, listen: Boolean, connect: Boolean, autoStart: Boolean, carLauncher: Boolean): Rows {
        if (!showsUsb) return Rows(connect = false, connectEnabled = false, open = false, reopen = false)
        val open = listen && connect
        return Rows(connect = true, connectEnabled = listen, open = open, reopen = open && autoStart && !carLauncher)
    }
}
