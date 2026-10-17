package com.andrerinas.openheadunit.aap

/** Identity carried by UI work and asynchronous artwork, independent of conflated connection state. */
class AaPresentationSession internal constructor() {
    @Volatile var isActive: Boolean = true
        private set

    internal fun retire() { isActive = false }
}
