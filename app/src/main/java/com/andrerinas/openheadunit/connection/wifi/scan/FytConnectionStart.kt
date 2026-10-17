package com.andrerinas.openheadunit.connection.wifi.scan

/** Main-thread session gate; an IO startup checks its permit again after waiting for FYT. */
internal class FytConnectionStart {
    class Permit {
        @Volatile var valid = true
            private set
        internal fun cancel() { valid = false }
    }
    private var owner: Any? = null
    private var attempted = false
    private var permit: Permit? = null

    fun session(owner: Any) {
        if (this.owner === owner) return
        end()
        this.owner = owner
    }
    fun claim(eligible: Boolean): Permit? {
        if (!eligible || owner == null || attempted) return null
        attempted = true
        return Permit().also { permit = it }
    }
    fun cancel() { permit?.cancel(); permit = null }
    fun end() { cancel(); owner = null; attempted = false }

    companion object {
        fun eligible(sdk: Int, unlocked: Boolean, enabled: Boolean, remembered: Boolean,
                     running: Boolean, pendingClose: Boolean) =
            sdk in 26..29 && unlocked && enabled && remembered && !running && !pendingClose
    }
}
