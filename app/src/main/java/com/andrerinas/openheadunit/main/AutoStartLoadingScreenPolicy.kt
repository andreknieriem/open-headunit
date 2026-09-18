package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.main.MainActivity.ConnectionUiMode

/**
 * Whether an automatic launch opens on the loading screen instead of the home screen, whether the
 * status pill rides that screen, and how long the screen may hold with nothing answering.
 *
 * Pure, so every combination is a unit test rather than a device.
 */
object AutoStartLoadingScreenPolicy {

    /**
     * Whether this launch is going to try to connect on its own, so the loading screen is the
     * honest first thing to show.
     *
     * The three flags are the home screen's own auto-connect list, which arms on a plain launch
     * with nothing in the intent; [automaticSource] is a receiver having started us.
     */
    fun opensOnLaunch(
        automaticSource: Boolean,
        lastSession: Boolean,
        selfMode: Boolean,
        singleUsb: Boolean
    ): Boolean = automaticSource || lastSession || selfMode || singleUsb

    /** The UI an attempt wears. Off, an automatic launch falls back to the home screen and pill. */
    fun modeFor(automatic: Boolean, enabled: Boolean): ConnectionUiMode =
        if (automatic && enabled) ConnectionUiMode.OVERLAY else ConnectionUiMode.PILL

    /**
     * Whether the home screen's pill is allowed up.
     *
     * It was suppressed outright while the overlay owned the screen; now the overlay is a screen
     * the pill is meant to narrate, so only the setting takes it down.
     */
    fun showsHomePill(overlayOwnsScreen: Boolean, pillSettingOn: Boolean): Boolean =
        !overlayOwnsScreen || pillSettingOn

    /** The projection's copy is up only over its own loading screen, and only with a step to name. */
    fun showsProjectionPill(
        loadingOverlayVisible: Boolean,
        stage: ConnectionStage?,
        pillSettingOn: Boolean
    ): Boolean = loadingOverlayVisible && stage != null && pillSettingOn

    /**
     * Whether an expired attempt keeps the loading screen rather than dropping to the home screen.
     *
     * Only a self-opened one, and only while the stack is still reporting a step: a wake can take
     * 91 s, so the 30 s bound would land the user on the home screen mid-bring-up. A person who
     * opened the app by hand keeps that bound, so their own screen comes back.
     */
    fun holdsOverlay(stage: ConnectionStage?, automaticLaunch: Boolean): Boolean =
        automaticLaunch && stage != null
}
