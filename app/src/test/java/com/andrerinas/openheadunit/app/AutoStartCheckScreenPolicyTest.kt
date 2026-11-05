package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.app.AutoStartCheckPolicy.Verdict
import com.andrerinas.openheadunit.app.AutoStartCheckScreenPolicy.Fix
import com.andrerinas.openheadunit.app.AutoStartCheckScreenPolicy.Headline
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoStartCheckScreenPolicyTest {

    /** A unit where nothing is wrong: exempt, no DuraSpeed, the maker's lists already applied. */
    private val fine = AutoStartCheckScreenPolicy.State(
        verdict = Verdict.UNKNOWN,
        canAct = true,
        exemptionsApply = true,
        exempt = true,
        duraSpeedInstalled = false,
        duraSpeedOffOnBoot = false,
        makerList = MakerWhitelistPolicy.Status.APPLIED
    )

    @Test
    fun `nothing found when every check is fine`() {
        assertEquals(Headline.NOTHING_FOUND, AutoStartCheckScreenPolicy.headline(fine))
        assertEquals(emptySet<Fix>(), AutoStartCheckScreenPolicy.recommendedFixes(fine))
    }

    @Test
    fun `a force stop in the log leads, before anything else`() {
        val s = fine.copy(verdict = Verdict.FORCE_STOPPED, exempt = false, duraSpeedInstalled = true)
        assertEquals(Headline.FORCE_STOPPED, AutoStartCheckScreenPolicy.headline(s))
    }

    @Test
    fun `battery comes before duraspeed and the maker list`() {
        val s = fine.copy(verdict = Verdict.BATTERY, exempt = false, duraSpeedInstalled = true,
            makerList = MakerWhitelistPolicy.Status.NOT_APPLIED)
        assertEquals(Headline.BATTERY, AutoStartCheckScreenPolicy.headline(s))
    }

    @Test
    fun `duraspeed leads until the app turns it off on each start`() {
        val on = fine.copy(duraSpeedInstalled = true)
        assertEquals(Headline.DURASPEED, AutoStartCheckScreenPolicy.headline(on))
        assertEquals(Headline.NOTHING_FOUND, AutoStartCheckScreenPolicy.headline(on.copy(duraSpeedOffOnBoot = true)))
    }

    @Test
    fun `a maker list without the app leads, a partly applied one too`() {
        assertEquals(Headline.MAKER_LIST,
            AutoStartCheckScreenPolicy.headline(fine.copy(makerList = MakerWhitelistPolicy.Status.NOT_APPLIED)))
        assertEquals(Headline.MAKER_LIST,
            AutoStartCheckScreenPolicy.headline(fine.copy(makerList = MakerWhitelistPolicy.Status.PARTLY_APPLIED)))
        assertEquals(Headline.NOTHING_FOUND, AutoStartCheckScreenPolicy.headline(fine.copy(makerList = null)))
    }

    @Test
    fun `the main button applies the exemptions and duraspeed, never the maker list`() {
        val s = fine.copy(exempt = false, duraSpeedInstalled = true, makerList = MakerWhitelistPolicy.Status.NOT_APPLIED)
        assertEquals(setOf(Fix.EXEMPTIONS, Fix.DURASPEED_OFF), AutoStartCheckScreenPolicy.recommendedFixes(s))
    }

    @Test
    fun `no fixes without root or adb`() {
        val s = fine.copy(canAct = false, exempt = false, duraSpeedInstalled = true)
        assertEquals(emptySet<Fix>(), AutoStartCheckScreenPolicy.recommendedFixes(s))
    }

    @Test
    fun `no exemption fix where android has no exemptions`() {
        val s = fine.copy(exemptionsApply = false, exempt = false)
        assertEquals(emptySet<Fix>(), AutoStartCheckScreenPolicy.recommendedFixes(s))
    }

    @Test
    fun `without root or adb getting access leads, after what was read without it`() {
        val none = fine.copy(canAct = false, duraSpeedInstalled = true, makerList = MakerWhitelistPolicy.Status.NOT_APPLIED)
        assertEquals(Headline.NO_ACCESS, AutoStartCheckScreenPolicy.headline(none))
        assertEquals(Headline.NO_ACCESS, AutoStartCheckScreenPolicy.headline(fine.copy(canAct = false)))
        assertEquals(Headline.BATTERY, AutoStartCheckScreenPolicy.headline(none.copy(verdict = Verdict.BATTERY)))
        assertEquals(Headline.FORCE_STOPPED, AutoStartCheckScreenPolicy.headline(none.copy(verdict = Verdict.FORCE_STOPPED)))
    }
}
