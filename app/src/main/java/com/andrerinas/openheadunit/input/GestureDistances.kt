package com.andrerinas.openheadunit.input

/** Gesture sizes in dp. Each pixel floor is the old fixed value, so a low-density panel keeps its zones. */
internal object GestureDistances {
    private const val TRAVEL_DP = 96f
    private const val SIDEWAYS_DP = 48f
    private const val PINCH_SPREAD_DP = 24f
    private const val EDGE_ZONE_FLOOR_PX = 100f
    private const val TRAVEL_FLOOR_PX = 200f
    private const val SIDEWAYS_FLOOR_PX = 100f
    private const val PINCH_SPREAD_FLOOR_PX = 48f

    fun edgeZonePx(density: Float, zoneDp: Float) = maxOf(EDGE_ZONE_FLOOR_PX, zoneDp * density)
    fun travelPx(density: Float) = maxOf(TRAVEL_FLOOR_PX, TRAVEL_DP * density)
    fun sidewaysLimitPx(density: Float) = maxOf(SIDEWAYS_FLOOR_PX, SIDEWAYS_DP * density)
    fun pinchSpreadPx(density: Float) = maxOf(PINCH_SPREAD_FLOOR_PX, PINCH_SPREAD_DP * density)
}
