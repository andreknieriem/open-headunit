package com.andrerinas.openheadunit.connection.wifi

/**
 * Whether the wireless stack stays down while the settings screen is open.
 *
 * A wake poke that works raises the projection over whatever the user is configuring, so the stack
 * stops rather than only deferring its retry loop. One predicate for the pause, because every input
 * can change while the screen is up and two spellings would drift; [rearmsOnRelease] then decides
 * what lifting it does, which is not the same question.
 */
object SettingsScreenPausePolicy {

    /**
     * @param sessionLive a session is never torn down for the settings screen.
     * @param qrHold the setup QR reads the running launcher, so it holds the stack up.
     */
    fun pauses(settingsForeground: Boolean, sessionLive: Boolean, qrHold: Boolean): Boolean =
        settingsForeground && !sessionLive && !qrHold

    /**
     * Whether lifting the pause re-arms the stack. Only the setup QR, which reads the running
     * launcher, or a request held behind the screen (a wireless Save, or the phone arriving over
     * Bluetooth); a visit with neither leaves the stack down, and the WiFi button is the way back up.
     */
    fun rearmsOnRelease(qrHold: Boolean, wirelessRequestHeld: Boolean): Boolean =
        qrHold || wirelessRequestHeld
}
