package com.andrerinas.openheadunit.utils

import java.util.UUID

/**
 * startService delivers VPN configuration later on the service main thread. Stopping the
 * currently bound VPN does not retract a delivered start command. Its request token must
 * still own the tunnel when that command runs; stop invalidates it before closing the tun.
 * Keep the check and the platform effect under one monitor so a concurrent stop cannot
 * land between validation and establish(). Tokens do not survive a process restart.
 */
internal class DummyVpnStartRequests {
    private var current: String? = null

    @Synchronized
    fun issue(): String = UUID.randomUUID().toString().also { current = it }

    @Synchronized
    fun runIfCurrent(token: String?, start: () -> Unit): Boolean {
        if (token == null || current != token) return false
        start()
        return true
    }

    @Synchronized
    fun stop(close: () -> Unit) {
        current = null
        close()
    }
}
