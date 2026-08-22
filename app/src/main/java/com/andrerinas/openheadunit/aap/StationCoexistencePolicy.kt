package com.andrerinas.openheadunit.aap

/**
 * What to say when this unit is joined to an ordinary WiFi network while hosting the group.
 *
 * One radio serving a station link and a group owner at once has to divide its time between them,
 * and when the two sit on different channels it also has to retune between them, which on a
 * single-channel chipset can stall projected video and audio together. That is worth telling a
 * reporter about. It is only worth telling them about when it has actually been established.
 *
 * The line this replaces did not establish it. `WifiP2pGroup.getFrequency()` arrived in API 29, so
 * every head unit below that reports the group frequency as zero - which is the whole class of
 * hardware that files this kind of bug - and the warning went out anyway, telling the user to
 * disconnect the other network on the strength of a comparison it had just described as
 * unavailable. On the unit that prompted this it fired on all three good runs and on neither bad
 * one: joined to a network the session had no dropout longer than 0.65 s in 138 s and no audio
 * underrun at all, and unjoined it lost the picture for four to eight seconds every ten and
 * underran thirty times. Advice that inverts the outcome is worse than silence.
 *
 * A rig then fired the surviving warning on a session that was measurably clean: two known
 * frequencies 260 MHz apart, and ten minutes at 45-55 fps with no gap on any channel. That is two
 * units now - the reporter's, where joined was the *good* configuration, and this one, where it cost
 * nothing - and no measurement anywhere in which disconnecting the station helped. The retune is
 * real physics and worth naming in a log a reporter attaches to an issue; telling them to act on it
 * is not supported by anything. So this object now describes and never prescribes.
 *
 * Pure, so every combination is a unit test rather than a device.
 */
object StationCoexistencePolicy {

    /** How loudly a finding should be logged. */
    enum class Level { INFO, WARN }

    /** One line about the station link, and how loudly to say it. */
    data class Finding(val level: Level, val message: String)

    /**
     * Describe a unit that is hosting the group without being joined to any other WiFi network.
     *
     * Separate from [describe] because that function's every branch presumes association, and a
     * zero station frequency there already means "associated, frequency unreadable on this Android
     * version" - a different fact that must not collapse into this one.
     *
     * This branch exists because the caller used to return silently when the supplicant was not
     * associated, so the good arm of a comparison printed a line and the other printed nothing.
     * Whether the unit is joined to a network is the variable that separated a clean session from
     * one losing picture and sound every ten seconds on the unit that prompted this object, and no
     * capture could be sorted into the right arm: a missing line meant either "not joined" or "the
     * read threw". Saying so costs one line per group and makes the arm readable.
     */
    fun describeNotAssociated(groupFrequency: Int): Finding = Finding(
        Level.INFO,
        if (groupFrequency > 0) {
            "This unit is not connected to any other WiFi network while hosting the WiFi Direct " +
                "group on $groupFrequency MHz. The radio serves the group alone."
        } else {
            "This unit is not connected to any other WiFi network while hosting the WiFi Direct " +
                "group. The radio serves the group alone."
        }
    )

    /**
     * Describe coexistence between a joined station link and the hosted group.
     *
     * [staFrequency] and [groupFrequency] are in MHz, with zero or less meaning "not available on
     * this Android version". The caller establishes that the station is associated at all; this
     * only decides what that is worth saying.
     */
    fun describe(staFrequency: Int, groupFrequency: Int): Finding {
        val known = staFrequency > 0 && groupFrequency > 0
        return when {
            known && staFrequency != groupFrequency -> Finding(
                Level.WARN,
                "This unit is connected to another WiFi network on $staFrequency MHz while hosting " +
                    "the WiFi Direct group on $groupFrequency MHz. One radio has to retune between " +
                    "the two, which can cost the projected video and audio a few hundred " +
                    "milliseconds at a time. Measured units have run clean in this state, so read " +
                    "this as context for a report rather than as something to change."
            )
            known -> Finding(
                Level.INFO,
                "This unit is connected to another WiFi network on the same channel as the WiFi " +
                    "Direct group ($staFrequency MHz). The two share airtime but the radio does " +
                    "not retune between them."
            )
            staFrequency > 0 -> Finding(
                Level.INFO,
                "This unit is connected to another WiFi network on $staFrequency MHz while hosting " +
                    "the WiFi Direct group. The group's frequency is not readable below Android 10, " +
                    "so whether the two contend cannot be told from here."
            )
            groupFrequency > 0 -> Finding(
                Level.INFO,
                "This unit is connected to another WiFi network while hosting the WiFi Direct group " +
                    "on $groupFrequency MHz. The station frequency is not readable here, so whether " +
                    "the two contend cannot be told from here."
            )
            else -> Finding(
                Level.INFO,
                "This unit is connected to another WiFi network while hosting the WiFi Direct " +
                    "group. Neither frequency is readable here, so whether the two contend cannot " +
                    "be told from here."
            )
        }
    }
}
