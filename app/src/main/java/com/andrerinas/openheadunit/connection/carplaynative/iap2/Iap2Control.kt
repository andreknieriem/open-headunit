package com.andrerinas.openheadunit.connection.carplaynative.iap2

import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySigner
import com.andrerinas.openheadunit.connection.carplaynative.MfiLimits
import com.andrerinas.openheadunit.connection.carplaynative.SignerException

/**
 * The wired control session on session 10: identify, authenticate through [signer], send power, subscriptions and the
 * media remote, then answer each 0x4300 with a 0x4301. No I/O or clock: the caller sends each [Action.Send] and reports frames and ticks.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `Iap2WiredControlClient.kt`, `Iap2IdentificationClient.kt`
 * and `Iap2MfiAuthenticationClient.kt`; the cap and the one identification retry follow DiPlay (https://github.com/shihabal3amri/DiPlay).
 */
class Iap2Control(
    private val signer: CarPlaySigner,
    private val identification: Iap2Identification.Config,
    private val startSession: Iap2Messages.StartSession,
) {
    sealed class Action {
        class Send(val frame: Iap2Frame) : Action()
        class Log(val line: String) : Action()
        class Fail(val reason: String) : Action()

        /** Power, subscriptions and the media remote are sent. The phone may now send 0x4300. */
        object Ready : Action()

        /** A 0x4301 went out. [count] is the total this session. */
        class StartSent(val count: Int) : Action()

        /** A message the reducer has no rule for. */
        class Incoming(val frame: Iap2Frame) : Action()
    }

    private enum class Stage { Idle, Identifying, Authenticating, Running, Failed }

    private var stage = Stage.Idle
    private var deadlineMs = 0L
    private var omitted: Set<Int> = emptySet()
    private var retried = false
    private var availabilityReplies = 0
    private var mediaRemoteOn = false

    /** The 0x4301 count this session, replies and resends together. */
    var startsSent = 0
        private set

    val failed: Boolean get() = stage == Stage.Failed

    /** Call once the link is up. The phone sends 0x1D00 next. */
    fun start(nowMs: Long): List<Action> {
        check(stage == Stage.Idle) { "Iap2Control is single use" }
        stage = Stage.Identifying
        deadlineMs = nowMs + IDENTIFY_TIMEOUT_MS
        return emptyList()
    }

    fun tick(nowMs: Long): List<Action> {
        if ((stage == Stage.Identifying || stage == Stage.Authenticating) && nowMs >= deadlineMs) {
            val what = if (stage == Stage.Identifying) "identification" else "authentication"
            return fail("CarPlayNative: iAP2 $what timed out")
        }
        return emptyList()
    }

    fun onFrame(frame: Iap2Frame, nowMs: Long): List<Action> = when (stage) {
        Stage.Identifying -> identifying(frame, nowMs)
        Stage.Authenticating -> authenticating(frame)
        Stage.Running -> running(frame)
        else -> emptyList()
    }

    /** Sends the 0x4301 again on the caller's rule. It does not use a reply slot. */
    fun resendStart(): List<Action> = if (stage == Stage.Running) sendStart() else emptyList()

    /** Stops the media remote, once, when the session ends. */
    fun stop(): List<Action> {
        if (!mediaRemoteOn) return emptyList()
        mediaRemoteOn = false
        return listOf(Action.Send(Iap2Messages.stopMediaRemote()))
    }

    private fun identifying(frame: Iap2Frame, nowMs: Long): List<Action> = when (frame.messageId) {
        Iap2Messages.START_IDENTIFICATION -> listOf(Action.Send(Iap2Identification.build(identification, omitted)))
        Iap2Messages.IDENTIFICATION_ACCEPTED -> {
            stage = Stage.Authenticating
            deadlineMs = nowMs + AUTHENTICATE_TIMEOUT_MS
            listOf(Action.Log(IDENTIFICATION_ACCEPTED_LINE))
        }
        Iap2Messages.IDENTIFICATION_REJECTED -> rejected(frame)
        else -> unexpected(frame, "identification")
    }

    private fun rejected(frame: Iap2Frame): List<Action> {
        val rejection = try {
            Iap2Messages.rejection(frame)
        } catch (e: Iap2FormatException) {
            return fail("CarPlayNative: the iPhone rejected the identification (${e.message})")
        }
        val known = Iap2Identification.SENT + Iap2Identification.RECEIVED
        val droppable = rejection.otherParams.isEmpty() && rejection.messageIds.isNotEmpty() && known.containsAll(rejection.messageIds)
        if (retried || !droppable) {
            val flagged = (rejection.otherParams + rejection.messageIds).joinToString(",") { Iap2Messages.hexId(it) }
            return fail("CarPlayNative: the iPhone rejected the identification ($flagged)")
        }
        retried = true
        omitted = rejection.messageIds
        val line = "CarPlayNative: iAP2 identification again without " + omitted.joinToString(",") { Iap2Messages.hexId(it) }
        return listOf(Action.Log(line), Action.Send(Iap2Identification.build(identification, omitted)))
    }

    private fun authenticating(frame: Iap2Frame): List<Action> = when (frame.messageId) {
        Iap2Messages.REQUEST_CERTIFICATE -> signed { Iap2Messages.certificate(signer.certificate()) }
        Iap2Messages.REQUEST_CHALLENGE -> challenge(frame)
        Iap2Messages.AUTHENTICATION_SUCCEEDED -> succeeded()
        Iap2Messages.AUTHENTICATION_FAILED -> fail("CarPlayNative: the iPhone rejected the MFi signature")
        else -> unexpected(frame, "authentication")
    }

    private fun challenge(frame: Iap2Frame): List<Action> {
        val challenge = try {
            Iap2Messages.challenge(frame)
        } catch (e: Iap2FormatException) {
            null
        }
        if (challenge == null) return fail("CarPlayNative: the iPhone sent an MFi challenge with no data")
        if (challenge.size < MfiLimits.CHALLENGE_MIN || challenge.size > MfiLimits.CHALLENGE_MAX) {
            return fail("CarPlayNative: the MFi challenge has ${challenge.size} bytes; the signer takes ${MfiLimits.CHALLENGE_MIN} to ${MfiLimits.CHALLENGE_MAX}")
        }
        return signed { Iap2Messages.response(signer.sign(challenge)) }
    }

    /** Runs the signer. A signer that fails, or an answer too long for a parameter, ends the session. */
    private fun signed(make: () -> Iap2Frame): List<Action> = try {
        listOf(Action.Send(make()))
    } catch (e: SignerException) {
        fail("CarPlayNative: the MFi signer failed (${e.message})")
    } catch (e: IllegalArgumentException) {
        fail("CarPlayNative: the MFi signer answer does not fit a message")
    }

    private fun succeeded(): List<Action> {
        stage = Stage.Running
        mediaRemoteOn = true
        val actions = ArrayList<Action>()
        actions += Action.Log("CarPlayNative: authentication succeeded")
        actions += Action.Send(Iap2Messages.powerSourceUpdate(AVAILABLE_CURRENT_MA))
        for (subscription in Iap2Messages.subscriptions()) actions += Action.Send(subscription)
        actions += Action.Send(Iap2Messages.startMediaRemote())
        actions += Action.Ready
        return actions
    }

    private fun running(frame: Iap2Frame): List<Action> {
        if (frame.messageId != Iap2Messages.CARPLAY_AVAILABILITY) return listOf(Action.Incoming(frame))
        val offered = try {
            Iap2Messages.wiredAvailable(frame)
        } catch (e: Iap2FormatException) {
            null
        }
        val actions = ArrayList<Action>()
        actions += Action.Log("CarPlayNative: iAP2 CarPlay availability, wired " + (offered?.toString() ?: "not stated"))
        // The phone's own flag does not gate the answer: LIVI and xcertplay answer every 0x4300.
        if (availabilityReplies >= MAX_AVAILABILITY_REPLIES) {
            actions += Action.Log("CarPlayNative: start session not sent again: $MAX_AVAILABILITY_REPLIES answers already")
            return actions
        }
        availabilityReplies++
        actions += sendStart()
        return actions
    }

    private fun sendStart(): List<Action> {
        startsSent++
        return listOf(
            Action.Send(startSession.frame()),
            Action.StartSent(startsSent),
            Action.Log("CarPlayNative: start session sent (#$startsSent)"),
        )
    }

    private fun unexpected(frame: Iap2Frame, during: String) =
        fail("CarPlayNative: unexpected iAP2 message ${Iap2Messages.hexId(frame.messageId)} during $during")

    private fun fail(reason: String): List<Action> {
        stage = Stage.Failed
        return listOf(Action.Fail(reason))
    }

    companion object {
        const val IDENTIFICATION_ACCEPTED_LINE = "CarPlayNative: iAP2 identification accepted"
        const val IDENTIFY_TIMEOUT_MS = 10_000L
        const val AUTHENTICATE_TIMEOUT_MS = 30_000L
        const val MAX_AVAILABILITY_REPLIES = 3

        /** What the phone may draw over the cable. */
        const val AVAILABLE_CURRENT_MA = 2400
    }
}
