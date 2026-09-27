package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfpServiceRecordPolicyTest {

    private val handsFree = HfpServiceRecordPolicy.HANDS_FREE_UUID
    private val audioGateway = "0000111f-0000-1000-8000-00805f9b34fb"
    private val a2dpSink = "0000110b-0000-1000-8000-00805f9b34fb"

    @Test
    fun `a device already advertising hands-free does not get a second record`() {
        assertFalse(HfpServiceRecordPolicy.shouldRegisterDummyHfp(listOf(a2dpSink, handsFree)))
    }

    @Test
    fun `the comparison ignores case`() {
        assertFalse(HfpServiceRecordPolicy.shouldRegisterDummyHfp(listOf(handsFree.uppercase())))
    }

    @Test
    fun `a phone advertising audio gateway still gets the record`() {
        // A phone standing in for a head unit carries the other half of HFP, so it needs ours.
        assertTrue(HfpServiceRecordPolicy.shouldRegisterDummyHfp(listOf(audioGateway, a2dpSink)))
    }

    @Test
    fun `an adapter with nothing advertised gets the record`() {
        assertTrue(HfpServiceRecordPolicy.shouldRegisterDummyHfp(emptyList()))
    }

    @Test
    fun `an adapter that could not be asked gets the record`() {
        // Reading the local UUIDs is not public API; a refusal is not an answer of yes.
        assertTrue(HfpServiceRecordPolicy.shouldRegisterDummyHfp(null))
    }

    private fun opens(
        enabled: Boolean = true,
        publishedStandIn: Boolean = true,
        link: BluetoothWakePolicy.HandsFreeLink = BluetoothWakePolicy.HandsFreeLink.ABSENT,
    ) = HfpServiceRecordPolicy.shouldOpenServiceLevelConnection(enabled, publishedStandIn, link)

    @Test
    fun `a live hands-free link keeps the stand-in from speaking first`() {
        assertFalse(opens(link = BluetoothWakePolicy.HandsFreeLink.CONNECTED))
    }

    @Test
    fun `no hands-free link lets the stand-in open the exchange`() {
        assertTrue(opens(link = BluetoothWakePolicy.HandsFreeLink.ABSENT))
    }

    @Test
    fun `an adapter that would not say still opens the exchange`() {
        // Same rule as the record above: a question that could not be asked is not answered yes.
        assertTrue(opens(link = BluetoothWakePolicy.HandsFreeLink.UNREADABLE))
    }

    @Test
    fun `the setting off stops the stand-in speaking first`() {
        assertFalse(opens(enabled = false))
    }

    @Test
    fun `a radio that publishes no stand-in record never speaks first`() {
        assertFalse(opens(publishedStandIn = false))
    }

    @Test
    fun `only a readable live link stands the stand-in down, as with the poke`() {
        // The two predicates answer UNREADABLE the same way and for the same stated reason.
        // Collapsing either one alone would silently disable a mechanism on a radio that will not
        // report its profiles.
        for (link in BluetoothWakePolicy.HandsFreeLink.entries) {
            assertEquals(
                BluetoothWakePolicy.wakeDecision(
                    clientRoleLink = link,
                    gatewayRoleLink = BluetoothWakePolicy.HandsFreeLink.ABSENT,
                    targetLink = BluetoothWakePolicy.TargetLink.UNREADABLE
                ).poke,
                opens(link = link)
            )
        }
    }

    @Test
    fun `a walk that established is not a refusal`() {
        assertNull(
            HfpServiceRecordPolicy.standInRefusalReason(true, HfpSlcInitiator.Stage.ESTABLISHED)
        )
    }

    @Test
    fun `a hold that never spoke reports nothing`() {
        // The answering half is not a refusal: nothing was asked of the phone.
        for (stage in HfpSlcInitiator.Stage.entries) {
            assertNull(stage.name, HfpServiceRecordPolicy.standInRefusalReason(false, stage))
        }
    }

    @Test
    fun `a phone that answered nothing is named as the case it is`() {
        // The outcome a phone already giving another device its hands-free link produces, and the
        // one that had no line at all: only the established branch was ever logged.
        val why = HfpServiceRecordPolicy.standInRefusalReason(true, HfpSlcInitiator.Stage.BRSF)
        assertNotNull(why)
        assertTrue(why!!, why.contains("answered nothing"))
    }

    @Test
    fun `a walk that stalled partway is a different report`() {
        for (stage in listOf(
            HfpSlcInitiator.Stage.CIND_TEST,
            HfpSlcInitiator.Stage.CIND_READ,
            HfpSlcInitiator.Stage.CMER,
        )) {
            val why = HfpServiceRecordPolicy.standInRefusalReason(true, stage)
            assertNotNull(stage.name, why)
            assertTrue(why!!, why.contains("stopped answering"))
        }
    }

    @Test
    fun `a refused record is asked for again until the ceiling`() {
        for (attempt in 1 until HfpServiceRecordPolicy.REGISTRATION_ATTEMPTS) {
            assertTrue("attempt $attempt", HfpServiceRecordPolicy.retriesRegistration(attempt))
        }
        assertFalse(HfpServiceRecordPolicy.retriesRegistration(HfpServiceRecordPolicy.REGISTRATION_ATTEMPTS))
    }

    @Test
    fun `retrying and reporting are exact complements`() {
        // The user is told exactly when the app has stopped asking, never before and never never.
        for (attempt in 0..HfpServiceRecordPolicy.REGISTRATION_ATTEMPTS + 2) {
            assertEquals(
                "attempt $attempt",
                !HfpServiceRecordPolicy.retriesRegistration(attempt),
                HfpServiceRecordPolicy.registrationRefused(attempt),
            )
        }
    }

    @Test
    fun `the retry gap leaves the Android Auto record time to settle`() {
        // The refusal this exists for lands 20 ms after that record registered on the same stack.
        assertTrue(HfpServiceRecordPolicy.REGISTRATION_RETRY_GAP_MS > 20L)
    }
}
