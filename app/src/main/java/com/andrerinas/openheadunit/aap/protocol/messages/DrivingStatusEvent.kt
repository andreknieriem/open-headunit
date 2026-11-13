package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class DrivingStatusEvent(status: Sensors.SensorBatch.DrivingStatusData.Status)
    : SensorEvent(Sensors.SensorType.DRIVING_STATUS_VALUE, makeProto(status)) {

    companion object {
        /** Speed threshold (m/s) above which the car is treated as moving (~5.4 km/h). */
        const val MOVING_SPEED_THRESHOLD_MPS = 1.5f

        private fun makeProto(status: Sensors.SensorBatch.DrivingStatusData.Status): Message {
            return Sensors.SensorBatch.newBuilder()
                    .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder()
                            .setStatus(status.number))
                    .build()
        }
    }
}
