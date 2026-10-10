package com.andrerinas.openheadunit.connection.wifi.scan

/** A remembered FYT start is retried once per boot, never after an explicit close. */
internal object FytBootStartPolicy {
    fun shouldStart(sdk: Int, unlocked: Boolean, enabled: Boolean, remembered: Boolean,
                    nativeHost: Boolean, running: Boolean, pendingClose: Boolean,
                    boot: Int, lastAttemptBoot: Int): Boolean =
        sdk in 26..29 && unlocked && enabled && remembered && nativeHost &&
            !running && !pendingClose && boot >= 0 && boot != lastAttemptBoot
}
