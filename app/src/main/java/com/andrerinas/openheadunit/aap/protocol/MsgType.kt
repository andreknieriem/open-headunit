package com.andrerinas.openheadunit.aap.protocol

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

object MsgType {

    const val SIZE = 2

    fun isControl(type: Int): Boolean {
        return type >= 1 && type <= 26
    }

    fun name(type: Int, channel: Int): String {

        when (type) {
            Control.ControlMsgType.MESSAGE_VERSION_REQUEST_VALUE -> return "Version Request"
            Control.ControlMsgType.MESSAGE_VERSION_RESPONSE_VALUE -> return "Version Response"
            Control.ControlMsgType.MESSAGE_ENCAPSULATED_SSL_VALUE -> return "SSL Handshake Data"
            Control.ControlMsgType.MESSAGE_AUTH_COMPLETE_VALUE -> return "SSL Authentication Complete Notification"
            Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE -> return "Service Discovery Request"
            Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE -> return "Service Discovery Response"
            Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE -> return "Channel Open Request"
            Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE -> return "Channel Open Response"
            Control.ControlMsgType.MESSAGE_CHANNEL_CLOSE_NOTIFICATION_VALUE -> return "Channel Close Notification"
            Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE -> return "Ping Request"
            Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE -> return "Ping Response"
            Control.ControlMsgType.MESSAGE_NAV_FOCUS_REQUEST_VALUE -> return "Navigation Focus Request"
            Control.ControlMsgType.MESSAGE_NAV_FOCUS_NOTIFICATION_VALUE -> return "Navigation Focus Notification"
            Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE -> return "Byebye Request"
            Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE -> return "Byebye Response"
            Control.ControlMsgType.MESSAGE_VOICE_SESSION_NOTIFICATION_VALUE -> return "Voice Session Notification"
            Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE -> return "Audio Focus Request"
            Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE -> return "Audio Focus Notification"
            Control.ControlMsgType.MESSAGE_CAR_CONNECTED_DEVICES_REQUEST_VALUE -> return "Car Connected Devices Request"
            Control.ControlMsgType.MESSAGE_CAR_CONNECTED_DEVICES_RESPONSE_VALUE -> return "Car Connected Devices Response"
            Control.ControlMsgType.MESSAGE_USER_SWITCH_REQUEST_VALUE -> return "User Switch Request"
            Control.ControlMsgType.MESSAGE_BATTERY_STATUS_NOTIFICATION_VALUE -> return "Battery Status Notification"
            Control.ControlMsgType.MESSAGE_CALL_AVAILABILITY_STATUS_VALUE -> return "Call Availability Status"
            Control.ControlMsgType.MESSAGE_USER_SWITCH_RESPONSE_VALUE -> return "User Switch Response"
            Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_UPDATE_VALUE -> return "Service Discovery Update"

            Media.MsgType.MEDIA_MESSAGE_DATA_VALUE -> return "Media Data"
            Media.MsgType.MEDIA_MESSAGE_CODEC_CONFIG_VALUE -> return "Codec Config"
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> return "Media Setup Request"
            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                return when (channel) {
                    Channel.ID_SEN -> "Sensor Start Request"
                    Channel.ID_INP, Channel.ID_INP2 -> "Input Event"
                    Channel.ID_MPB -> "Media Playback Status"
                    else -> "Media Start Request"
                }
            }
            Media.MsgType.MEDIA_MESSAGE_STOP_VALUE -> {
                return when (channel) {
                    Channel.ID_SEN -> "Sensor Start Response"
                    Channel.ID_INP, Channel.ID_INP2 -> "Input Binding Request"
                    Channel.ID_MPB -> "Media Playback Status"
                    else -> "Media Stop Request"
                }
            }
            Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE -> {
                return when (channel) {
                    Channel.ID_SEN -> "Sensor Event"
                    Channel.ID_INP, Channel.ID_INP2 -> "Input Binding Response"
                    Channel.ID_MPB -> "Media Playback Status"
                    else -> "Media Config Response"
                }
            }
            Media.MsgType.MEDIA_MESSAGE_ACK_VALUE -> return "Codec/Media Data Ack"
            Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE -> return "Mic Start/Stop Request"
            Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE -> return "Mic Response"
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> return "Video Focus Request"
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE -> return "Video Focus Notification"
            Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST_VALUE -> return "Update UI Config Request"
            Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REPLY_VALUE -> return "Update UI Config Reply"
            Media.MsgType.MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION_VALUE -> return "Audio Underflow Notification"

            Control.ControlMsgType.MESSAGE_UNEXPECTED_MESSAGE_VALUE -> return "Unexpected Message"
            Control.ControlMsgType.MESSAGE_FRAMING_ERROR_VALUE -> return "Framing Error Notification"
        }
        return "Unknown ($type)"
    }

}