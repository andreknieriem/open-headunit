package com.andrerinas.openheadunit.aap.protocol

object Channel {

    const val ID_CTR = 0
    const val ID_SEN = 1
    const val ID_VID = 2
    const val ID_INP = 3
    const val ID_AUD = 6
    const val ID_AU1 = 4
    const val ID_AU2 = 5
    const val ID_MIC = 7
    const val ID_BTH = 8
    const val ID_MPB = 9
    const val ID_NAV = 10
    const val ID_NOT = 11
    const val ID_NOTI = 11
    const val ID_PHONE = 12
    const val ID_WIFI = 13

    /**
     * The auxiliary display's video, when one is advertised. Its own channel, because the phone ends
     * the session with MULTIPLE_DISPLAY_CONFIGS over a second config on [ID_VID].
     */
    const val ID_VID2 = 14

    /** The aux display's input; the phone ends the session when an announced display has no input of its own. */
    const val ID_INP2 = 15

    fun name(channel: Int): String {
        when (channel) {
            ID_CTR -> return "CONTROL"
            ID_VID -> return "VIDEO"
            ID_VID2 -> return "VIDEO_AUX"
            ID_INP -> return "INPUT"
            ID_INP2 -> return "INPUT_AUX"
            ID_SEN -> return "SENSOR"
            ID_MIC -> return "MIC"
            ID_AUD -> return "AUDIO"
            ID_AU1 -> return "AUDIO1"
            ID_AU2 -> return "AUDIO2"
            ID_BTH -> return "BLUETOOTH"
            ID_MPB -> return "MUSIC_PLAYBACK"
            ID_NAV -> return "NAVIGATION_DIRECTIONS"
            ID_NOTI -> return "NOTIFICATION"
            ID_PHONE -> return "PHONE_STATUS"
        }
        return "UNK"
    }

    fun isAudio(chan: Int): Boolean {
        return chan == Channel.ID_AUD || chan == Channel.ID_AU1 || chan == Channel.ID_AU2
    }

    /** Any projected display's video. Ask this rather than comparing to [ID_VID] directly. */
    fun isVideo(chan: Int): Boolean = chan == ID_VID || chan == ID_VID2

    /** Any projected display's input. Ask this rather than comparing to [ID_INP] directly. */
    fun isInput(chan: Int): Boolean = chan == ID_INP || chan == ID_INP2
}