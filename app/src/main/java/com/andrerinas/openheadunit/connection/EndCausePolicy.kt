package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.CommManager.EndCause

/** Names who ended a session, so later code can tell a lost link from a choice. */
object EndCausePolicy {
    /** The transport stopped itself: the user's exit wins, then the phone's ByeBye, else a failure. */
    fun ofTransportQuit(isClean: Boolean, wasUserExit: Boolean): EndCause = when {
        wasUserExit -> EndCause.USER
        isClean -> EndCause.PHONE
        else -> EndCause.LINK_FAILED
    }

    /** An explicit disconnect: the caller's cause when it gave one, else user or app by the flag. */
    fun ofDisconnect(isUserExit: Boolean, explicit: EndCause?): EndCause =
        explicit ?: if (isUserExit) EndCause.USER else EndCause.APP
}
