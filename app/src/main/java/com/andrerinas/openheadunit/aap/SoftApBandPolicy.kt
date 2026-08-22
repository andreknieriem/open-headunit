package com.andrerinas.openheadunit.aap

/** A radio band a soft AP can be brought up on. */
enum class ApBand { BAND_5GHZ, BAND_2GHZ }

/** Which band the user wants this head unit's access point brought up on. */
enum class HotspotBandPreference {
    /** Ask for 5 GHz, and fall back to 2.4 GHz if no access point comes up. The default. */
    AUTO,

    /** Ask for 5 GHz and stop there, rather than falling back to a band that may carry no video. */
    FORCE_5GHZ,

    /** Ask for 2.4 GHz only, for a radio that will not host a 5 GHz access point. */
    FORCE_2_4GHZ;

    companion object {
        /** [Settings.hotspotBand][com.andrerinas.openheadunit.utils.Settings.hotspotBand] as a
         *  preference, defaulting to [AUTO] for any value we do not recognise. */
        fun fromSetting(value: Int): HotspotBandPreference = when (value) {
            1 -> FORCE_5GHZ
            2 -> FORCE_2_4GHZ
            else -> AUTO
        }
    }
}

/**
 * Which band to bring the head unit's access point up on for wireless Android Auto, and in what
 * order to try.
 *
 * What the band costs, measured 2026-08-05 on a head unit hotspot. Over a 2.4 GHz access point at
 * 1080p/60 the phone completed the handshake, joined the network and opened the video channel, then
 * closed the socket having sent no frame at all: thirty-two times in under five minutes. On the
 * *same* access point on the *same* band, an 800x480/30 stream held for as long as it was watched,
 * and at 1080p/60 on 5 GHz it held too.
 *
 * So this is a throughput ceiling rather than a dead band, and 5 GHz is the default because it is
 * the only band measured to carry a full-resolution stream, not because 2.4 GHz carries nothing.
 * [VideoStarvationPolicy] is where that measurement is recorded in full and where the advice
 * reaches the user; this object only decides what to ask the radio for.
 *
 * The two band hints on the settings screen round that ceiling to "reliable up to 720p", which sits
 * between the two figures above and was never itself measured. That is deliberate: the numbers here
 * are 480p holding and 1080p dying, and a hint that quoted both would be asking a user to work out
 * where their own panel falls between them. Do not "correct" the strings back to the raw pair.
 *
 * The reference head unit software running this route on the same class of hardware brings its AP
 * up on channel 36, and the AA protocol itself carries the phone's answer to the same question
 * (`selected_wifi_channel_type`, one of 2.4-only / 5-only / dual), which this app does not read.
 *
 * @see VideoStarvationPolicy
 */
object SoftApBandPolicy {

    /**
     * The bands to try, best first. Trying is the only way to find out: an app cannot ask whether
     * the soft AP will accept 5 GHz, since `is5GHzBandSupported()` describes the station side and
     * vendors ship radios that answer yes there and still refuse to host an AP on it.
     *
     * [HotspotBandPreference.FORCE_5GHZ] exists because the fallback is not free: a session on
     * 2.4 GHz can connect, look entirely healthy and show nothing, which is harder to diagnose than
     * no access point at all. It also halves the worst case, one bring-up window instead of two.
     * [HotspotBandPreference.FORCE_2_4GHZ] is for a radio that will not host 5 GHz, and wants a
     * lower resolution and frame rate to go with it.
     */
    fun attemptOrder(
        preference: HotspotBandPreference = HotspotBandPreference.AUTO
    ): List<ApBand> = when (preference) {
        HotspotBandPreference.AUTO -> listOf(ApBand.BAND_5GHZ, ApBand.BAND_2GHZ)
        HotspotBandPreference.FORCE_5GHZ -> listOf(ApBand.BAND_5GHZ)
        HotspotBandPreference.FORCE_2_4GHZ -> listOf(ApBand.BAND_2GHZ)
    }

    /** `SoftApConfiguration.BAND_2GHZ` / `BAND_5GHZ` (API 30+). */
    fun softApConfigurationBand(band: ApBand): Int = when (band) {
        ApBand.BAND_2GHZ -> 1
        ApBand.BAND_5GHZ -> 2
    }

    /** The hidden `WifiConfiguration.apBand` field's values, used below API 30. */
    fun legacyApBand(band: ApBand): Int = when (band) {
        ApBand.BAND_2GHZ -> 0
        ApBand.BAND_5GHZ -> 1
    }

    /** How the band reads in a log line users paste into bug reports. */
    fun describe(band: ApBand): String = when (band) {
        ApBand.BAND_2GHZ -> "2.4 GHz"
        ApBand.BAND_5GHZ -> "5 GHz"
    }

    /**
     * How the user's choice reads in that same log line.
     *
     * Logged on every bring-up, including [HotspotBandPreference.AUTO]: a line that only appears in
     * the unusual case is a line whose absence tells a reader nothing.
     */
    fun describePreference(preference: HotspotBandPreference): String = when (preference) {
        HotspotBandPreference.AUTO -> "automatic (5 GHz, falling back to 2.4 GHz)"
        HotspotBandPreference.FORCE_5GHZ -> "5 GHz only, set by the user"
        HotspotBandPreference.FORCE_2_4GHZ -> "2.4 GHz only, set by the user"
    }
}
