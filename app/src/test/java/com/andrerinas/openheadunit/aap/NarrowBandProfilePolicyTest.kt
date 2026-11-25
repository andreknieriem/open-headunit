package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrowBandProfilePolicyTest {

    // --- when the cap applies at all -----------------------------------------------------------

    @Test
    fun `the one case worth acting on - no 5 GHz band, wireless, and the cap on`() {
        assertTrue(NarrowBandProfilePolicy.caps(supports5Ghz = false, wirelessSession = true, capEnabled = true))
    }

    @Test
    fun `a wired session caps nothing, whatever the radio can do`() {
        assertFalse(NarrowBandProfilePolicy.caps(supports5Ghz = false, wirelessSession = false, capEnabled = true))
    }

    @Test
    fun `only a no caps - a yes and an unknown both leave the profile alone`() {
        // A yes describes the station side and a null means the platform would not answer. Neither
        // is grounds for lowering somebody's picture.
        assertFalse(NarrowBandProfilePolicy.caps(supports5Ghz = true, wirelessSession = true, capEnabled = true))
        assertFalse(NarrowBandProfilePolicy.caps(supports5Ghz = null, wirelessSession = true, capEnabled = true))
    }

    @Test
    fun `the user can say no`() {
        assertFalse(NarrowBandProfilePolicy.caps(supports5Ghz = false, wirelessSession = true, capEnabled = false))
    }

    // --- what it does to the profile -----------------------------------------------------------

    @Test
    fun `the frame rate comes down on the one case`() {
        assertEquals(
            NarrowBandProfilePolicy.CAPPED_FRAME_RATE,
            NarrowBandProfilePolicy.cappedFrameRate(60, supports5Ghz = false, wirelessSession = true, capEnabled = true)
        )
    }

    @Test
    fun `the frame rate is never raised`() {
        // A user who asked for 30 on a radio with 5 GHz keeps 30, and one already at 30 on a narrow
        // band is left there rather than being handed the cap as a floor.
        assertEquals(30, NarrowBandProfilePolicy.cappedFrameRate(30, supports5Ghz = true, wirelessSession = true, capEnabled = true))
        assertEquals(30, NarrowBandProfilePolicy.cappedFrameRate(30, supports5Ghz = false, wirelessSession = true, capEnabled = true))
    }

    @Test
    fun `the frame rate is untouched everywhere the cap does not apply`() {
        assertEquals(60, NarrowBandProfilePolicy.cappedFrameRate(60, supports5Ghz = true, wirelessSession = true, capEnabled = true))
        assertEquals(60, NarrowBandProfilePolicy.cappedFrameRate(60, supports5Ghz = null, wirelessSession = true, capEnabled = true))
        assertEquals(60, NarrowBandProfilePolicy.cappedFrameRate(60, supports5Ghz = false, wirelessSession = false, capEnabled = true))
        assertEquals(60, NarrowBandProfilePolicy.cappedFrameRate(60, supports5Ghz = false, wirelessSession = true, capEnabled = false))
    }

    @Test
    fun `the resolution ceiling is 720p on the one case and absent everywhere else`() {
        assertEquals(
            Settings.Resolution._1280x720,
            NarrowBandProfilePolicy.linkCeiling(supports5Ghz = false, wirelessSession = true, capEnabled = true)
        )
        assertNull(NarrowBandProfilePolicy.linkCeiling(supports5Ghz = true, wirelessSession = true, capEnabled = true))
        assertNull(NarrowBandProfilePolicy.linkCeiling(supports5Ghz = null, wirelessSession = true, capEnabled = true))
        assertNull(NarrowBandProfilePolicy.linkCeiling(supports5Ghz = false, wirelessSession = false, capEnabled = true))
        assertNull(NarrowBandProfilePolicy.linkCeiling(supports5Ghz = false, wirelessSession = true, capEnabled = false))
    }

    @Test
    fun `a ceiling is returned rather than a resolution, so a lower choice survives`() {
        // The caller keeps min(current, ceiling). If this ever returned a value to use outright, a
        // user on 480p would be raised to 720p by a policy whose whole point is asking for less.
        val ceiling = NarrowBandProfilePolicy.linkCeiling(supports5Ghz = false, wirelessSession = true, capEnabled = true)!!
        assertTrue(Settings.Resolution._800x480.width * Settings.Resolution._800x480.height < ceiling.width * ceiling.height)
    }

    // --- the audio codec -----------------------------------------------------------------------

    @Test
    fun `AAC is announced where the cap applies, and wherever the user asked for it`() {
        for (supports in listOf(true, false, null)) {
            for (wireless in listOf(true, false)) {
                for (capEnabled in listOf(true, false)) {
                    val label = "$supports/$wireless/$capEnabled"
                    val capped = NarrowBandProfilePolicy.caps(supports, wireless, capEnabled)
                    assertTrue(label, NarrowBandProfilePolicy.useAac(true, supports, wireless, capEnabled))
                    assertEquals(label, capped, NarrowBandProfilePolicy.useAac(false, supports, wireless, capEnabled))
                }
            }
        }
    }

    // --- what it says --------------------------------------------------------------------------

    @Test
    fun `the advice says what was lowered, and to what`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 60, wirelessSession = true, capEnabled = true
        )!!
        assertTrue(advice, advice.contains(NarrowBandProfilePolicy.CAPPED_RESOLUTION.resName))
        assertTrue(advice, advice.contains("${NarrowBandProfilePolicy.CAPPED_FRAME_RATE} fps"))
        assertTrue(advice, advice.contains("AAC"))
        // The claim it must never make again, now that it does change something.
        assertFalse(advice, advice.contains("Nothing here has been changed for you"))
        // AAC's saving is unmeasured; the line names the codec, not a figure.
        assertFalse(advice, advice.contains("tenth"))
    }

    @Test
    fun `the advice is given even when the user was already below the cap`() {
        // A line that only appears in the unusual case is a line whose absence tells a reader
        // nothing, and the resolution can still be over the ceiling at 30 fps.
        assertNotNull(NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 30, wirelessSession = true, capEnabled = true
        ))
    }

    @Test
    fun `with the cap off it goes back to naming the remedy and says it changed nothing`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 60, wirelessSession = true, capEnabled = false
        )!!
        assertTrue(advice, advice.contains("Nothing here has been changed for you"))
        // And a user who already took the advice is not told again.
        assertNull(NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 30, wirelessSession = true, capEnabled = false
        ))
    }

    @Test
    fun `every combination the cap does not reach is silent`() {
        for (supports in listOf(true, false, null)) {
            for (fps in listOf(30, 60)) {
                for (wireless in listOf(true, false)) {
                    for (capEnabled in listOf(true, false)) {
                        val advice = NarrowBandProfilePolicy.advice(supports, fps, wireless, capEnabled)
                        val narrow = supports == false && wireless
                        val speaks = narrow && (capEnabled || fps == NarrowBandProfilePolicy.FULL_FRAME_RATE)
                        val label = "$supports/$fps/$wireless/$capEnabled"
                        if (speaks) assertNotNull(label, advice) else assertNull(label, advice)
                    }
                }
            }
        }
    }

    @Test
    fun `a five gigahertz radio on a two point four network is narrow`() {
        assertTrue(NarrowBandProfilePolicy.runsNarrow(supports5Ghz = true, sessionFrequencyMhz = 2437))
        assertTrue(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = true, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 2437
            )
        )
    }

    @Test
    fun `a group on five gigahertz is not narrow`() {
        assertFalse(NarrowBandProfilePolicy.runsNarrow(supports5Ghz = true, sessionFrequencyMhz = 5180))
        assertFalse(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = true, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 5180
            )
        )
    }

    @Test
    fun `an unreadable frequency leaves the radio to answer`() {
        assertFalse(NarrowBandProfilePolicy.runsNarrow(supports5Ghz = true, sessionFrequencyMhz = 0))
        assertTrue(NarrowBandProfilePolicy.runsNarrow(supports5Ghz = false, sessionFrequencyMhz = 0))
    }

    @Test
    fun `a wired session is never capped by the network it is not on`() {
        assertFalse(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = true, wirelessSession = false, capEnabled = true,
                sessionFrequencyMhz = 2437
            )
        )
    }

    @Test
    fun `the band the network came up on caps the frame rate and announces AAC`() {
        assertEquals(
            NarrowBandProfilePolicy.CAPPED_FRAME_RATE,
            NarrowBandProfilePolicy.cappedFrameRate(
                fpsLimit = 60, supports5Ghz = true, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 2437
            )
        )
        assertTrue(
            NarrowBandProfilePolicy.useAac(
                userChoice = false, supports5Ghz = true, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 2437
            )
        )
    }

    @Test
    fun `the advice names the network rather than the radio when the radio has the band`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = true, fpsLimit = 60, wirelessSession = true, capEnabled = true,
            sessionFrequencyMhz = 2437
        )
        assertTrue(advice!!.startsWith("This session's network is on 2.4 GHz"))
    }

    @Test
    fun `the advice still names the radio when the band is absent`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 60, wirelessSession = true, capEnabled = true
        )
        assertTrue(advice!!.startsWith("This unit has no 5 GHz band"))
    }

    // --- a platform that cannot be asked about the band at all ---------------------------------

    @Test
    fun `a platform with no way to read the band is narrow`() {
        // Below API 21 the radio has no is5GHzBandSupported and nothing reports a frequency, so
        // both of the other inputs read absent and the cap could never fire without this.
        assertTrue(
            NarrowBandProfilePolicy.runsNarrow(
                supports5Ghz = null, sessionFrequencyMhz = 0, bandUnreadable = true
            )
        )
    }

    @Test
    fun `an unknown band on a platform that can be asked still caps nobody`() {
        // The regression this guards: a modern unit whose WiFi service threw answers null too, and
        // must keep the picture it asked for.
        assertFalse(
            NarrowBandProfilePolicy.runsNarrow(
                supports5Ghz = null, sessionFrequencyMhz = 0, bandUnreadable = false
            )
        )
        assertFalse(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = null, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 0, bandUnreadable = false
            )
        )
    }

    @Test
    fun `a yes does not save a unit whose platform cannot have answered it`() {
        // Nothing below API 21 can return true from a call that does not exist, so a true here came
        // from somewhere else and is not a reading.
        assertTrue(
            NarrowBandProfilePolicy.runsNarrow(
                supports5Ghz = true, sessionFrequencyMhz = 0, bandUnreadable = true
            )
        )
    }

    @Test
    fun `the unreadable band lowers the whole profile, and the user can still say no`() {
        assertEquals(
            NarrowBandProfilePolicy.CAPPED_FRAME_RATE,
            NarrowBandProfilePolicy.cappedFrameRate(
                fpsLimit = 60, supports5Ghz = null, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 0, bandUnreadable = true
            )
        )
        assertEquals(
            NarrowBandProfilePolicy.CAPPED_RESOLUTION,
            NarrowBandProfilePolicy.linkCeiling(
                supports5Ghz = null, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 0, bandUnreadable = true
            )
        )
        assertTrue(
            NarrowBandProfilePolicy.useAac(
                userChoice = false, supports5Ghz = null, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 0, bandUnreadable = true
            )
        )
        assertFalse(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = null, wirelessSession = true, capEnabled = false,
                sessionFrequencyMhz = 0, bandUnreadable = true
            )
        )
    }

    @Test
    fun `the advice claims no band it could not read, and no measurement it does not have`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = null, fpsLimit = 60, wirelessSession = true, capEnabled = true,
            sessionFrequencyMhz = 0, bandUnreadable = true
        )
        assertNotNull(advice)
        assertTrue(advice!!.startsWith("This unit's Android is too old to report which band it is on"))
        assertFalse("the 2.4 GHz measurement belongs to the other two arms", advice.contains("2.4 GHz access point"))
        assertTrue(advice.contains("Android 4.4 tablet"))
    }

    @Test
    fun `a real reading is preferred to cannot ask when both are present`() {
        val absent = NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 60, wirelessSession = true, capEnabled = true,
            sessionFrequencyMhz = 0, bandUnreadable = true
        )
        assertTrue(absent!!.startsWith("This unit has no 5 GHz band"))
        val onNetwork = NarrowBandProfilePolicy.advice(
            supports5Ghz = null, fpsLimit = 60, wirelessSession = true, capEnabled = true,
            sessionFrequencyMhz = 2437, bandUnreadable = true
        )
        assertTrue(onNetwork!!.startsWith("This session's network is on 2.4 GHz"))
    }

    // --- a link that has already refused to carry the picture ------------------------------------

    @Test
    fun `a link measured too slow caps a 5 GHz session the band would have left alone`() {
        assertTrue(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = true, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 5180, linkProvedTooSlow = true
            )
        )
        assertEquals(
            NarrowBandProfilePolicy.CAPPED_RESOLUTION,
            NarrowBandProfilePolicy.linkCeiling(
                supports5Ghz = true, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 5180, linkProvedTooSlow = true
            )
        )
        assertEquals(
            NarrowBandProfilePolicy.CAPPED_FRAME_RATE,
            NarrowBandProfilePolicy.cappedFrameRate(
                fpsLimit = 60, supports5Ghz = true, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 5180, linkProvedTooSlow = true
            )
        )
        assertTrue(
            NarrowBandProfilePolicy.useAac(
                userChoice = false, supports5Ghz = true, wirelessSession = true, capEnabled = true,
                sessionFrequencyMhz = 5180, linkProvedTooSlow = true
            )
        )
    }

    @Test
    fun `the band switch does not govern a cap the link itself earned`() {
        // "Lower video on a 2.4 GHz link" is a guess about a link; this is that link having already
        // refused the picture three sessions running, and turning the guess off does not undo it.
        assertTrue(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = true, wirelessSession = true, capEnabled = false,
                linkProvedTooSlow = true
            )
        )
        // The band arm is still the user's to switch off.
        assertFalse(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = false, wirelessSession = true, capEnabled = false,
                linkProvedTooSlow = false
            )
        )
    }

    @Test
    fun `a wired session is not capped by a link it is not running on`() {
        assertFalse(
            NarrowBandProfilePolicy.caps(
                supports5Ghz = true, wirelessSession = false, capEnabled = true,
                linkProvedTooSlow = true
            )
        )
    }

    @Test
    fun `the earned cap answers first, and names what it measured rather than the band`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = true, fpsLimit = 60, wirelessSession = true, capEnabled = false,
            sessionFrequencyMhz = 5180, linkProvedTooSlow = true
        )
        assertNotNull(advice)
        assertTrue(advice!!.contains("without a single video frame"))
        assertTrue(advice.contains("Change the resolution or the frame rate"))
        assertFalse("nothing was left unchanged here", advice.contains("Nothing here has been changed"))
    }

    @Test
    fun `every existing caller is unchanged, because the new input defaults to false`() {
        assertFalse(NarrowBandProfilePolicy.runsNarrow(supports5Ghz = null, sessionFrequencyMhz = 0))
        assertFalse(NarrowBandProfilePolicy.runsNarrow(supports5Ghz = true, sessionFrequencyMhz = 0))
        assertTrue(NarrowBandProfilePolicy.runsNarrow(supports5Ghz = false, sessionFrequencyMhz = 0))
        assertTrue(NarrowBandProfilePolicy.runsNarrow(supports5Ghz = true, sessionFrequencyMhz = 2437))
    }

    @Test
    fun `the local hotspot flag names the local hotspot and the 2_4 GHz evidence, not an old Android`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = null, fpsLimit = 60, wirelessSession = true, capEnabled = true,
            sessionFrequencyMhz = 0, bandUnreadable = true, localHotspotNarrow = true
        )
        assertNotNull(advice)
        assertTrue(advice!!, advice.startsWith("This unit's local hotspot"))
        assertFalse(advice, advice.contains("too old"))
        assertTrue(advice, advice.contains("2.4 GHz access point"))
    }

    @Test
    fun `without the flag an unreadable band keeps the old Android text`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = null, fpsLimit = 60, wirelessSession = true, capEnabled = true,
            sessionFrequencyMhz = 0, bandUnreadable = true
        )
        assertTrue(advice!!.startsWith("This unit's Android is too old to report which band it is on"))
    }

    @Test
    fun `a real 2_4 GHz reading still wins over the local hotspot flag`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = null, fpsLimit = 60, wirelessSession = true, capEnabled = true,
            sessionFrequencyMhz = 2437, bandUnreadable = true, localHotspotNarrow = true
        )
        assertTrue(advice!!.startsWith("This session's network is on 2.4 GHz"))
        val noBand = NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 60, wirelessSession = true, capEnabled = true,
            sessionFrequencyMhz = 0, bandUnreadable = true, localHotspotNarrow = true
        )
        assertTrue(noBand!!.startsWith("This unit has no 5 GHz band"))
    }
}
