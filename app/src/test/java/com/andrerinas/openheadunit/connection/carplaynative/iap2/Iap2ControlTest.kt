package com.andrerinas.openheadunit.connection.carplaynative.iap2

import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySigner
import com.andrerinas.openheadunit.connection.carplaynative.SignerException
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session order follows xcertplay's `Iap2WirelessSessionReplayTest` (https://github.com/shilapi/xcertplay):
 * identification, certificate, response, subscriptions, media remote, start session. The "iPhone" here is a second [Iap2Link].
 */
class Iap2ControlTest {
    private val certificate = ByteArray(607) { (it * 7).toByte() }
    private val signature = ByteArray(64) { (0x5A + it).toByte() }

    private class FakeSigner(val certificate: ByteArray, val signature: ByteArray, val failSigning: Boolean = false) : CarPlaySigner {
        val challenges = ArrayList<ByteArray>()
        override fun protocolMajor() = 3
        override fun certificate() = certificate
        override fun sign(challenge: ByteArray): ByteArray {
            challenges += challenge
            if (failSigning) throw SignerException("chip did not answer")
            return signature
        }
    }

    private val config = Iap2Identification.Config("Open Headunit", "OHU-1", "Open Headunit", "0000001", "1.0.0", "1.0", 3)
    private val start = Iap2Messages.StartSession("fe80::2", 7000, "02:00:00:00:00:02", "ab".repeat(32), "950.7.1")

    private fun control(signer: CarPlaySigner = FakeSigner(certificate, signature)) = Iap2Control(signer, config, start)

    private fun ids(actions: List<Iap2Control.Action>) = actions.filterIsInstance<Iap2Control.Action.Send>().map { it.frame.messageId }
    private fun fails(actions: List<Iap2Control.Action>) = actions.filterIsInstance<Iap2Control.Action.Fail>().map { it.reason }
    private fun logs(actions: List<Iap2Control.Action>) = actions.filterIsInstance<Iap2Control.Action.Log>().map { it.line }

    private fun frame(id: Int, block: Iap2Body.() -> Unit = {}) = Iap2Csm.build(id, block)

    private fun authenticating(c: Iap2Control): Iap2Control {
        c.start(0)
        c.onFrame(frame(0x1D00), 0)
        c.onFrame(frame(0x1D02), 0)
        return c
    }

    private fun running(c: Iap2Control = control()): Iap2Control {
        authenticating(c)
        c.onFrame(frame(0xAA05), 0)
        return c
    }

    // The scripted phone and the real link layer.

    private class Rig(val control: Iap2Control, val phoneScript: (Iap2Frame) -> List<Iap2Frame>) {
        val accessory = Iap2Link()
        val phone = Iap2Link()
        val phoneFramer = Iap2Csm.Framer()
        val accessoryFramer = Iap2Csm.Framer()
        val sent = ArrayList<Iap2Frame>()
        val logs = ArrayList<String>()
        val failures = ArrayList<String>()
        var ready = false
        var now = 0L
        private var up = false

        init {
            accessory.start(true, 0)
            phone.start(false, 0)
        }

        fun run(untilMs: Long) {
            while (now < untilMs) {
                now += 10
                phone.feed(accessory.takeOutput(), now)
                accessory.feed(phone.takeOutput(), now)
                accessory.advanceTime(now)
                phone.advanceTime(now)
                if (!up && accessory.state == Iap2Link.State.Normal && phone.state == Iap2Link.State.Normal) {
                    up = true
                    apply(control.start(now))
                    phone.send(Iap2Link.CONTROL_SESSION_ID, Iap2Csm.build(0x1D00).encode(), now)
                }
                while (true) {
                    val ev = accessory.pollEvent() ?: break
                    if (ev is Iap2Link.Event.Control) for (f in accessoryFramer.offer(ev.bytes)) apply(control.onFrame(f, now))
                }
                while (true) {
                    val ev = phone.pollEvent() ?: break
                    if (ev is Iap2Link.Event.Control) {
                        for (f in phoneFramer.offer(ev.bytes)) for (reply in phoneScript(f)) phone.send(Iap2Link.CONTROL_SESSION_ID, reply.encode(), now)
                    }
                }
                apply(control.tick(now))
            }
        }

