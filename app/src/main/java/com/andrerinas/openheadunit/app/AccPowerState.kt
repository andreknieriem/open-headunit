package com.andrerinas.openheadunit.app

/**
 * Whether the car has told us it is switched off, and by which action.
 *
 * Process-wide because the service hears the ACC broadcast while the hotspot provider decides what
 * to do about a vanished access point; switching it back on then fights the unit powering down.
 */
object AccPowerState {

    @Volatile
    var offAction: String? = null
        private set

    /** When the car last came back on after an off we heard, 0 if never. */
    @Volatile
    var wokeAtMs = 0L
        private set

    val isOff: Boolean get() = offAction != null

    fun noteOff(action: String) {
        offAction = action
    }

    fun noteOn() {
        if (offAction == null) return
        offAction = null
        wokeAtMs = System.currentTimeMillis()
    }

    /** A long sleep ended on a unit that may name no ACC intent: a wake all the same. */
    fun noteWake(nowMs: Long = System.currentTimeMillis()) {
        offAction = null
        wokeAtMs = nowMs
    }
}
