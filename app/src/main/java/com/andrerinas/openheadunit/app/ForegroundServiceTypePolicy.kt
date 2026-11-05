package com.andrerinas.openheadunit.app

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Which foreground-service types this service claims, and when.
 *
 * The microphone type cannot be claimed at service start. Since Android 14 it is a while-in-use
 * type, so holding the permission is necessary and not sufficient: a service started from the
 * background is refused with "the app must be in the eligible state/exemptions" even with
 * RECORD_AUDIO granted, and this service starts from boot, USB and Bluetooth receivers. Measured on
 * a background Bluetooth auto-start, five times out of five.
 *
 * So the start claims [baseTypeMask], which holds no while-in-use type and cannot be refused, and
 * the microphone is added by [withMicrophone] at the moment capture actually opens - by then the
 * projection is on screen and the app is eligible. It is dropped again when capture stops, so the
 * type is held only while it is true.
 */
object ForegroundServiceTypePolicy {

    /**
     * What to claim when the service starts, from any caller and any process state.
     *
     * @param sdkInt the running platform level.
     * @return the mask for startForeground, or 0 before Android Q, where the call takes no types.
     */
    fun baseTypeMask(sdkInt: Int): Int {
        if (sdkInt < Build.VERSION_CODES.Q) return 0

        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    /**
     * What to claim while the microphone is open.
     *
     * @param sdkInt the running platform level.
     * @param recordAudioGranted whether RECORD_AUDIO is held right now.
     * @param headUnitMicEnabled the user's microphone setting.
     * @return [baseTypeMask] plus the microphone type when both hold, and [baseTypeMask] otherwise.
     */
    fun withMicrophone(sdkInt: Int, recordAudioGranted: Boolean, headUnitMicEnabled: Boolean): Int {
        val base = baseTypeMask(sdkInt)
        if (base == 0) return 0
        if (!recordAudioGranted || !headUnitMicEnabled) return base

        return base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }

    /**
     * [mask] plus the location type while the mileage is being counted.
     *
     * From Android Q a foreground service only gets location in the background when it holds this
     * type, and from Android 11 only when it was started or re-started while the app was on
     * screen. Like the microphone it is a while-in-use type, so a claim from the background can be
     * refused on Android 14: the caller tries it and falls back to [mask] when it is.
     *
     * @param mask what the service claims otherwise, from [baseTypeMask] or [withMicrophone].
     * @param fineLocationGranted whether ACCESS_FINE_LOCATION is held right now.
     * @param countingMileage whether the maintenance feature is on.
     */
    fun withLocation(mask: Int, sdkInt: Int, fineLocationGranted: Boolean, countingMileage: Boolean): Int {
        if (sdkInt < Build.VERSION_CODES.Q) return mask
        if (!fineLocationGranted || !countingMileage) return mask

        return mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }
}