        fun apply(actions: List<Iap2Control.Action>) {
            for (a in actions) when (a) {
                is Iap2Control.Action.Send -> {
                    sent += a.frame
                    accessory.send(Iap2Link.CONTROL_SESSION_ID, a.frame.encode(), now)
                }
                is Iap2Control.Action.Log -> logs += a.line
                is Iap2Control.Action.Fail -> failures += a.reason
                Iap2Control.Action.Ready -> ready = true
                else -> Unit
            }
        }
    }

    private fun availability() = Iap2Csm.build(0x4300) { group(0) { u8(0, 1); string(1, "usb-1") }; group(1) { u8(0, 0) } }

    private fun happyScript(extraAvailabilities: Int = 1): (Iap2Frame) -> List<Iap2Frame> = { f ->
        when (f.messageId) {
            0x1D01 -> listOf(frame(0x1D02), frame(0xAA00))
            0xAA01 -> listOf(frame(0xAA02) { bytes(0, ByteArray(32) { it.toByte() }) })
            0xAA03 -> listOf(frame(0xAA05))
            0x6800 -> List(extraAvailabilities) { availability() }
            else -> emptyList()
        }
    }

    @Test fun theWholeStartRunsOverTheRealLinkInTheRecordedOrder() {
        val signer = FakeSigner(certificate, signature)
        val rig = Rig(control(signer), happyScript())
        rig.run(3_000)
        assertEquals(
            listOf(0x1D01, 0xAA01, 0xAA03, 0xAE03, 0x5000, 0x5200, 0xAE00, 0x4157, 0x4154, 0x6800, 0x4301),
            rig.sent.map { it.messageId },
        )
        assertTrue(rig.ready)
        assertEquals(emptyList<String>(), rig.failures)
        assertEquals(1, signer.challenges.size)
        assertArrayEquals(ByteArray(32) { it.toByte() }, signer.challenges[0])
        assertArrayEquals(certificate, rig.sent[1].first(0))
        assertArrayEquals(signature, rig.sent[2].first(0))
        assertEquals(
            listOf("CarPlayNative: iAP2 identification accepted", "CarPlayNative: authentication succeeded"),
            rig.logs.take(2),
        )
        assertTrue(rig.logs.contains("CarPlayNative: start session sent (#1)"))
    }

    @Test fun aSecondAvailabilitySendsASecondStartSessionCountedInTheSameNumber() {
        val rig = Rig(control(), happyScript(extraAvailabilities = 2))
        rig.run(3_000)
        assertEquals(listOf(0x4301, 0x4301), rig.sent.map { it.messageId }.filter { it == 0x4301 })
        assertTrue(rig.logs.contains("CarPlayNative: start session sent (#1)"))
        assertTrue(rig.logs.contains("CarPlayNative: start session sent (#2)"))
        assertEquals(2, rig.control.startsSent)
    }

    @Test fun theIphoneRejectingTheSignatureEndsTheSessionWithTheNamedLine() {
        val rig = Rig(control()) { f ->
            when (f.messageId) {
                0x1D01 -> listOf(frame(0x1D02), frame(0xAA00))
                0xAA01 -> listOf(frame(0xAA02) { bytes(0, ByteArray(32)) })
                0xAA03 -> listOf(frame(0xAA04))
                else -> emptyList()
            }
        }
        rig.run(3_000)
        assertEquals(listOf("CarPlayNative: the iPhone rejected the MFi signature"), rig.failures)
        assertEquals(listOf(0x1D01, 0xAA01, 0xAA03), rig.sent.map { it.messageId })
        assertFalse(rig.ready)
        assertTrue(rig.control.failed)
    }

    // The reducer on its own.

