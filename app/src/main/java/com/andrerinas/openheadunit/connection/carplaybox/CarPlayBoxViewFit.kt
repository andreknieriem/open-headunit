package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.utils.Settings

/** The size the box picture takes in its container, by the same fit rule `TouchCoordinateMapper` undoes. */
object CarPlayBoxViewFit {

    fun size(containerW: Int, containerH: Int, videoW: Int, videoH: Int, mode: Settings.VideoFitMode): Pair<Int, Int> {
        if (containerW <= 0 || containerH <= 0 || videoW <= 0 || videoH <= 0) return containerW to containerH
        if (mode == Settings.VideoFitMode.FILL) return containerW to containerH
        val videoRatio = videoW.toFloat() / videoH
        val containerWider = containerW.toFloat() / containerH > videoRatio
        val matchHeight = if (mode == Settings.VideoFitMode.COVER) !containerWider else containerWider
        return if (matchHeight) Pair((containerH * videoRatio).toInt(), containerH)
        else Pair(containerW, (containerW / videoRatio).toInt())
    }
}
