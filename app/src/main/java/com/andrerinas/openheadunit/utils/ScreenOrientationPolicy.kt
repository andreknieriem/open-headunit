package com.andrerinas.openheadunit.utils

import android.content.pm.ActivityInfo

/**
 * Which way up a reading is meant to be. Four call sites each grew their own copy of this swap and
 * two omitted the AUTO/SYSTEM fallback, so a portrait window reading was adopted as the landscape
 * canvas and a portrait resolution was announced from it.
 */
object ScreenOrientationPolicy {

    /** The shape a reading is turned into before anything compares it with another. */
    enum class Normalisation {
        /** Longest side first. */
        LANDSCAPE,
        /** Shortest side first. */
        PORTRAIT,
        /** Neither is known, so the reading is left as it came. */
        AS_READ,
    }

    /** AUTO and SYSTEM take the orientation the configuration is actually in. */
    fun normalisation(
        setting: Settings.ScreenOrientation,
        configLandscape: Boolean,
        configPortrait: Boolean,
    ): Normalisation = when (setting) {
        Settings.ScreenOrientation.LANDSCAPE,
        Settings.ScreenOrientation.LANDSCAPE_REVERSE -> Normalisation.LANDSCAPE
        Settings.ScreenOrientation.PORTRAIT,
        Settings.ScreenOrientation.PORTRAIT_REVERSE -> Normalisation.PORTRAIT
        Settings.ScreenOrientation.AUTO,
        Settings.ScreenOrientation.SYSTEM -> when {
            configLandscape -> Normalisation.LANDSCAPE
            configPortrait -> Normalisation.PORTRAIT
            else -> Normalisation.AS_READ
        }
    }

    /**
     * [normalisation] for a reading that may not describe a window of this app.
     *
     * AUTO and SYSTEM mean "the way the projection is shown", and the configuration only says that
     * while one of this app's windows is what it describes. A reconnect that lands behind the
     * keyguard, under a launcher or with the screen off reads the keyguard's portrait instead: on a
     * Note8 locked to landscape that announced 1080x1920, locked it, and the projection then came
     * up in landscape around a portrait stream. [remembered] is the shape the projection was last
     * drawn in, the best guess for a head unit that does not move.
     */
    fun normalisation(
        setting: Settings.ScreenOrientation,
        configLandscape: Boolean,
        configPortrait: Boolean,
        configIsOurWindow: Boolean,
        remembered: Normalisation?,
    ): Normalisation = when (setting) {
        Settings.ScreenOrientation.AUTO,
        Settings.ScreenOrientation.SYSTEM ->
            if (!configIsOurWindow && remembered != null) remembered
            else normalisation(setting, configLandscape, configPortrait)
        else -> normalisation(setting, configLandscape, configPortrait)
    }

    /**
     * Whether a configuration or surface read now describes the projection as it will be shown:
     * one of this app's windows started, lit and unlocked, and not shrunk into picture-in-picture.
     * Behind the keyguard everything reads the keyguard's portrait, and our activity is resumed
     * for a moment even there. A state that cannot be read is not held against the reading.
     */
    fun describesProjection(
        startedActivity: Boolean,
        pictureInPicture: Boolean,
        screenInteractive: Boolean?,
        keyguardLocked: Boolean?,
    ): Boolean = startedActivity && !pictureInPicture && screenInteractive != false && keyguardLocked != true

    /** The shape a stored canvas was drawn in, or null when it is missing or square. */
    fun shapeOf(width: Int, height: Int): Normalisation? = when {
        width <= 0 || height <= 0 -> null
        width > height -> Normalisation.LANDSCAPE
        height > width -> Normalisation.PORTRAIT
        else -> null
    }

    /**
     * What the projection asks for while a session holds its negotiated resolution, or null when
     * the setting itself applies.
     *
     * The stream keeps the shape it was negotiated in, so a window that turns under it is drawn
     * stretched. AUTO has held its shape since the lock existed. SYSTEM is not held: it follows
     * the system, and a turn to the other shape redraws the session ([redrawsSession]). Holding it
     * kept a session started on a unit locked portrait in portrait after rotation was turned on.
     */
    fun sessionOrientation(setting: Settings.ScreenOrientation, negotiatedLandscape: Boolean): Int? =
        when (setting) {
            Settings.ScreenOrientation.AUTO -> fixedShape(negotiatedLandscape)
            else -> null
        }

    /** How long a turned window must stay turned before its session is redrawn. */
    const val SHAPE_SETTLE_MS = 1_000L

    /**
     * Whether a session should end and reconnect so Android Auto draws it in the window's new shape.
     *
     * A session cannot change its resolution, so this is the only way the stream follows a window
     * that turned: under SYSTEM when the system turns, and under any setting when the setting itself
     * is changed mid-session. AUTO holds the window in the session's shape, so it never differs there.
     * Only for a window that is ours: the keyguard reads portrait whatever the mount. Only to the
     * other shape: reverse landscape is still landscape. And once per window shape, so a session that
     * comes back in the old shape is not redrawn on every landing.
     */
    fun redrawsSession(
        negotiated: Normalisation?,
        window: Normalisation?,
        readingIsOurs: Boolean,
        alreadyRedrawnFor: Normalisation?,
    ): Boolean = readingIsOurs &&
        negotiated != null && window != null && window != negotiated && window != alreadyRedrawnFor

    private fun fixedShape(landscape: Boolean): Int =
        if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    /** A reading turned the way [normalisation] says. */
    fun normalise(w: Int, h: Int, normalisation: Normalisation): ProjectionCanvasPolicy.Rect =
        when (normalisation) {
            Normalisation.LANDSCAPE -> ProjectionCanvasPolicy.Rect(Math.max(w, h), Math.min(w, h))
            Normalisation.PORTRAIT -> ProjectionCanvasPolicy.Rect(Math.min(w, h), Math.max(w, h))
            Normalisation.AS_READ -> ProjectionCanvasPolicy.Rect(w, h)
        }
}
