package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WppHandshakeSessionTest {

    private fun session(versionExchange: Boolean = true) = WppHandshakeSession(versionExchange)

    private fun msg(type: Int, status: Int? = null) = WppEvent.MessageReceived(type, status)

    /** Drives a session to [WppStage.SETTLING] the ordinary way and returns it. */
    private fun settledSession(versionExchange: Boolean = false): WppHandshakeSession {
        val s = session(versionExchange)
        s.on(WppEvent.SocketReady)
        if (versionExchange) s.on(msg(WppMessageType.VERSION_RESPONSE))
        s.on(WppEvent.CredentialsReady)
        assertEquals(WppStage.AWAIT_INFO_REQUEST, s.stage)
        assertEquals(listOf(WppAction.SendInfoResponse), s.on(msg(WppMessageType.INFO_REQUEST)))
        assertEquals(WppStage.SETTLING, s.stage)
        return s
    }

    // --- opening the exchange -------------------------------------------------------------

    @Test
    fun `with the version exchange off the phone can speak before we have sent anything`() {
        // The precondition behind NativeAaHandshakeManager's spokeToPhone flag, which decides
        // whether a silent handshake is recorded as this unit's Bluetooth failing to transmit.
        // With the version exchange off, SocketReady emits no action at all, so an INFO_REQUEST
        // arriving here is the phone opening the exchange against a unit that has written
        // nothing. If this ever changes so that we always send first, the flag stops being the
        // thing that separates "we transmitted and got nothing" from "the phone went first" -
        // and the failure banner would start accusing units that were never given the chance.
        val s = session(versionExchange = false)

        assertEquals(emptyList<WppAction>(), s.on(WppEvent.SocketReady))
        assertEquals(WppStage.AWAIT_CREDENTIALS, s.stage)

        // Accepted, not ignored, and still nothing sent: the credentials have not arrived yet.
        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.INFO_REQUEST)))
        assertFalse(s.isTerminal())

        // Once they do, both replies go out together.
        assertEquals(
            listOf(WppAction.SendStartRequest, WppAction.SendInfoResponse),
            s.on(WppEvent.CredentialsReady)
        )
        assertEquals(WppStage.SETTLING, s.stage)
    }

    @Test
    fun `with the version exchange off the wire behaviour is the one that shipped`() {
        val s = session(versionExchange = false)

        assertEquals(emptyList<WppAction>(), s.on(WppEvent.SocketReady))
        assertEquals(WppStage.AWAIT_CREDENTIALS, s.stage)
        assertEquals(listOf(WppAction.SendStartRequest), s.on(WppEvent.CredentialsReady))
        assertEquals(listOf(WppAction.SendInfoResponse), s.on(msg(WppMessageType.INFO_REQUEST)))
        assertEquals(WppStage.SETTLING, s.stage)
    }

    @Test
    fun `with the version exchange on, type 4 goes out before anything else`() {
        val s = session()

        assertEquals(listOf(WppAction.SendVersionRequest), s.on(WppEvent.SocketReady))
        assertEquals(WppStage.AWAIT_VERSION, s.stage)
        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.VERSION_RESPONSE)))
        assertEquals(WppStage.AWAIT_CREDENTIALS, s.stage)
        assertEquals(listOf(WppAction.SendStartRequest), s.on(WppEvent.CredentialsReady))
    }

    @Test
    fun `a phone that ignores type 4 does not fail the handshake, it just carries on`() {
        val s = session()
        s.on(WppEvent.SocketReady)

        assertEquals(emptyList<WppAction>(), s.on(WppEvent.StageTimeout))

        assertEquals(WppStage.AWAIT_CREDENTIALS, s.stage)
        assertEquals(listOf(WppAction.SendStartRequest), s.on(WppEvent.CredentialsReady))
    }

    @Test
    fun `credentials arriving mid-version-exchange do not jump the queue`() {
        val s = session()
        s.on(WppEvent.SocketReady)

        // Holding them is the entire point of sending type 4 first.
        assertEquals(emptyList<WppAction>(), s.on(WppEvent.CredentialsReady))
        assertEquals(WppStage.AWAIT_VERSION, s.stage)

        assertEquals(listOf(WppAction.SendStartRequest), s.on(msg(WppMessageType.VERSION_RESPONSE)))
        assertEquals(WppStage.AWAIT_INFO_REQUEST, s.stage)
    }

    @Test
    fun `credentials held across the version timeout are sent when it expires`() {
        val s = session()
        s.on(WppEvent.SocketReady)
        s.on(WppEvent.CredentialsReady)

        assertEquals(listOf(WppAction.SendStartRequest), s.on(WppEvent.StageTimeout))
        assertEquals(WppStage.AWAIT_INFO_REQUEST, s.stage)
    }

    // --- the impatient phone --------------------------------------------------------------

    @Test
    fun `a phone that asks for credentials early is answered as soon as they exist`() {
        val s = session()
        s.on(WppEvent.SocketReady)

        // Type 2 before we have anything to send: latched, not dropped and not answered yet.
        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.INFO_REQUEST)))
        assertEquals(WppStage.AWAIT_CREDENTIALS, s.stage)

        assertEquals(
            listOf(WppAction.SendStartRequest, WppAction.SendInfoResponse),
            s.on(WppEvent.CredentialsReady)
        )
        assertEquals(WppStage.SETTLING, s.stage)
    }

    @Test
    fun `an early type 2 during the credentials wait is latched too`() {
        val s = session(versionExchange = false)
        s.on(WppEvent.SocketReady)

        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.INFO_REQUEST)))
        assertEquals(
            listOf(WppAction.SendStartRequest, WppAction.SendInfoResponse),
            s.on(WppEvent.CredentialsReady)
        )
    }

    // --- settling -------------------------------------------------------------------------

    @Test
    fun `the projection session landing completes the handshake`() {
        val s = settledSession()

        assertEquals(listOf(WppAction.CompleteSuccess), s.on(WppEvent.TcpSessionUp))
        assertEquals(WppStage.DONE, s.stage)
    }

    @Test
    fun `a phone still joining buys itself more time`() {
        val s = settledSession()

        assertEquals(listOf(WppAction.ExtendSettle), s.on(msg(WppMessageType.CONNECT_STATUS, 0)))
        assertEquals(WppStage.SETTLING, s.stage)
        assertEquals(
            NativeHandoffPolicy.SETTLE_TIMEOUT_MS + WppHandshakeSession.SETTLE_EXTENSION_MS,
            s.currentStageTimeoutMs()
        )
    }

    @Test
    fun `extensions stop at the cap so a phone that never arrives cannot hold us forever`() {
        val s = settledSession()

        var granted = 0
        repeat(20) { if (s.on(msg(WppMessageType.CONNECT_STATUS, 0)).isNotEmpty()) granted++ }

        assertTrue("at least one extension should be granted", granted > 0)
        assertEquals(NativeHandoffPolicy.MAX_SETTLE_MS, s.currentStageTimeoutMs())
        assertEquals(WppStage.SETTLING, s.stage)
    }

    @Test
    fun `a phone reporting it could not join fails fast and lets the poke resume`() {
        val s = settledSession()

        val actions = s.on(msg(WppMessageType.CONNECT_STATUS, -1))

        assertEquals(WppStage.FAILED, s.stage)
        assertEquals(2, actions.size)
        assertTrue(actions[0] is WppAction.Fail)
        assertEquals(WppAction.ResumePoke, actions[1])
    }

    @Test
    fun `a failed start response is treated the same way while settling`() {
        val s = settledSession()

        val actions = s.on(msg(WppMessageType.START_RESPONSE, 7))

        assertEquals(WppStage.FAILED, s.stage)
        assertTrue(actions[0] is WppAction.Fail)
        assertEquals(WppAction.ResumePoke, actions[1])
    }

    @Test
    fun `a successful start response is informational in both stages it can arrive in`() {
        val awaiting = session(versionExchange = false)
        awaiting.on(WppEvent.SocketReady)
        awaiting.on(WppEvent.CredentialsReady)
        assertEquals(emptyList<WppAction>(), awaiting.on(msg(WppMessageType.START_RESPONSE, 0)))
        assertEquals(WppStage.AWAIT_INFO_REQUEST, awaiting.stage)

        val settling = settledSession()
        assertEquals(emptyList<WppAction>(), settling.on(msg(WppMessageType.START_RESPONSE, 0)))
        assertEquals(WppStage.SETTLING, settling.stage)
    }

    @Test
    fun `a start response we could not parse is never read as a failure`() {
        val s = settledSession()

        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.START_RESPONSE, null)))
        assertEquals(WppStage.SETTLING, s.stage)
    }

    @Test
    fun `a phone that asks for credentials twice gets them twice`() {
        val s = settledSession()

        assertEquals(listOf(WppAction.SendInfoResponse), s.on(msg(WppMessageType.INFO_REQUEST)))
        assertEquals(WppStage.SETTLING, s.stage)
    }

    @Test
    fun `the settle timeout resumes the poke and leaves the listeners alone`() {
        val s = settledSession()

        assertEquals(listOf(WppAction.ResumePoke), s.on(WppEvent.SettleTimeout))
        assertEquals(WppStage.FAILED, s.stage)
    }

    // --- pings ----------------------------------------------------------------------------

    @Test
    fun `a ping is echoed in every live stage and never changes the stage`() {
        val awaitVersion = session().also { it.on(WppEvent.SocketReady) }
        val awaitCredentials = session(versionExchange = false).also { it.on(WppEvent.SocketReady) }
        val awaitInfo = session(versionExchange = false).also {
            it.on(WppEvent.SocketReady); it.on(WppEvent.CredentialsReady)
        }
        val settling = settledSession()

        for (s in listOf(awaitVersion, awaitCredentials, awaitInfo, settling)) {
            val stageBefore = s.stage
            assertEquals(
                "stage $stageBefore",
                listOf(WppAction.SendPingResponse),
                s.on(msg(WppMessageType.PING_REQUEST))
            )
            assertEquals(stageBefore, s.stage)
        }
    }

    @Test
    fun `an inbound ping response is a keepalive and is ignored`() {
        val s = settledSession()

        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.PING_RESPONSE)))
        assertEquals(WppStage.SETTLING, s.stage)
    }

    @Test
    fun `message types we do not model are ignored rather than fatal`() {
        val s = settledSession()

        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.SETUP_INFO)))
        assertEquals(emptyList<WppAction>(), s.on(msg(42)))
        assertEquals(WppStage.SETTLING, s.stage)
    }

    // --- failures and the silent-phone flag -----------------------------------------------

    @Test
    fun `a phone that never asks for credentials fails, and is recorded as silent`() {
        val s = session(versionExchange = false)
        s.on(WppEvent.SocketReady)
        s.on(WppEvent.CredentialsReady)

        val actions = s.on(WppEvent.StageTimeout)

        assertEquals(WppStage.FAILED, s.stage)
        val fail = actions.single() as WppAction.Fail
        assertTrue("the phone said nothing at all", fail.phoneWasSilent)
    }

    @Test
    fun `a phone that answered something is not recorded as silent`() {
        val s = session()
        s.on(WppEvent.SocketReady)
        s.on(msg(WppMessageType.VERSION_RESPONSE))
        s.on(WppEvent.CredentialsReady)

        val fail = s.on(WppEvent.StageTimeout).single() as WppAction.Fail

        // This unit's Bluetooth is carrying data, so the handshake backoff must not count it.
        assertFalse(fail.phoneWasSilent)
        assertEquals(1, s.messagesReceived)
    }

    @Test
    fun `credentials that never arrive fail the handshake`() {
        val s = session(versionExchange = false)
        s.on(WppEvent.SocketReady)

        val fail = s.on(WppEvent.CredentialsUnavailable).single() as WppAction.Fail

        assertEquals(WppStage.FAILED, s.stage)
        assertTrue(fail.phoneWasSilent)
    }

    @Test
    fun `every message the phone sends is counted, whatever it was`() {
        val s = settledSession()
        s.on(msg(WppMessageType.PING_REQUEST))
        s.on(msg(WppMessageType.PING_RESPONSE))
        s.on(msg(999))

        // 1 for the type 2 that got us to SETTLING, plus the three above.
        assertEquals(4, s.messagesReceived)
    }

    // --- terminal behaviour ---------------------------------------------------------------

    @Test
    fun `a finished session ignores everything, repeatedly`() {
        val s = settledSession()
        s.on(WppEvent.TcpSessionUp)
        assertEquals(WppStage.DONE, s.stage)
        val countAtCompletion = s.messagesReceived

        for (event in listOf(
            WppEvent.TcpSessionUp,
            WppEvent.SettleTimeout,
            WppEvent.StageTimeout,
            WppEvent.CredentialsReady,
            WppEvent.CredentialsUnavailable,
            msg(WppMessageType.PING_REQUEST),
            msg(WppMessageType.CONNECT_STATUS, -1)
        )) {
            assertEquals(emptyList<WppAction>(), s.on(event))
            assertEquals(WppStage.DONE, s.stage)
        }
        assertEquals("a terminal session counts nothing", countAtCompletion, s.messagesReceived)
    }

    @Test
    fun `a failed session cannot be revived by a late success`() {
        val s = settledSession()
        s.on(WppEvent.SettleTimeout)

        assertEquals(emptyList<WppAction>(), s.on(WppEvent.TcpSessionUp))
        assertEquals(WppStage.FAILED, s.stage)
    }

    // --- stage deadlines ------------------------------------------------------------------

    @Test
    fun `each stage carries the deadline the caller should hold it to`() {
        val s = session()
        assertNull("nothing is out on the wire yet", s.currentStageTimeoutMs())

        s.on(WppEvent.SocketReady)
        assertEquals(WppHandshakeSession.VERSION_RESPONSE_TIMEOUT_MS, s.currentStageTimeoutMs())

        s.on(WppEvent.StageTimeout)
        assertNull("the credentials wait is bounded by the caller, not by us", s.currentStageTimeoutMs())

        s.on(WppEvent.CredentialsReady)
        assertEquals(WppHandshakeSession.INFO_REQUEST_TIMEOUT_MS, s.currentStageTimeoutMs())

        s.on(msg(WppMessageType.INFO_REQUEST))
        assertEquals(NativeHandoffPolicy.SETTLE_TIMEOUT_MS, s.currentStageTimeoutMs())

        s.on(WppEvent.TcpSessionUp)
        assertNull("a finished handshake has no deadline", s.currentStageTimeoutMs())
    }
}