    @Test fun nothingHappensBeforeStart() {
        val c = control()
        assertEquals(emptyList<Iap2Control.Action>(), c.onFrame(frame(0x1D00), 0))
        assertEquals(emptyList<Iap2Control.Action>(), c.tick(1_000_000))
        assertEquals(emptyList<Iap2Control.Action>(), c.resendStart())
    }

    @Test fun aChallengeOutsideOneTo128BytesIsRefusedBeforeTheSignerRuns() {
        for (size in listOf(0, 129, 4000)) {
            val signer = FakeSigner(certificate, signature)
            val c = authenticating(control(signer))
            val out = c.onFrame(frame(0xAA02) { bytes(0, ByteArray(size)) }, 0)
            assertEquals("size $size", 1, fails(out).size)
            assertTrue(fails(out)[0], fails(out)[0].startsWith("CarPlayNative: the MFi challenge has $size bytes"))
            assertEquals("size $size", 0, signer.challenges.size)
        }
    }

    @Test fun challengesOfOneAndOf128BytesReachTheSigner() {
        for (size in listOf(1, 128)) {
            val signer = FakeSigner(certificate, signature)
            val c = authenticating(control(signer))
            val challenge = ByteArray(size) { (it + 1).toByte() }
            val out = c.onFrame(frame(0xAA02) { bytes(0, challenge) }, 0)
            assertEquals(listOf(0xAA03), ids(out))
            assertEquals(1, signer.challenges.size)
            assertArrayEquals(challenge, signer.challenges[0])
        }
    }

    @Test fun aChallengeWithNoDataIsRefused() {
        val c = authenticating(control())
        assertEquals(listOf("CarPlayNative: the iPhone sent an MFi challenge with no data"), fails(c.onFrame(frame(0xAA02), 0)))
    }

    @Test fun aSignerThatFailsEndsTheSessionWithoutItsDetailsInAFrame() {
        val c = authenticating(control(FakeSigner(certificate, signature, failSigning = true)))
        val out = c.onFrame(frame(0xAA02) { bytes(0, ByteArray(32)) }, 0)
        assertEquals(listOf("CarPlayNative: the MFi signer failed (chip did not answer)"), fails(out))
        assertEquals(emptyList<Int>(), ids(out))
    }

    @Test fun aCertificateOrSignatureTooLongForAParameterEndsTheSession() {
        val longCert = FakeSigner(ByteArray(70_000), signature)
        assertEquals(1, fails(authenticating(control(longCert)).onFrame(frame(0xAA00), 0)).size)
        val longSig = FakeSigner(certificate, ByteArray(70_000))
        assertEquals(1, fails(authenticating(control(longSig)).onFrame(frame(0xAA02) { bytes(0, ByteArray(32)) }, 0)).size)
    }

    @Test fun noLogLineHoldsTheCertificateTheSignatureOrTheDeviceKey() {
        val rig = Rig(control(), happyScript())
        rig.run(3_000)
        val all = rig.logs.joinToString("\n") + rig.failures.joinToString("\n")
        assertTrue(rig.logs.isNotEmpty())
        for (secret in listOf(certificate.toHex(), signature.toHex(), "ab".repeat(32))) assertFalse(all.contains(secret))
        assertFalse(all.contains(certificate.copyOf(8).toHex()))
        assertFalse(all.contains(signature.copyOf(8).toHex()))
    }

    @Test fun identificationOutOfOrderMessagesFailAndAuthenticationMessagesBeforeAcceptanceToo() {
        val c = control()
        c.start(0)
        assertEquals(1, fails(c.onFrame(frame(0xAA00), 0)).size)
        val d = control()
        d.start(0)
        d.onFrame(frame(0x1D02), 0)
        assertEquals(listOf("CarPlayNative: unexpected iAP2 message 0x4300 during authentication"), fails(d.onFrame(frame(0x4300), 0)))
    }

    @Test fun aCertificateRequestAnswersWithTheSignersCertificate() {
        val c = authenticating(control())
        val out = c.onFrame(frame(0xAA00), 0)
        assertEquals(listOf(0xAA01), ids(out))
        assertArrayEquals(certificate, (out[0] as Iap2Control.Action.Send).frame.first(0))
    }

