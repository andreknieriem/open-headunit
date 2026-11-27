package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.BinaryPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue

/**
 * The commands we send on the event channel, as plist bodies, and the RTSP request that carries one.
 * Follows xcertplay airplay/AirPlaySession.kt `sendHidReport` and `sendCommandLocked`, and CarPlayMediaEngine.kt `mainScreenKeyFrameCommand`.
 */
object AirPlayCommands {
    const val TYPE_HID = "hidSendReport"
    const val TYPE_KEY_FRAME = "forceKeyFrame"

    /** [uid] is a HID device uid; the plist holds it as lower-case hex. */
    fun hidSendReport(uid: Long, report: ByteArray): PlistValue.Dict = PlistValue.dict(
        "type" to PlistValue.Text(TYPE_HID),
        "uuid" to PlistValue.Text(java.lang.Long.toHexString(uid)),
        "hidReport" to PlistValue.Data(report),
    )

    fun touch(report: ByteArray): PlistValue.Dict = hidSendReport(HidDevices.TOUCH_UID, report)

    /** No `uuid` param: the phone restarts the main screen. */
    fun forceKeyFrame(): PlistValue.Dict = PlistValue.dict(
        "type" to PlistValue.Text(TYPE_KEY_FRAME),
        "params" to PlistValue.Dict(emptyList()),
    )

    /** The text of the command: `POST /command RTSP/1.0` with this [cseq]. The caller seals it with the event cipher. */
    fun eventRequest(command: PlistValue.Dict, cseq: Int): ByteArray {
        val body = BinaryPlist.encode(command)
        val head = "POST /command RTSP/1.0\r\nContent-Type: ${RtspRoutes.CONTENT_PLIST}\r\n" +
            "Content-Length: ${body.size}\r\nCSeq: $cseq\r\n\r\n"
        return head.toByteArray(Charsets.US_ASCII) + body
    }
}
