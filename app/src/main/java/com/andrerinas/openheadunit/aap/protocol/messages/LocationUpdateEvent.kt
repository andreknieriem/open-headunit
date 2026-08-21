package com.andrerinas.openheadunit.aap.protocol.messages

import android.location.Location
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class LocationUpdateEvent(location: Location)
    : SensorEvent(Sensors.SensorType.LOCATION_VALUE, makeProto(location)) {

    companion object {
        /** Stand-in for a fix that reports no accuracy: 100 m, in the field's millimetres. */
        private const val UNKNOWN_ACCURACY_MM = 100_000

        private fun makeProto(location: Location): Message {
            return Sensors.SensorBatch.newBuilder().also { batch ->
                batch.addLocationData(
                    Sensors.SensorBatch.LocationData.newBuilder().apply {
                        timestamp = location.time
                        latitude = (location.latitude * 1E7).toInt()
                        longitude = (location.longitude * 1E7).toInt()
                        if (location.hasAltitude()) {
                            altitude = (location.altitude * 1E2).toInt()
                        }
                        if (location.hasBearing()) {
                            bearing = (location.bearing * 1E6).toInt()
                        }
                        if (location.hasSpeed()) {
                            speed = (location.speed * 1E3).toInt()
                        }
                        // accuracy is a proto2 `required` field, so leaving it unset throws
                        // UninitializedMessageException out of build() - and on the control
                        // channel that surfaces as a HandleException, which kills the session.
                        // A fix without a reported accuracy is rare but does happen, so declare
                        // it coarse rather than fail to build at all.
                        accuracy = if (location.hasAccuracy()) {
                            (location.accuracy * 1E3).toInt()
                        } else {
                            UNKNOWN_ACCURACY_MM
                        }
                    }
                )
            }.build()
        }
    }
}
