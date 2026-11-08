package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Phones that did not answer, with the time their back-off ends. Process-wide on purpose: a manager
 * restart must not bring an away phone back to the head of the plan.
 */
object WakeBackoffs {
    private val until = HashMap<String, Long>()

    @Synchronized fun noteUnanswered(mac: String, now: Long) {
        until[mac.uppercase()] = now + WakePlanPolicy.BACKOFF_MS
    }

    @Synchronized fun clear(mac: String) {
        until.remove(mac.uppercase())
    }

    @Synchronized fun snapshot(): Map<String, Long> = HashMap(until)
}
