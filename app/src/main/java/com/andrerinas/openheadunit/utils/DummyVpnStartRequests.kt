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

    // The issued start whose command has not run yet: it holds the VPN slot as a live tun does.
    @Volatile private var pending: String? = null

    val isPending: Boolean get() = pending != null

    @Synchronized
    fun issue(): String = UUID.randomUUID().toString().also { current = it; pending = it }

    @Synchronized
    fun runIfCurrent(token: String?, start: () -> Unit): Boolean {
        if (token == null || current != token) return false
        try { start() } finally { if (pending == token) pending = null }
        return true
    }

    /** The start command for [token] will not arrive. */
    @Synchronized
    fun abandon(token: String) {
        if (pending == token) pending = null
    }

    @Synchronized
    fun stop(close: () -> Unit) {
        current = null
        pending = null
        close()
    }
}
