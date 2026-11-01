package com.andrerinas.openheadunit.aap

/** Picks the overlay rows that are switched on and have something to show. */
object NightOverlayRows {
    enum class Row { CLOCK, NAVIGATION, TRACK }

    fun select(content: NightOverlayState.Content, options: NightOverlayOptions): Set<Row> {
        val rows = LinkedHashSet<Row>()
        if (options.showClock) rows.add(Row.CLOCK)
        if (options.showNavigation && hasNavigationText(content.navigation)) rows.add(Row.NAVIGATION)
        if (options.showTrack && content.isPlaying != false &&
            (content.title.isNotBlank() || content.artist.isNotBlank())) rows.add(Row.TRACK)
        return rows
    }

    private fun hasNavigationText(navigation: NightOverlayState.Navigation?): Boolean =
        navigation != null && (
            navigation.icon != null || navigation.maneuver.isNotBlank() ||
                navigation.distanceMeters != null || !navigation.road.isNullOrBlank() ||
                !navigation.details.isNullOrBlank()
            )
}
