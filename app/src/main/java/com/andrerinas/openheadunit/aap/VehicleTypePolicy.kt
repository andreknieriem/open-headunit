package com.andrerinas.openheadunit.aap

/**
 * What kind of vehicle this head unit says it is, in Android Auto's own numbering.
 *
 * The user picks this, but the microphone setting overrides it. A motorcycle is the only claim that
 * makes the phone record with its own microphone instead of asking for ours. It also matters for
 * connecting at all: the phone ships two car services, and the newer one aborts setup when a head
 * unit announces no microphone under any other type. Nothing tells us which one is running, so the
 * claim goes out either way.
 *
 * Pure: no Android, no logging.
 */
object VehicleTypePolicy {

    /** Android Auto's VEHICLE_TYPE_CAR. */
    const val CAR = 1

    /** Android Auto's VEHICLE_TYPE_TRUCK. */
    const val TRUCK = 2

    /** Android Auto's VEHICLE_TYPE_MOTORCYCLE. */
    const val MOTORCYCLE = 3

    /** The types a user can choose, in the order the picker shows them. */
    val SELECTABLE = listOf(CAR, TRUCK, MOTORCYCLE)

    /** What to announce. A head unit that will not record has to claim a motorcycle. */
    fun vehicleType(selected: Int, headUnitMicEnabled: Boolean): Int =
        if (headUnitMicEnabled) sanitised(selected) else MOTORCYCLE

    /** True when the microphone setting sends a type other than the one picked, so the picker must say so. */
    fun isOverriddenByMicrophone(selected: Int, headUnitMicEnabled: Boolean): Boolean =
        vehicleType(selected, headUnitMicEnabled) != sanitised(selected)

    /** A stored value that is not one of ours reads as a car, the same as sending nothing. */
    fun sanitised(vehicleType: Int): Int = if (vehicleType in SELECTABLE) vehicleType else CAR

    fun indexOf(vehicleType: Int): Int = SELECTABLE.indexOf(sanitised(vehicleType))

    fun atIndex(index: Int): Int = SELECTABLE.getOrElse(index) { CAR }
}