    @Test fun identificationWaitsTenSecondsFromStartAndRepeatsDoNotMoveIt() {
        val c = control()
        c.start(1_000)
        assertEquals(emptyList<Iap2Control.Action>(), c.tick(10_999))
        c.onFrame(frame(0x1D00), 5_000)
        c.onFrame(frame(0x1D00), 10_500)
        assertEquals(emptyList<Iap2Control.Action>(), c.tick(10_999))
        assertEquals(listOf("CarPlayNative: iAP2 identification timed out"), fails(c.tick(11_000)))
        assertEquals(emptyList<Iap2Control.Action>(), c.tick(99_000))
    }

    @Test fun authenticationWaitsThirtySecondsFromAcceptance() {
        val c = control()
        c.start(0)
        c.onFrame(frame(0x1D02), 4_000)
        c.onFrame(frame(0xAA00), 20_000)
        assertEquals(emptyList<Iap2Control.Action>(), c.tick(33_999))
        assertEquals(listOf("CarPlayNative: iAP2 authentication timed out"), fails(c.tick(34_000)))
    }

    @Test fun afterAuthenticationNoTimerRuns() {
        val c = running()
        assertEquals(emptyList<Iap2Control.Action>(), c.tick(10_000_000))
    }

    @Test fun successSendsPowerSubscriptionsAndTheRemoteThenReady() {
        val c = authenticating(control())
        val out = c.onFrame(frame(0xAA05), 0)
        assertEquals(listOf(0xAE03, 0x5000, 0x5200, 0xAE00, 0x4157, 0x4154, 0x6800), ids(out))
        assertEquals(Iap2Control.Action.Ready, out.last())
        assertEquals(Iap2Messages.powerSourceUpdate(Iap2Control.AVAILABLE_CURRENT_MA).encode().toHex(), (out[1] as Iap2Control.Action.Send).frame.encode().toHex())
    }

    @Test fun eachAvailabilityAnswersOnceUpToThreeThenIsIgnored() {
        val c = running()
        val sent = ArrayList<Int>()
        for (i in 1..5) {
            val out = c.onFrame(availability(), 0)
            sent += ids(out)
            if (i > 3) assertTrue(logs(out).contains("CarPlayNative: start session not sent again: 3 answers already"))
        }
        assertEquals(listOf(0x4301, 0x4301, 0x4301), sent)
        assertEquals(3, c.startsSent)
    }

    @Test fun theStartSessionIsTheSameBytesOnTheReplyAndOnTheResend() {
        val c = running()
        val reply = c.onFrame(availability(), 0)
        val again = c.resendStart()
        val a = (reply.first { it is Iap2Control.Action.Send } as Iap2Control.Action.Send).frame.encode()
        val b = (again.first { it is Iap2Control.Action.Send } as Iap2Control.Action.Send).frame.encode()
        assertArrayEquals(a, b)
        assertArrayEquals(start.frame().encode(), a)
        assertEquals(listOf(1, 2), (reply + again).filterIsInstance<Iap2Control.Action.StartSent>().map { it.count })
        assertTrue(logs(again).contains("CarPlayNative: start session sent (#2)"))
    }

    @Test fun aResendDoesNotUseAReplySlot() {
        val c = running()
        c.resendStart()
        c.resendStart()
        val sent = (1..4).flatMap { ids(c.onFrame(availability(), 0)) }
        assertEquals(3, sent.size)
        assertEquals(5, c.startsSent)
    }

    @Test fun anAvailabilityThatSaysNotAvailableIsStillAnswered() {
        val c = running()
        val out = c.onFrame(Iap2Csm.build(0x4300) { group(0) { u8(0, 0) } }, 0)
        assertEquals(listOf(0x4301), ids(out))
        assertTrue(logs(out)[0].endsWith("wired false"))
    }

