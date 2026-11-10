package com.andrerinas.openheadunit.view

/** Puts the dimmer just above the projection view and below the HUD and the night overlay. */
object DimmerLayerPolicy {
    enum class Kind { PROJECTION, OTHER }

    /** [children] excludes the dimmer; the result is the index to add it at. */
    fun index(children: List<Kind>): Int {
        val projection = children.lastIndexOf(Kind.PROJECTION)
        return if (projection < 0) children.size else projection + 1
    }
}
