package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.utils.Settings

/**
 * What the head unit announces when the user turns on "Full navigation data (lanes)". Pure logic, apart from [enabled].
 *
 * The phone sends one of two navigation formats per session, never both:
 *  - legacy: NEXTTURNDETAILS (0x8004) and NEXTTURNDISTANCEANDTIME (0x8005): one icon code, a road name, a distance;
 *  - current: INSTRUMENT_CLUSTER_NAVIGATION_STATE (0x8006) and ..._CURRENT_POSITION (0x8007): the full step with the
 *    maneuver type, lanes, cue text, destination, distances and ETA.
 *
 * Which one it picks follows the protocol version the head unit asks for in its version request, not the one the phone
 * answers with (it answers 1.7 either way): from 1.6 on it sends the current format, below it the legacy one.
 *  - this app asks for 1.2 and receives only the legacy format; asking for 1.4 changes nothing (tested);
 *  - Zlink 6.0.38 asks for 1.6 and receives only the current format (recordings of thousands of messages, not one legacy
 *    message among them); in its HUD mode it asks for 1.4 on purpose, because its HUD code only parses the legacy format;
 *  - the MIB cluster project reports the same switch at 1.6 (its unit claims 1.5 and gets the legacy pair), and
 *    open-android-auto notes the legacy turn event as the one for head units below 1.6.
 * With the switch on, this app announces what Zlink announces; with it off, nothing changes.
 *
 * This app never answers an unknown message with "unexpected message" (that reply makes the phone reset the link), and it
 * parses 0x8006/0x8007 itself, so asking for 1.6 does not disturb the projection.
 */
object FullNavigationProfile {

    /** the first minor version for which the phone sends the current navigation format (what Zlink 6.0.38 asks for) */
    const val FULL_NAVIGATION_MINOR = 6

    /** the release a Google Desktop Head Unit reports, as Zlink announces it */
    const val DHU_SOFTWARE_BUILD = "2022-03-30-438482292"
    const val DHU_SOFTWARE_VERSION = "2.0-linux"

    private const val DEFAULT_SOFTWARE_BUILD = "1"
    private const val DEFAULT_SOFTWARE_VERSION = "0.1.0"
    private const val DEFAULT_NAVIGATION_INTERVAL_MS = 1000
    private const val FULL_NAVIGATION_INTERVAL_MS = 500

    /** The switch, read where the session is negotiated; false whenever the settings cannot be read (no context in host
     *  tests, or credential-encrypted storage before the first unlock). */
    fun enabled(context: Context?): Boolean = try {
        context != null && Settings(context).fullNavigationData
    } catch (e: RuntimeException) {
        false
    }

    fun versionMinor(full: Boolean): Int = if (full) FULL_NAVIGATION_MINOR else AapVersionNegotiation.ANNOUNCED_MINOR

    fun navigationIntervalMs(full: Boolean): Int = if (full) FULL_NAVIGATION_INTERVAL_MS else DEFAULT_NAVIGATION_INTERVAL_MS

    fun softwareBuild(full: Boolean): String = if (full) DHU_SOFTWARE_BUILD else DEFAULT_SOFTWARE_BUILD

    fun softwareVersion(full: Boolean): String = if (full) DHU_SOFTWARE_VERSION else DEFAULT_SOFTWARE_VERSION
}