    @Test fun aGarbledAvailabilityIsStillAnswered() {
        val c = running()
        val out = c.onFrame(Iap2Frame(0x4300, byteArrayOf(0, 9, 0)), 0)
        assertEquals(listOf(0x4301), ids(out))
        assertTrue(logs(out)[0].endsWith("wired not stated"))
    }

    @Test fun otherMessagesAreForwardedAndAnswerNothing() {
        val c = running()
        for (id in listOf(0x5001, 0xAE01, 0x4158, 0x4155, 0x4302, 0x4301, 0x1D00)) {
            val out = c.onFrame(frame(id), 0)
            assertEquals(1, out.size)
            assertEquals(id, (out[0] as Iap2Control.Action.Incoming).frame.messageId)
        }
        assertEquals(0, c.startsSent)
    }

    @Test fun anIdentificationRequestAfterARetryKeepsTheCorrection() {
        val c = control()
        c.start(0)
        c.onFrame(frame(0x1D00), 0)
        val out = c.onFrame(frame(0x1D03) { bytes(6, byteArrayOf(0x43, 0x01)); bytes(7, byteArrayOf(0x43, 0x00)) }, 0)
        assertEquals(listOf(0x1D01), ids(out))
        val again = (c.onFrame(frame(0x1D00), 0)[0] as Iap2Control.Action.Send).frame
        assertFalse(0x4301 in Iap2Csm.readU16List(again.first(6)!!))
        assertFalse(0x4300 in Iap2Csm.readU16List(again.first(7)!!))
    }

    @Test fun aRejectionOfOnlyMessageIdsIsRetriedOnceWithoutThem() {
        val c = control()
        c.start(0)
        val first = c.onFrame(frame(0x1D00), 0)
        assertTrue(0x4301 in Iap2Csm.readU16List((first[0] as Iap2Control.Action.Send).frame.first(6)!!))
        val rejection = frame(0x1D03) { bytes(6, byteArrayOf(0x43, 0x01)); bytes(7, byteArrayOf(0x43, 0x00)) }
        val retry = c.onFrame(rejection, 0)
        assertEquals(listOf("CarPlayNative: iAP2 identification again without 0x4301,0x4300"), logs(retry))
        val sent = (retry.first { it is Iap2Control.Action.Send } as Iap2Control.Action.Send).frame
        assertFalse(0x4301 in Iap2Csm.readU16List(sent.first(6)!!))
        assertFalse(0x4300 in Iap2Csm.readU16List(sent.first(7)!!))
        assertEquals(1, fails(c.onFrame(rejection, 0)).size)
    }

    @Test fun aRejectionThatFlagsAnotherParameterOrAnUnknownIdFailsAtOnce() {
        val flagged = frame(0x1D03) { bytes(6, byteArrayOf(0x43, 0x01)); void(16) }
        val c = control()
        c.start(0)
        assertEquals(1, fails(c.onFrame(flagged, 0)).size)
        val unknown = frame(0x1D03) { bytes(6, byteArrayOf(0x41, 0x5A)) }
        val d = control()
        d.start(0)
        assertEquals(1, fails(d.onFrame(unknown, 0)).size)
        val empty = frame(0x1D03) { bytes(6, ByteArray(0)) }
        val e = control()
        e.start(0)
        assertEquals(1, fails(e.onFrame(empty, 0)).size)
    }

    @Test fun stopSendsTheMediaRemoteStopOnceAndOnlyAfterItStarted() {
        val early = control()
        early.start(0)
        assertEquals(emptyList<Iap2Control.Action>(), early.stop())
        val c = running()
        assertEquals(listOf(0x6803), ids(c.stop()))
        assertEquals(emptyList<Iap2Control.Action>(), c.stop())
    }

    @Test fun aFailedControlIgnoresLaterFramesAndTicks() {
        val c = authenticating(control())
        c.onFrame(frame(0xAA04), 0)
        assertEquals(emptyList<Iap2Control.Action>(), c.onFrame(frame(0xAA05), 0))
        assertEquals(emptyList<Iap2Control.Action>(), c.tick(1_000_000))
    }
}
