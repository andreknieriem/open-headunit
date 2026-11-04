package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.app.AutoStartCheckPolicy.Verdict

/**
 * What the auto-start check screen leads with (#1122): the one cause it names in its headline,
 * and the fixes its main button applies. Kept apart from the views so it can be tested on the JVM.
 */
object AutoStartCheckScreenPolicy {

    enum class Headline { FORCE_STOPPED, BATTERY, DURASPEED, MAKER_LIST, NOTHING_FOUND }

    /** Only fixes that leave /system alone. The maker's lists always ask first, on their own row. */
    enum class Fix { EXEMPTIONS, DURASPEED_OFF }

    data class State(
        val verdict: Verdict,
        /** Root or ADB is there, so the fixes can run. */
        val canAct: Boolean,
        /** Battery exemptions exist on this Android version. */
        val exemptionsApply: Boolean,
        /** Exempt from battery saving and not restricted in the background. */
        val exempt: Boolean,
        val duraSpeedInstalled: Boolean,
        val duraSpeedOffOnBoot: Boolean,
        /** Null when the unit has no maker list the app knows. */
        val makerList: MakerWhitelistPolicy.Status?
    )

    /** The first cause wins, in the order a user should deal with them. */
    fun headline(s: State): Headline = when {
        s.verdict == Verdict.FORCE_STOPPED -> Headline.FORCE_STOPPED
        s.verdict == Verdict.BATTERY -> Headline.BATTERY
        s.duraSpeedInstalled && !s.duraSpeedOffOnBoot -> Headline.DURASPEED
        s.makerList != null && s.makerList != MakerWhitelistPolicy.Status.APPLIED -> Headline.MAKER_LIST
        else -> Headline.NOTHING_FOUND
    }

    fun recommendedFixes(s: State): Set<Fix> {
        if (!s.canAct) return emptySet()
        val fixes = mutableSetOf<Fix>()
        if (s.exemptionsApply && !s.exempt) fixes.add(Fix.EXEMPTIONS)
        if (s.duraSpeedInstalled && !s.duraSpeedOffOnBoot) fixes.add(Fix.DURASPEED_OFF)
        return fixes
    }
}
