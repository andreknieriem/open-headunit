package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.app.BtAutoStartRearmPolicy
import com.andrerinas.openheadunit.connection.ConnectionOperation

/**
 * A relaunch while the unit sleeps armed the radios and the wake poke within seconds.
 * Automatic bring-ups wait until the screen comes on; only the user's own request passes.
 */
object WirelessSleepHold {

    /** The command and its optional resource-owned continuation survive the wake unchanged. */
    class Held internal constructor(
        val force: Boolean,
        val operation: ConnectionOperation,
        val startsAtTake: Int,
        val resume: (() -> Unit)?,
    )

    /** Force is combined only for commands with the same ticket and Save owner. */
    @Volatile
    private var held: Held? = null

    /**
     * The screen alone decides: an ACC-off with no screen cycle after it would hold a lit unit with
     * nothing to replay it. A screen that cannot be read counts as awake.
     */
    fun isAsleep(screenInteractive: Boolean?): Boolean = screenInteractive == false

    /**
     * A formed session drops the hold, since its own end re-arms wireless. An attempt in flight
     * replays unforced, so the arbiter owes the bring-up back if that attempt fails.
     */
    fun replayForce(held: Boolean?, connected: Boolean, connecting: Boolean): Boolean? = when {
        held == null || connected -> null
        connecting -> false
        else -> held
    }

    /**
     * A forced hold waits out an attempt in flight instead: unforced, it meets the restart
     * refusal against a stack that is still started, and the arbiter never learns it is owed.
     */
    fun waitsForAttempt(held: Boolean, connected: Boolean, connecting: Boolean): Boolean =
        held && !connected && connecting

    /**
     * The replay asks what the Bluetooth auto-start asks, so a phone that arrives with the screen
     * does not get a second rebuild under its join. Null means the replay goes ahead.
     */
    fun replayVeto(force: Boolean, networkComingUp: Boolean?, attemptInFlight: Boolean?): String? =
        if (!force) null
        else BtAutoStartRearmPolicy.vetoReason(
            sessionUp = false,
            handshakeActive = null,
            attemptInFlight = attemptInFlight,
            groupUp = null,
            networkComingUp = networkComingUp
        )

    /**
     * A bring-up that started after the take already rebuilt the stack, so the replay does not
     * force a second rebuild under the phone's join.
     */
    fun replayKeepsForce(force: Boolean, startsAtTake: Int, startsNow: Int): Boolean =
        force && startsNow == startsAtTake

    fun refusesBringUp(asleep: Boolean, userRequested: Boolean, manual: Boolean): Boolean =
        asleep && !userRequested && !manual

    /** True while the stack runs on a bring-up the user asked for, which the radio gate obeys too. */
    @Volatile
    var startedByUser: Boolean = false
        private set

    /** Bring-ups that actually started; each taken hold retains its own comparison baseline. */
    @Volatile
    var starts: Int = 0
        private set

    fun refusesRadioEnable(asleep: Boolean, startedByUser: Boolean): Boolean =
        asleep && !startedByUser

    /** A join recovery in the dark only moves the group's address; the wake rebuilds it instead. */
    fun refusesGroupRecreate(asleep: Boolean, startedByUser: Boolean): Boolean =
        asleep && !startedByUser

    /** The stack is starting, so a held bring-up has nothing left to replay. */
    @Synchronized
    fun started(userRequested: Boolean) {
        held = null
        startedByUser = userRequested
        starts++
    }

    /** A stop ends the arming the user asked for, but not a bring-up held for the wake. */
    @Synchronized
    fun stopped() {
        startedByUser = false
    }

    /** Callers validate [operation] under lifecycle before recording this owner's work. */
    @Synchronized
    fun hold(force: Boolean, operation: ConnectionOperation, resume: (() -> Unit)? = null): Boolean {
        val previous = held
        val sameOwner = previous?.operation?.ticket == operation.ticket &&
            previous.operation.save === operation.save
        val first = !sameOwner
        held = Held(force || (sameOwner && previous?.force == true), operation, starts, resume)
        return first
    }

    /** Take this command once; its start baseline cannot be changed by a later take. */
    @Synchronized
    fun take(): Held? {
        val command = held ?: return null
        held = null
        return Held(command.force, command.operation, starts, command.resume)
    }

    @Synchronized
    fun clear() {
        held = null
        startedByUser = false
    }
}
