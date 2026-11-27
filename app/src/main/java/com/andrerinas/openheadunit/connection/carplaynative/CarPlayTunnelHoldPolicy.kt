package com.andrerinas.openheadunit.connection.carplaynative

/** The debug intent that runs the tunnel check and keeps the tunnel up, so a rig run can make the dummy VPN refusal fire. */
object CarPlayTunnelHoldPolicy {
    const val ACTION = "com.andrerinas.openheadunit.DEBUG_CARPLAY_TUNNEL_HOLD"
    const val EXTRA_SECONDS = "seconds"

    /** Only the shell holds this, so only `adb shell am broadcast` can send the hold. */
    const val SENDER_PERMISSION = "android.permission.DUMP"

    /** The hold in seconds, 1 to [CarPlayTunnelCheck.MAX_HOLD_SECONDS], or null when the extra is missing or below 1. */
    fun seconds(extra: Int): Int? = if (extra < 1) null else extra.coerceAtMost(CarPlayTunnelCheck.MAX_HOLD_SECONDS)

    sealed class Decision {
        class Hold(val seconds: Int) : Decision()
        class Refused(val line: String) : Decision()
    }

    /** A live or connecting Android Auto session, or a live native session, refuses the hold before the tunnel check. */
    fun decide(extra: Int, aaSessionLive: Boolean, nativeLive: Boolean): Decision {
        val held = seconds(extra) ?: return Decision.Refused("CarPlayNative: tunnel hold ignored, the seconds extra is not 1 to ${CarPlayTunnelCheck.MAX_HOLD_SECONDS}")
        return when {
            aaSessionLive -> Decision.Refused("CarPlayNative: tunnel hold refused, an Android Auto session is live")
            nativeLive -> Decision.Refused("CarPlayNative: tunnel hold refused, a native CarPlay session is live")
            else -> Decision.Hold(held)
        }
    }
}
