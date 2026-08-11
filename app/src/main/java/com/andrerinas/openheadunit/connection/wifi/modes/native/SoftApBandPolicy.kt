package com.andrerinas.openheadunit.connection.wifi.modes.native

/** A radio band a soft AP can be brought up on. */
enum class ApBand { BAND_5GHZ, BAND_2GHZ }

/**
 * Which band to bring the head unit's access point up on for wireless Android Auto, and in what
 * order to try.
 *
 * 5 GHz first is not a preference, it is what the link needs. On 2.4 GHz the handshake completes
 * and the projection then dies within seconds — a failure mode that reads as "Android Auto starts
 * and drops" and has cost several rounds of log-reading to recognise. The reference head unit
 * software running this route on the same class of hardware brings its AP up on channel 36, and
 * the AA protocol itself carries the phone's answer to the same question (`selected_wifi_channel
 * _type`, one of 2.4-only / 5-only / dual).
 *
 * 2.4 GHz is kept only as a fallback for radios with no 5 GHz support at all, where the choice is
 * between a shaky link and none.
 */
object SoftApBandPolicy {

    /**
     * The bands to try, best first. Trying is the only way to find out: an app cannot ask whether
     * the soft AP will accept 5 GHz — `is5GHzBandSupported()` describes the station side, and
     * vendors ship radios that answer yes there and still refuse to host an AP on it.
     */
    fun attemptOrder(prefer5Ghz: Boolean = true): List<ApBand> =
        if (prefer5Ghz) listOf(ApBand.BAND_5GHZ, ApBand.BAND_2GHZ)
        else listOf(ApBand.BAND_2GHZ)

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
}
