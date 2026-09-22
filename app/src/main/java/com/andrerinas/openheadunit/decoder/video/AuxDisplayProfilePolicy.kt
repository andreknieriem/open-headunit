package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.protocol.proto.Control

private typealias Resolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType
private typealias FrameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType

/**
 * The video configuration announced for an auxiliary display, such as an instrument cluster.
 *
 * Deliberately not routed through HeadUnitScreenConfig: that object is the main canvas, and its
 * locked resolution has to keep meaning the main display.
 */
object AuxDisplayProfilePolicy {

    /** Shows the navigation map. Google's `KEYCODE_NAVIGATION`. */
    const val KEYCODE_NAVIGATION = 65538

    /** Shows the turn card. Google's `KEYCODE_TURN_CARD`, which is valid on auxiliary displays only. */
    const val KEYCODE_TURN_CARD = 65544

    /** The announced size of each resolution the protocol has, in the order it should be preferred. */
    private val SIZES: List<Pair<Resolution, Pair<Int, Int>>> = listOf(
        Resolution._800x480 to (800 to 480),
        Resolution._1280x720 to (1280 to 720),
        Resolution._1920x1080 to (1920 to 1080),
        Resolution._720x1280 to (720 to 1280),
        Resolution._1080x1920 to (1080 to 1920),
    )

    /** What goes into the auxiliary sink's `VideoConfiguration`. */
    data class Profile(
        val resolution: Resolution,
        val widthMargin: Int,
        val heightMargin: Int,
        val density: Int,
        val frameRate: FrameRate,
    )

    /**
     * The smallest standard resolution that contains the panel, with the remainder as margins.
     *
     * A margin is the part of the frame that will not be rendered, so it is the announced size minus
     * the panel rather than the other way round; a panel larger than anything on offer takes the
     * largest and no margin, and is scaled.
     */
    fun profileFor(widthPx: Int, heightPx: Int, densityDpi: Int): Profile {
        val panelW = widthPx.coerceAtLeast(1)
        val panelH = heightPx.coerceAtLeast(1)
        val containing = SIZES
            .filter { it.second.first >= panelW && it.second.second >= panelH }
            .minByOrNull { it.second.first.toLong() * it.second.second }
        val chosen = containing ?: SIZES.maxByOrNull { it.second.first.toLong() * it.second.second }!!
        return Profile(
            resolution = chosen.first,
            widthMargin = (chosen.second.first - panelW).coerceAtLeast(0),
            heightMargin = (chosen.second.second - panelH).coerceAtLeast(0),
            // A density of 0 is what an unreadable panel reports, and the phone lays its UI out from
            // this, so it falls back to the baseline rather than going out as nothing.
            density = if (densityDpi in 1..640) densityDpi else 160,
            // 30 rather than 60 on purpose: a cluster view is a map or a turn card, and the second
            // stream's bandwidth is taken from the main picture's.
            frameRate = FrameRate._30,
        )
    }

    /**
     * What the second sink is announced as. AUXILIARY honours our content choice; CLUSTER gets
     * whatever the phone decides an instrument cluster shows.
     */
    enum class Role { AUXILIARY, CLUSTER }

    fun roleOrDefault(stored: String?): Role = Role.values().firstOrNull { it.name == stored } ?: Role.AUXILIARY

    fun displayType(role: Role): Control.DisplayType = when (role) {
        Role.AUXILIARY -> Control.DisplayType.DISPLAY_TYPE_AUXILIARY
        Role.CLUSTER -> Control.DisplayType.DISPLAY_TYPE_CLUSTER
    }

    /** The phone ignores a content keycode on a cluster, so none is sent there. */
    fun announcesContent(role: Role): Boolean = role == Role.AUXILIARY

    /** Whether a stored content choice is one the protocol allows on an auxiliary display. */
    fun contentKeycodeOrDefault(stored: Int): Int =
        if (stored == KEYCODE_TURN_CARD) KEYCODE_TURN_CARD else KEYCODE_NAVIGATION
}
