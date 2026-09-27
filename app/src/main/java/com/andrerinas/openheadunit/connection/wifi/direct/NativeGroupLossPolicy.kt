package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Some platforms remove our group on their own, and the connection broadcast is all that says so.
 * Credentials left standing after it send the phone to a network that no longer exists.
 */
object NativeGroupLossPolicy {

    fun invalidatesOnDisconnect(isNativeMode: Boolean, wasGroupOwner: Boolean): Boolean =
        isNativeMode && wasGroupOwner
}
