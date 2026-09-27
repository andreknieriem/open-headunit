package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WppHandshakeSessionTest {

    private fun session() = WppHandshakeSession()

    private fun msg(type: Int, status: Int? = null) = WppEvent.MessageReceived(type, status)

    /**
     * A session opened and past the version stage, which is where every test that is not about
     * the opener starts. The timeout is the no-answer path, which is what a phone that ignores
     * type 4 produces and is the commoner of the two.
     */
    private fun openedSession(): WppHandshakeSession =
        session().also { it.on(WppEvent.SocketReady); it.on(WppEvent.StageTimeout) }

    /** Drives a session to [WppStage.SETTLING] the ordinary way and returns it. */
    private fun settledSession(): WppHandshakeSession {
        val s = openedSession()
        s.on(WppEvent.CredentialsReady)
        assertEquals(WppStage.AWAIT_INFO_REQUEST, s.stage)
        assertEquals(listOf(WppAction.SendInfoResponse), s.on(msg(WppMessageType.INFO_REQUEST)))
        assertEquals(WppStage.SETTLING, s.stage)
        return s
    }

    // --- opening the exchange -------------------------------------------------------------

    @Test
    fun `we always open the exchange, so a silent handshake is always ours to explain`() {
        // NativeAaHandshakeManager's spokeToPhone flag turns true on this first action, so a
        // handshake that receives nothing now always means our bytes went out and the phone
        // answered none of them. What separates that from the phone going first is
        // messagesReceived, not whether we sent anything, and the banner reads both.
        val s = session()

        assertEquals(listOf(WppAction.SendVersionRequest), s.on(WppEvent.SocketReady))
        assertEquals(WppStage.AWAIT_VERSION, s.stage)
    }

    @Test
    fun `a phone that asks for credentials before they exist is answered when they arrive`() {
        val s = openedSession()
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
    fun `a phone that never answers type 4 still completes the rest of the exchange`() {
        val s = openedSession()

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
        val s = openedSession()

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
        // The phone answered everything and then said no, which is the case the silence-counting
        // backoff cannot see. JoinRefusalPolicy is what bounds it.
        assertTrue((actions[0] as WppAction.Fail).joinRefused)
        assertFalse((actions[0] as WppAction.Fail).phoneWasSilent)
    }

    @Test
    fun `a failed start response is treated the same way while settling`() {
        val s = settledSession()

        val actions = s.on(msg(WppMessageType.START_RESPONSE, 7))

        assertEquals(WppStage.FAILED, s.stage)
        assertTrue(actions[0] is WppAction.Fail)
        assertEquals(WppAction.ResumePoke, actions[1])
        // A rejected endpoint is not the phone failing to reach the network, so it must not spend
        // the join-refusal budget: retrying it promptly is still right.
        assertFalse((actions[0] as WppAction.Fail).joinRefused)
    }

    @Test
    fun `a successful start response is informational in both stages it can arrive in`() {
        val awaiting = openedSession()
        awaiting.on(WppEvent.CredentialsReady)
        assertEquals(emptyList<WppAction>(), awaiting.on(msg(WppMessageType.START_RESPONSE, 0)))
        assertEquals(WppStage.AWAIT_INFO_REQUEST, awaiting.stage)

        val settling = settledSession()
        assertEquals(emptyList<WppAction>(), settling.on(msg(WppMessageType.START_RESPONSE, 0)))
        assertEquals(WppStage.SETTLING, settling.stage)
    }

    @Test
    fun `a network taken down under a joining phone ends the handshake and wakes it again`() {
        val s = settledSession()
        s.on(msg(WppMessageType.START_RESPONSE, 0))

        val actions = s.on(WppEvent.NetworkWithdrawn)

        assertEquals(WppStage.FAILED, s.stage)
        val fail = actions[0] as WppAction.Fail
        assertEquals(WppAction.ResumePoke, actions[1])
        // Ours, not the phone's: it must not spend the join-refusal backoff or read as silence.
        assertFalse(fail.joinRefused)
        assertFalse(fail.phoneWasSilent)
    }

    @Test
    fun `a withdrawal before the credentials went out changes nothing`() {
        val s = openedSession()
        s.on(WppEvent.CredentialsReady)
        assertEquals(WppStage.AWAIT_INFO_REQUEST, s.stage)

        assertEquals(emptyList<WppAction>(), s.on(WppEvent.NetworkWithdrawn))
        assertEquals(WppStage.AWAIT_INFO_REQUEST, s.stage)
    }

    // --- a phone that needs no credentials ------------------------------------------------

    @Test
    fun `a phone already on the network reports the join instead of asking for credentials`() {
        // The shape the hotspot transport produces: the phone dials us from inside the network,
        // so there is nothing to hand it and type 2 never comes.
        val s = session()
        s.on(WppEvent.SocketReady)
        s.on(msg(WppMessageType.VERSION_RESPONSE))
        s.on(WppEvent.CredentialsReady)
        assertEquals(WppStage.AWAIT_INFO_REQUEST, s.stage)

        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.START_RESPONSE, 0)))
        assertEquals(emptyList<WppAction>(), s.on(msg(WppMessageType.CONNECT_STATUS, 0)))
        assertEquals(WppStage.SETTLING, s.stage)

        assertEquals(listOf(WppAction.CompleteSuccess), s.on(WppEvent.TcpSessionUp))
        assertEquals(WppStage.DONE, s.stage)
    }

    @Test
    fun `a join failure before any credential request fails the same way as after one`() {
        val s = openedSession()
        s.on(WppEvent.CredentialsReady)

        val actions = s.on(msg(WppMessageType.CONNECT_STATUS, -1))

        assertEquals(WppStage.FAILED, s.stage)
        assertTrue(actions[0] is WppAction.Fail)
        assertEquals(WppAction.ResumePoke, actions[1])
        assertTrue((actions[0] as WppAction.Fail).joinRefused)
    }

    @Test
    fun `the session landing completes the handshake from every stage it can reach`() {
        // Without this the caller spins: it feeds TcpSessionUp on every tick once the session is
        // up, and a stage that ignored it would never reach its own timeout either.
        for (drive in listOf<(WppHandshakeSession) -> Unit>(
            { },
            { it.on(WppEvent.SocketReady) },
            { it.on(WppEvent.SocketReady); it.on(WppEvent.StageTimeout) },
            { it.on(WppEvent.SocketReady); it.on(WppEvent.StageTimeout); it.on(WppEvent.CredentialsReady) }
        )) {
            val s = session()
            drive(s)
            assertEquals(listOf(WppAction.CompleteSuccess), s.on(WppEvent.TcpSessionUp))
            assertEquals(WppStage.DONE, s.stage)
        }
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
        val awaitCredentials = openedSession()
        val awaitInfo = openedSession().also { it.on(WppEvent.CredentialsReady) }
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
        val s = openedSession()
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
        assertFalse(fail.joinRefused)
        assertEquals(1, s.messagesReceived)
    }

    @Test
    fun `credentials that never arrive fail the handshake`() {
        val s = openedSession()

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
            WppEvent.NetworkWithdrawn,
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
