package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control

/**
 * Mirrors Android Auto's service-discovery check of displays as decompiled, so a test fails the way
 * the phone does. A test oracle, not a policy, so it has no production caller.
 */
object GearheadDisplayRules {

    /** The phone's own message for the first rule [services] break, or null when the phone accepts them. */
    fun violation(services: List<Control.Service>): String? {
        val sinks = services.filter { it.hasMediaSinkService() && it.mediaSinkService.videoConfigsCount > 0 }
        for (sink in sinks) {
            for (config in sink.mediaSinkService.videoConfigsList) {
                if (!config.hasFrameRate()) return "wrong FPS "
                if (!config.hasDensity()) return "density missing"
                if (config.density <= 0) return "wrong density ${config.density}"
            }
        }
        if (sinks.isEmpty()) return "No displays found"
        val displays = sinks.map { it.mediaSinkService.displayId to it.mediaSinkService.displayType }
        if (displays.map { it.first }.toSet().size != displays.size) return "Display IDs must be unique"
        val primary = displays.firstOrNull { it.first == 0 }
            ?: return "There must be a primary display with display ID 0"
        if (primary.second != Control.DisplayType.DISPLAY_TYPE_MAIN) return "The primary display must have type MAIN"
        if (displays.count { it.second == Control.DisplayType.DISPLAY_TYPE_MAIN } != 1) {
            return "There must be exactly one display of type MAIN"
        }
        if (displays.count { it.second == Control.DisplayType.DISPLAY_TYPE_CLUSTER } > 1) {
            return "There must be at most one display of type CLUSTER"
        }
        val claimed = mutableSetOf<Int>()
        for ((displayId, _) in displays) {
            val inputs = services.filter {
                it.hasInputSourceService() && it.inputSourceService.displayId == displayId && it.id !in claimed
            }
            if (inputs.isEmpty()) return "No input for display $displayId"
            if (inputs.size > 1) return "Multiple inputs found for display $displayId"
            claimed += inputs.single().id
        }
        return null
    }
}
