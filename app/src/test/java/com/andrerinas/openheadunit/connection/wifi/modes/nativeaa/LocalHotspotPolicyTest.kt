package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.aap.NarrowBandProfilePolicy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.direct.P2pChannelClosePolicy
import com.andrerinas.openheadunit.connection.wifi.direct.P2pTeardown
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.CloseReason
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.Gate
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.HostEvent
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.InterfaceStep
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.LocalBand
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.RefreshAction
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.RestartCause
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.ReservationVerdict
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.Source
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.StartDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHotspotPolicyTest {

    // Source choice

    @Test
    fun `the source is local from API 26 unless the user asks for the system hotspot`() {
        assertEquals(Source.LOCAL_ONLY, LocalHotspotPolicy.source(26, false))
        assertEquals(Source.SYSTEM, LocalHotspotPolicy.source(26, true))
        assertEquals(Source.SYSTEM, LocalHotspotPolicy.source(25, false))
        assertEquals(Source.SYSTEM, LocalHotspotPolicy.source(16, false))
        assertEquals(Source.LOCAL_ONLY, LocalHotspotPolicy.source(36, false))
    }

    @Test
    fun `the row shows only for API 26 and later, Native, Hotspot`() {
        assertTrue(LocalHotspotPolicy.showsUseSystemRow(26, WifiLauncherMode.NATIVE, NativeStrategy.HOTSPOT))
        assertFalse(LocalHotspotPolicy.showsUseSystemRow(25, WifiLauncherMode.NATIVE, NativeStrategy.HOTSPOT))
        assertFalse(LocalHotspotPolicy.showsUseSystemRow(34, WifiLauncherMode.NATIVE, NativeStrategy.WIFI_DIRECT))
        assertFalse(LocalHotspotPolicy.showsUseSystemRow(34, WifiLauncherMode.HELPER, NativeStrategy.HOTSPOT))
        assertFalse(LocalHotspotPolicy.showsUseSystemRow(34, WifiLauncherMode.AUTO, NativeStrategy.HOTSPOT))
    }

    @Test
    fun `the local source hides seven Native rows and the system source none`() {
        assertEquals(
            setOf(
                "autoEnableHotspot", "hotspotBand", "fiveGhzChannel", "hotspotSsidOverride",
                "hotspotPasswordOverride", "hotspotInterfaceOverride", "projectionSetupQr",
            ),
            LocalHotspotPolicy.hiddenNativeRows(Source.LOCAL_ONLY),
        )
        assertTrue(LocalHotspotPolicy.hiddenNativeRows(Source.SYSTEM).isEmpty())
    }

    @Test
    fun `the Auto-Enable toggle hides only in Native on the local source`() {
        assertTrue(LocalHotspotPolicy.hidesAutoEnableToggle(WifiLauncherMode.NATIVE, Source.LOCAL_ONLY))
        assertFalse(LocalHotspotPolicy.hidesAutoEnableToggle(WifiLauncherMode.NATIVE, Source.SYSTEM))
        for (source in Source.entries) {
            assertFalse(LocalHotspotPolicy.hidesAutoEnableToggle(WifiLauncherMode.HELPER, source))
            assertFalse(LocalHotspotPolicy.hidesAutoEnableToggle(WifiLauncherMode.AUTO, source))
        }
    }

    @Test
    fun `a flipped setting restarts the Hotspot mode and leaves Wi-Fi Direct alone`() {
        val hotspot = NativeStrategy.HOTSPOT
        val direct = NativeStrategy.WIFI_DIRECT
        assertFalse(LocalHotspotPolicy.sameStartConfiguration(hotspot, Source.LOCAL_ONLY, hotspot, Source.SYSTEM))
        assertTrue(LocalHotspotPolicy.sameStartConfiguration(hotspot, Source.SYSTEM, hotspot, Source.SYSTEM))
        assertTrue(LocalHotspotPolicy.sameStartConfiguration(direct, Source.LOCAL_ONLY, direct, Source.SYSTEM))
        assertFalse(LocalHotspotPolicy.sameStartConfiguration(direct, Source.SYSTEM, hotspot, Source.SYSTEM))
    }

    @Test
    fun `identity is not assessed on the local source`() {
        assertFalse(LocalHotspotPolicy.assessesIdentity(Source.LOCAL_ONLY))
        assertTrue(LocalHotspotPolicy.assessesIdentity(Source.SYSTEM))
    }

    // Reservation

    @Test
    fun `a blank name or passphrase is not joinable`() {
        assertEquals(ReservationVerdict.NotJoinable, LocalHotspotPolicy.judgeReservation("", "pass1234", 1))
        assertEquals(ReservationVerdict.NotJoinable, LocalHotspotPolicy.judgeReservation("AndroidShare_1", " ", 1))
        assertEquals(ReservationVerdict.NotJoinable, LocalHotspotPolicy.judgeReservation(null, "pass1234", 1))
        assertEquals(ReservationVerdict.NotJoinable, LocalHotspotPolicy.judgeReservation("AndroidShare_1", null, 2))
    }

    @Test
    fun `only the WPA2 types are joinable`() {
        assertEquals(ReservationVerdict.Joinable, LocalHotspotPolicy.judgeReservation("AndroidShare_1", "pass1234", 1))
        assertEquals(ReservationVerdict.Joinable, LocalHotspotPolicy.judgeReservation("AndroidShare_1", "pass1234", 2))
        assertEquals(ReservationVerdict.UnsupportedSecurity(0), LocalHotspotPolicy.judgeReservation("AndroidShare_1", "pass1234", 0))
        assertEquals(ReservationVerdict.UnsupportedSecurity(3), LocalHotspotPolicy.judgeReservation("AndroidShare_1", "pass1234", 3))
    }

    @Test
    fun `a quoted name loses its quotes and a bare one is kept`() {
        assertEquals("AndroidShare_1", LocalHotspotPolicy.cleanSsid("\"AndroidShare_1\""))
        assertEquals("AndroidShare_1", LocalHotspotPolicy.cleanSsid("AndroidShare_1"))
        assertEquals("\"", LocalHotspotPolicy.cleanSsid("\""))
        assertNull(LocalHotspotPolicy.cleanSsid(null))
    }

    @Test
    fun `the legacy key management bits map to a security type`() {
        assertEquals(1, LocalHotspotPolicy.legacySecurityType(wpa2Psk = true, open = false))
        assertEquals(0, LocalHotspotPolicy.legacySecurityType(wpa2Psk = false, open = true))
        assertEquals(3, LocalHotspotPolicy.legacySecurityType(wpa2Psk = false, open = false))
    }

    // BSSID and interface

    @Test
    fun `the reservation address wins when it is usable`() {
        assertEquals("00:27:15:43:6a:66", LocalHotspotPolicy.chooseBssid("00:27:15:43:6a:66", listOf("aa:bb:cc:dd:ee:01")))
    }

    @Test
    fun `a null reservation address falls to the first interface rung`() {
        assertEquals("aa:bb:cc:dd:ee:01", LocalHotspotPolicy.chooseBssid(null, listOf(null, "aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02")))
    }

    @Test
    fun `masked addresses are skipped and no usable rung gives null`() {
        assertEquals(
            "aa:bb:cc:dd:ee:01",
            LocalHotspotPolicy.chooseBssid("02:00:00:00:00:00", listOf("00:00:00:00:00:00", "aa:bb:cc:dd:ee:01")),
        )
        assertNull(LocalHotspotPolicy.chooseBssid(null, listOf(null, "", "00:00:00:00:00:00")))
        assertNull(LocalHotspotPolicy.chooseBssid(null, emptyList()))
    }

    private fun iface(name: String, ip: String? = "192.168.254.140", up: Boolean = true, loopback: Boolean = false) =
        ApInterfaceCandidate(name, loopback, up, ip)

    @Test
    fun `a new interface name is picked`() {
        val before = setOf("wlan0" to "192.168.1.20")
        val now = listOf(iface("wlan0", "192.168.1.20"), iface("wlan2"))
        assertEquals("wlan2", LocalHotspotPolicy.newInterface(before, now)?.name)
    }

    @Test
    fun `a reused name with a new address is picked`() {
        val before = setOf("wlan2" to "192.168.43.1")
        assertEquals("wlan2", LocalHotspotPolicy.newInterface(before, listOf(iface("wlan2", "192.168.254.140")))?.name)
    }

    @Test
    fun `an unchanged pair is not picked`() {
        val before = setOf("wlan2" to "192.168.254.140")
        assertNull(LocalHotspotPolicy.newInterface(before, listOf(iface("wlan2", "192.168.254.140"))))
    }

    @Test
    fun `a p2p interface is not picked`() {
        assertNull(LocalHotspotPolicy.newInterface(emptySet(), listOf(iface("p2p-wlan0-0"), iface("p2p0"))))
    }

    @Test
    fun `the station address is not picked`() {
        assertNull(LocalHotspotPolicy.newInterface(emptySet(), listOf(iface("wlan0", "192.168.1.20")), stationIpv4 = "192.168.1.20"))
    }

    @Test
    fun `nothing new gives null`() {
        assertNull(LocalHotspotPolicy.newInterface(emptySet(), emptyList()))
        assertNull(LocalHotspotPolicy.newInterface(emptySet(), listOf(iface("lo", loopback = true))))
    }

    @Test
    fun `a new access point that waits for its first station counts, behind one that is up`() {
        val waiting = iface("wlan2", "192.168.43.1", up = false)
        assertEquals("wlan2", LocalHotspotPolicy.newInterface(emptySet(), listOf(waiting))?.name)
        assertNull(LocalHotspotPolicy.newInterface(setOf("wlan2" to "192.168.43.1"), listOf(waiting)))
        val up = iface("wlan3", "192.168.50.1")
        assertEquals("wlan3", LocalHotspotPolicy.newInterface(emptySet(), listOf(waiting, up))?.name)
    }

    @Test
    fun `among several new interfaces the network policy picks`() {
        val now = listOf(iface("rmnet_data0", "10.1.1.1"), iface("wlan2", "192.168.254.140"))
        assertEquals("wlan2", LocalHotspotPolicy.newInterface(emptySet(), now)?.name)
    }

    private fun step(interfaceFound: Boolean, bssidFound: Boolean, nowMs: Long) =
        LocalHotspotPolicy.interfaceStep(interfaceFound, bssidFound, nowMs, deadlineMs = 5_000L)

    @Test
    fun `an interface with no address yet is polled until the deadline, then named`() {
        assertEquals(InterfaceStep.POLL, step(interfaceFound = true, bssidFound = false, nowMs = 0L))
        assertEquals(InterfaceStep.POLL, step(interfaceFound = true, bssidFound = false, nowMs = 4_999L))
        assertEquals(InterfaceStep.NO_ADDRESS, step(interfaceFound = true, bssidFound = false, nowMs = 5_000L))
        assertEquals(InterfaceStep.POLL, step(interfaceFound = false, bssidFound = false, nowMs = 4_999L))
        assertEquals(InterfaceStep.NO_INTERFACE, step(interfaceFound = false, bssidFound = false, nowMs = 5_000L))
    }

    @Test
    fun `an interface with an address publishes at once, and at the deadline`() {
        assertEquals(InterfaceStep.PUBLISH, step(interfaceFound = true, bssidFound = true, nowMs = 0L))
        assertEquals(InterfaceStep.PUBLISH, step(interfaceFound = true, bssidFound = true, nowMs = 5_000L))
        assertEquals(InterfaceStep.POLL, step(interfaceFound = false, bssidFound = true, nowMs = 0L))
    }

    // Band

    @Test
    fun `a mask with 2_4 GHz or nothing read caps the video, 5 GHz and higher alone does not`() {
        assertEquals(LocalBand.BAND_24, LocalHotspotPolicy.bandFromMask(1))
        assertEquals(LocalBand.BAND_24, LocalHotspotPolicy.bandFromMask(1 or 2))
        assertEquals(LocalBand.ABOVE_24, LocalHotspotPolicy.bandFromMask(2))
        assertEquals(LocalBand.ABOVE_24, LocalHotspotPolicy.bandFromMask(2 or 4))
        assertEquals(LocalBand.UNKNOWN, LocalHotspotPolicy.bandFromMask(0))
        assertEquals(LocalBand.UNKNOWN, LocalHotspotPolicy.bandFromMask(-1))
        assertTrue(LocalHotspotPolicy.capsVideo(LocalBand.BAND_24))
        assertTrue(LocalHotspotPolicy.capsVideo(LocalBand.UNKNOWN))
        assertFalse(LocalHotspotPolicy.capsVideo(LocalBand.ABOVE_24))
    }

    @Test
    fun `the cap flag reaches the video policy and no channel list is sent`() {
        val caps = LocalHotspotPolicy.capsVideo(LocalBand.BAND_24)
        assertTrue(NarrowBandProfilePolicy.runsNarrow(true, 0, bandUnreadable = caps))
        assertFalse(NarrowBandProfilePolicy.runsNarrow(true, 0, bandUnreadable = LocalHotspotPolicy.capsVideo(LocalBand.ABOVE_24)))
        assertTrue(WppChannelListPolicy.forGroup(0).isEmpty())
    }

    @Test
    fun `the channel keys of API 36 give the band mask`() {
        assertEquals(2, LocalHotspotPolicy.bandMaskFromChannelKeys(listOf(2)))
        assertEquals(3, LocalHotspotPolicy.bandMaskFromChannelKeys(listOf(1, 2)))
        assertEquals(0, LocalHotspotPolicy.bandMaskFromChannelKeys(emptyList()))
    }

    @Test
    fun `the config text of API 31 to 35 gives the mask from its Channels line`() {
        val text = "ssid = \"AndroidShare_1\" \n bssid = 02:00:00:00:00:00 \n Passphrase = <non-empty> \n Channels = {2=0} \n SecurityType = 2"
        assertEquals(2, LocalHotspotPolicy.bandMaskFromConfigText(text))
        assertEquals(1, LocalHotspotPolicy.bandMaskFromConfigText("ssid = x \n Channels = {1=0}"))
        assertEquals(3, LocalHotspotPolicy.bandMaskFromConfigText("ssid = x \n Channels = {1=6, 2=149}"))
        assertEquals(0, LocalHotspotPolicy.bandMaskFromConfigText("ssid = x \n Channels = {}"))
    }

    @Test
    fun `the config text of API 30 gives the mask from its Band line, anything else gives 0`() {
        assertEquals(2, LocalHotspotPolicy.bandMaskFromConfigText("ssid=x \n Band =2 \n Channel =0"))
        assertEquals(0, LocalHotspotPolicy.bandMaskFromConfigText("ssid=x \n Frequency band unknown"))
        assertEquals(0, LocalHotspotPolicy.bandMaskFromConfigText(null))
    }

    // Failures

    @Test
    fun `each callback reason maps to its name`() {
        assertEquals(LocalHotspotFailure.NO_CHANNEL, LocalHotspotFailure.fromReason(1, true))
        assertEquals(LocalHotspotFailure.GENERIC, LocalHotspotFailure.fromReason(2, true))
        assertEquals(LocalHotspotFailure.INCOMPATIBLE_MODE, LocalHotspotFailure.fromReason(3, true))
        assertEquals(LocalHotspotFailure.TETHERING_DISALLOWED, LocalHotspotFailure.fromReason(4, true))
        assertEquals(LocalHotspotFailure.GENERIC, LocalHotspotFailure.fromReason(99, true))
    }

    @Test
    fun `a thrown call maps to its name`() {
        assertEquals(LocalHotspotFailure.SECURITY_EXCEPTION, LocalHotspotFailure.fromThrown(SecurityException("x")))
        assertEquals(
            LocalHotspotFailure.REQUEST_PENDING,
            LocalHotspotFailure.fromThrown(IllegalStateException("Caller already has an active LocalOnlyHotspot request")),
        )
        assertEquals(LocalHotspotFailure.GENERIC, LocalHotspotFailure.fromThrown(IllegalArgumentException("x")))
    }

    @Test
    fun `Wi-Fi off wins over any reason`() {
        for (reason in 1..4) assertEquals(LocalHotspotFailure.WIFI_OFF, LocalHotspotFailure.fromReason(reason, false))
    }

    @Test
    fun `every advice names the setting`() {
        for (failure in LocalHotspotFailure.entries) for (sdk in listOf(26, 28, 34)) {
            assertTrue("$failure at $sdk", failure.describe(sdk).contains("Use the system hotspot"))
        }
    }

    @Test
    fun `the generic advice names the Wi-Fi change right from API 28 only`() {
        assertTrue(LocalHotspotFailure.GENERIC.describe(28).contains("Wi-Fi change right"))
        assertFalse(LocalHotspotFailure.GENERIC.describe(27).contains("Wi-Fi change right"))
        assertTrue(LocalHotspotFailure.GENERIC.describe(27).contains("Wi-Fi Direct group"))
    }

    @Test
    fun `the incompatible mode advice names the system hotspot and the foreground`() {
        val advice = LocalHotspotFailure.INCOMPATIBLE_MODE.describe(34)
        assertTrue(advice.contains("system hotspot"))
        assertTrue(advice.contains("foreground"))
    }

    @Test
    fun `no address is named in the advice`() {
        assertTrue(LocalHotspotFailure.NO_ADDRESS.describe(34).contains("the unit gives no hotspot address"))
    }

    // Grants

    @Test
    fun `API 33 and a target of 33 or later needs the nearby grant only`() {
        assertEquals(listOf("NEARBY_WIFI_DEVICES"), LocalHotspotPolicy.missingGrants(34, 36, false, false, false))
        assertTrue(LocalHotspotPolicy.missingGrants(34, 36, true, false, false).isEmpty())
    }

    @Test
    fun `below API 33 or with an older target it needs fine location and the switch`() {
        assertEquals(
            listOf("ACCESS_FINE_LOCATION", "location switch"),
            LocalHotspotPolicy.missingGrants(32, 36, false, false, false),
        )
        assertEquals(
            listOf("ACCESS_FINE_LOCATION", "location switch"),
            LocalHotspotPolicy.missingGrants(34, 32, true, false, false),
        )
        assertEquals(listOf("location switch"), LocalHotspotPolicy.missingGrants(32, 36, false, true, false))
        assertTrue(LocalHotspotPolicy.missingGrants(32, 36, false, true, true).isEmpty())
    }

    // After a start

    @Test
    fun `a reason 3 before a start is the incompatible mode and after a start the system hotspot`() {
        val before = LocalHotspotPolicy.afterStart(HostEvent.Failed(3), started = false)
        assertEquals(LocalHotspotFailure.INCOMPATIBLE_MODE, before.failure)
        assertTrue(before.isStartFailure)
        val after = LocalHotspotPolicy.afterStart(HostEvent.Failed(3), started = true)
        assertEquals(LocalHotspotFailure.SYSTEM_HOTSPOT_STARTED, after.failure)
        assertFalse(after.isStartFailure)
        assertTrue(after.invalidates)
    }

    @Test
    fun `a platform stop invalidates and is not a start failure`() {
        val stopped = LocalHotspotPolicy.afterStart(HostEvent.Stopped, started = true)
        assertEquals(LocalHotspotFailure.PLATFORM_STOPPED, stopped.failure)
        assertFalse(stopped.isStartFailure)
        assertTrue(stopped.invalidates)
    }

    @Test
    fun `a start failure never names the system source as next`() {
        val response = LocalHotspotPolicy.onStartFailure()
        assertTrue(response.raisesBanner)
        assertEquals(Source.LOCAL_ONLY, response.nextSource)
    }

    // Restarts

    @Test
    fun `a refresh re-reads a live reservation and waits on a start in flight`() {
        assertEquals(RefreshAction.REREAD, LocalHotspotPolicy.onRefresh(true, false, emptyList(), 0L))
        assertEquals(RefreshAction.REREAD, LocalHotspotPolicy.onRefresh(true, true, listOf(0L, 1L, 2L), 3L))
        assertEquals(RefreshAction.WAIT, LocalHotspotPolicy.onRefresh(false, true, emptyList(), 0L))
    }

    @Test
    fun `a refresh with no reservation restarts up to three times in 60 s`() {
        val now = 100_000L
        assertEquals(RefreshAction.RESTART, LocalHotspotPolicy.onRefresh(false, false, emptyList(), now))
        assertEquals(RefreshAction.RESTART, LocalHotspotPolicy.onRefresh(false, false, listOf(now - 10, now - 20), now))
        assertEquals(RefreshAction.GIVE_UP, LocalHotspotPolicy.onRefresh(false, false, listOf(now - 10, now - 20, now - 30), now))
        assertEquals(
            RefreshAction.RESTART,
            LocalHotspotPolicy.onRefresh(false, false, listOf(now - 61_000, now - 20, now - 30), now),
        )
    }

    @Test
    fun `a first start in a process has no restart cause`() {
        assertEquals(StartDecision.Go(null, 1), LocalHotspotPolicy.startDecision(emptyList(), null, 1_000L))
    }

    @Test
    fun `a start after a close names the close and counts the attempt`() {
        assertEquals(
            StartDecision.Go(RestartCause.CLOSE, 2),
            LocalHotspotPolicy.startDecision(listOf(500L), RestartCause.CLOSE, 1_000L),
        )
    }

    @Test
    fun `a fourth start inside 60 s gives up and an old stamp does not count`() {
        val now = 100_000L
        assertEquals(StartDecision.GiveUp, LocalHotspotPolicy.startDecision(listOf(now - 1, now - 2, now - 3), RestartCause.CLOSE, now))
        assertEquals(
            StartDecision.Go(RestartCause.CLOSE, 3),
            LocalHotspotPolicy.startDecision(listOf(now - 61_000, now - 2, now - 3), RestartCause.CLOSE, now),
        )
    }

    @Test
    fun `a fresh start and a refresh restart share one ledger`() {
        val now = 100_000L
        val stamps = listOf(now - 3, now - 2, now - 1)
        assertEquals(StartDecision.GiveUp, LocalHotspotPolicy.startDecision(stamps, null, now))
        assertEquals(RefreshAction.GIVE_UP, LocalHotspotPolicy.onRefresh(false, false, stamps, now))
    }

    @Test
    fun `pruning drops the stamps outside the window`() {
        assertEquals(listOf(95_000L, 99_000L), LocalHotspotPolicy.prune(listOf(10_000L, 95_000L, 99_000L), 100_000L))
    }

    // Unanswered mark

    @Test
    fun `a throwing call gives the mark back to the earlier host`() {
        val first = Any()
        val second = Any()
        assertEquals(first, LocalHotspotPolicy.pendingAfterThrow(second, second, first))
        assertNull(LocalHotspotPolicy.pendingAfterThrow(second, second, null))
    }

    @Test
    fun `a throw does not take a mark that another host holds now`() {
        val first = Any()
        val second = Any()
        val third = Any()
        assertEquals(third, LocalHotspotPolicy.pendingAfterThrow(third, second, first))
        assertNull(LocalHotspotPolicy.pendingAfterThrow(null, second, first))
    }

    // The old pair

    private val closedPair = "wlan1" to "192.168.97.139"

    @Test
    fun `beforeSet leaves the own pair out and keeps the others`() {
        val now = listOf(iface("wlan1", "192.168.97.139"), iface("wlan0", "192.168.1.20"), iface("lo", null))
        assertEquals(setOf("wlan0" to "192.168.1.20"), LocalHotspotPolicy.beforeSet(now, closedPair))
        assertEquals(
            setOf("wlan1" to "192.168.97.139", "wlan0" to "192.168.1.20"),
            LocalHotspotPolicy.beforeSet(now, null),
        )
    }

    @Test
    fun `the same pair as the closed hotspot is picked once beforeSet leaves it out`() {
        val now = listOf(iface("wlan1", "192.168.97.139"))
        val raw = now.map { it.name to it.siteLocalIpv4!! }.toSet()
        assertNull(LocalHotspotPolicy.newInterface(raw, now))
        assertEquals("wlan1", LocalHotspotPolicy.newInterface(LocalHotspotPolicy.beforeSet(now, closedPair), now)?.name)
    }

    @Test
    fun `the old pair is gone only when both name and address are absent together`() {
        assertTrue(LocalHotspotPolicy.oldPairGone(listOf(iface("wlan1", "192.168.97.139")), null))
        assertTrue(LocalHotspotPolicy.oldPairGone(listOf(iface("wlan1", "10.0.0.1")), closedPair))
        assertTrue(LocalHotspotPolicy.oldPairGone(listOf(iface("wlan2", "192.168.97.139")), closedPair))
        assertFalse(LocalHotspotPolicy.oldPairGone(listOf(iface("wlan1", "192.168.97.139")), closedPair))
    }

    @Test
    fun `a refusal holds a refresh for 5 s but not a live one`() {
        assertEquals(RefreshAction.WAIT, LocalHotspotPolicy.onRefresh(false, false, emptyList(), 4_999L, 0L))
        assertEquals(RefreshAction.RESTART, LocalHotspotPolicy.onRefresh(false, false, emptyList(), 5_000L, 0L))
        assertEquals(RefreshAction.REREAD, LocalHotspotPolicy.onRefresh(true, false, emptyList(), 100L, 0L))
        val now = 100_000L
        assertEquals(
            RefreshAction.GIVE_UP,
            LocalHotspotPolicy.onRefresh(false, false, listOf(now - 3, now - 2, now - 1), now, now - 5_000L),
        )
    }

    @Test
    fun `the stray and old interface lines keep their texts`() {
        assertEquals("LocalHotspot: closed a hotspot that an earlier start left up", LocalHotspotPolicy.strayClosedLine())
        assertEquals(
            "LocalHotspot: the old interface was still up after 3 s; its address is not taken as an old one",
            LocalHotspotPolicy.oldInterfaceLine(),
        )
    }

    // Teardown gate

    private fun teardown(
        groupRemoved: Boolean = true,
        channelClosed: Boolean = true,
        kept: Boolean = false,
        at: Long = 0L,
    ) = P2pTeardown(stopped = true, groupRemoved = groupRemoved, channelClosed = channelClosed, keptGroup = kept, stoppedAtMs = at)

    @Test
    fun `no record opens the gate`() {
        assertEquals(Gate.GO, LocalHotspotPolicy.teardownGate(null, 34, 1_000L))
    }

    @Test
    fun `a pending removal waits and a finished teardown goes`() {
        assertEquals(Gate.WAIT, LocalHotspotPolicy.teardownGate(teardown(groupRemoved = false, channelClosed = false), 34, 1_000L))
        assertEquals(Gate.WAIT, LocalHotspotPolicy.teardownGate(teardown(channelClosed = false), 34, 1_000L))
        assertEquals(Gate.GO, LocalHotspotPolicy.teardownGate(teardown(), 34, 1_000L))
    }

    @Test
    fun `an unfinished teardown times out after its bound, which outlasts the channel close fallback`() {
        val pending = teardown(groupRemoved = false, channelClosed = false)
        val bound = LocalHotspotPolicy.TEARDOWN_TIMEOUT_MS
        assertTrue(bound > P2pChannelClosePolicy.CLOSE_FALLBACK_MS)
        assertEquals(Gate.WAIT, LocalHotspotPolicy.teardownGate(pending, 34, P2pChannelClosePolicy.CLOSE_FALLBACK_MS + 1))
        assertEquals(Gate.WAIT, LocalHotspotPolicy.teardownGate(pending, 34, bound))
        assertEquals(Gate.TIMED_OUT, LocalHotspotPolicy.teardownGate(pending, 34, bound + 1))
    }

    @Test
    fun `below API 27 the channel flag is ignored`() {
        assertEquals(Gate.GO, LocalHotspotPolicy.teardownGate(teardown(channelClosed = false), 26, 1_000L))
        assertEquals(Gate.WAIT, LocalHotspotPolicy.teardownGate(teardown(groupRemoved = false, channelClosed = false), 26, 1_000L))
    }

    @Test
    fun `a kept group waits for its release and then times out`() {
        val kept = teardown(groupRemoved = false, channelClosed = false, kept = true)
        assertEquals(Gate.WAIT, LocalHotspotPolicy.teardownGate(kept, 34, 0L))
        assertEquals(Gate.TIMED_OUT, LocalHotspotPolicy.teardownGate(kept, 34, LocalHotspotPolicy.TEARDOWN_TIMEOUT_MS + 1))
    }

    // Close reason

    @Test
    fun `a noted user exit wins over a launcher replacement`() {
        assertEquals(CloseReason.USER_EXIT, LocalHotspotPolicy.closeReason(launcherReplaced = true, userExitNoted = true))
        assertEquals(CloseReason.LAUNCHER_REPLACED, LocalHotspotPolicy.closeReason(true, false))
        assertEquals(CloseReason.SESSION_END, LocalHotspotPolicy.closeReason(false, false))
    }

    // Pinned log texts

    @Test
    fun `the log lines keep the prefixes the rig greps`() {
        assertEquals(
            "LocalHotspot: started ssid=AndroidShare_1 security=2 bssid=aa:bb iface=wlan2 ip=192.168.254.140",
            LocalHotspotPolicy.startedLine("AndroidShare_1", 2, "aa:bb", "wlan2", "192.168.254.140"),
        )
        assertEquals(
            "LocalHotspot: band unknown (mask=0), channel list 0, 2.4 GHz cap applied",
            LocalHotspotPolicy.bandLine(LocalBand.UNKNOWN, 0),
        )
        assertEquals(
            "LocalHotspot: band 2.4 GHz (mask=1), channel list 0, 2.4 GHz cap applied",
            LocalHotspotPolicy.bandLine(LocalBand.BAND_24, 1),
        )
        assertEquals(
            "LocalHotspot: band 5 GHz or higher (mask=2), channel list 0, 2.4 GHz cap not forced",
            LocalHotspotPolicy.bandLine(LocalBand.ABOVE_24, 2),
        )
        assertEquals("LocalHotspot: station state before=joined after=not", LocalHotspotPolicy.stationLine(true, false))
        assertEquals("LocalHotspot: station state before=not after=not", LocalHotspotPolicy.stationLine(false, false))
        assertEquals("LocalHotspot: closed (session end)", LocalHotspotPolicy.closedLine(CloseReason.SESSION_END))
        assertEquals("LocalHotspot: closed (user exit)", LocalHotspotPolicy.closedLine(CloseReason.USER_EXIT))
        assertEquals("LocalHotspot: closed (launcher replaced)", LocalHotspotPolicy.closedLine(CloseReason.LAUNCHER_REPLACED))
        assertEquals("LocalHotspot: restart after close attempt=2", LocalHotspotPolicy.restartLine(RestartCause.CLOSE, 2))
        assertEquals("LocalHotspot: restart after platform stop attempt=1", LocalHotspotPolicy.restartLine(RestartCause.PLATFORM_STOP, 1))
        assertEquals("LocalHotspot: restart after wake attempt=1", LocalHotspotPolicy.restartLine(RestartCause.WAKE, 1))
        assertEquals("LocalHotspot: p2p teardown done before start", LocalHotspotPolicy.teardownLine(done = true))
        assertEquals("LocalHotspot: p2p teardown timed out", LocalHotspotPolicy.teardownLine(done = false))
        assertEquals("LocalHotspot: the platform stopped the hotspot", LocalHotspotPolicy.platformStoppedLine())
        assertEquals(
            "SoftApCredentials: the local hotspot is in use; the manual name, password, interface, static BSSID and Auto-Enable Hotspot are not applied.",
            LocalHotspotPolicy.notAppliedLine(),
        )
    }

    @Test
    fun `the failed line names the reason, the failure and the advice`() {
        val line = LocalHotspotPolicy.failedLine(2, LocalHotspotFailure.GENERIC, 34)
        assertTrue(line, line.startsWith("LocalHotspot: start failed reason=2 (GENERIC): "))
        assertTrue(line.contains("Use the system hotspot"))
        assertTrue(LocalHotspotPolicy.failedLine(0, LocalHotspotFailure.NO_INTERFACE, 34).startsWith("LocalHotspot: start failed reason=0 (NO_INTERFACE): "))
    }

    // The live frequency (API 33)

    @Test
    fun `the slowest live frequency decides and zeros do not count`() {
        assertEquals(0, LocalHotspotPolicy.liveFrequency(emptyList()))
        assertEquals(0, LocalHotspotPolicy.liveFrequency(listOf(0, -1)))
        assertEquals(5785, LocalHotspotPolicy.liveFrequency(listOf(0, 5785)))
        assertEquals(2437, LocalHotspotPolicy.liveFrequency(listOf(5180, 2437)))
    }

    @Test
    fun `a live frequency decides the cap whatever band was asked for`() {
        for (band in LocalBand.values()) {
            assertTrue(LocalHotspotPolicy.capsVideo(band, 2462))
            assertFalse(LocalHotspotPolicy.capsVideo(band, 5785))
            assertEquals(LocalHotspotPolicy.capsVideo(band), LocalHotspotPolicy.capsVideo(band, 0))
        }
    }

    @Test
    fun `the live band lines`() {
        assertEquals(
            "LocalHotspot: live frequency 5785 MHz, 2.4 GHz cap not forced",
            LocalHotspotPolicy.liveBandLine(LocalBand.BAND_24, 5785),
        )
        assertEquals(
            "LocalHotspot: live frequency 2462 MHz, 2.4 GHz cap applied",
            LocalHotspotPolicy.liveBandLine(LocalBand.ABOVE_24, 2462),
        )
        assertEquals(
            "LocalHotspot: band 5 GHz or higher (mask=2), channel list 0, 2.4 GHz cap applied",
            LocalHotspotPolicy.bandLine(LocalBand.ABOVE_24, 2, 2462),
        )
        assertEquals(
            "LocalHotspot: no live frequency (SecurityException), so the requested band decides the cap",
            LocalHotspotPolicy.noLiveBandLine("SecurityException"),
        )
    }
}
