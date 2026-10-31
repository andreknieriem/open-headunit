package com.andrerinas.openheadunit.connection.wifi.direct

/** OS-removal seam only; native retention ordering is covered by its coordinator tests. */
internal object RetainedNativeGroup {
    var stopRequests = 0
    fun requestStop(isCurrent: () -> Boolean) {
        if (isCurrent()) stopRequests++
    }
}
