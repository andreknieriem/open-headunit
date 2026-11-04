package com.andrerinas.openheadunit.connection.usb

/**
 * What the attach screen does with a plug-in, in the order it asks. The blacklist and a live
 * session are checked before this. With "Connect when plugged in" off it opens the home screen,
 * because the user answered Android's "Open", and leaves the phone in its normal identity.
 */
object UsbAttachActivityPolicy {
    enum class Action { FINISH, HAND_OFF, START_SERVICE, OPEN_HOME, SWITCH }

    fun decide(
        accessory: Boolean,
        hasPermission: Boolean,
        locked: Boolean,
        held: Boolean,
        connectOnPlugIn: Boolean,
        autoStart: Boolean,
        mayAttempt: Boolean,
        switchLive: Boolean,
    ): Action = when {
        held -> Action.HAND_OFF
        accessory && !hasPermission -> Action.HAND_OFF
        accessory && locked -> Action.FINISH
        accessory -> Action.START_SERVICE
        // Nothing can open before unlock, and nothing is to be switched.
        !connectOnPlugIn -> if (locked) Action.FINISH else Action.OPEN_HOME
        !mayAttempt -> Action.HAND_OFF
        locked && !autoStart -> Action.FINISH
        switchLive -> Action.FINISH
        else -> Action.SWITCH
    }
}
