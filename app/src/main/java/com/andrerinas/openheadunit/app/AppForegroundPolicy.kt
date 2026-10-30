package com.andrerinas.openheadunit.app

/** Main-thread policy for a return after an observed non-configuration fully stopped episode. */
internal class AppForegroundPolicy {
    enum class Kind { OPEN, EXIT, INTERNAL_CONTINUATION }

    /** Retained by the Activity's ViewModelStore, not reconstructed from a saved Intent/Bundle. */
    class LogicalActivity {
        internal var created = false
    }


    private class Episode(val revision: Long, var consumed: Boolean)

    private var foreground = false
    private var observedStop = false
    private var handledBeforeStart = false
    private var episode: Episode? = null

    fun created(activity: LogicalActivity, kind: Kind): Kind? {
        if (activity.created) {
            // Only a retained logical instance proves configuration continuity. Do not replay
            // its stored OPEN/EXIT, but do account for its upcoming resume.
            delivered(Kind.INTERNAL_CONTINUATION)
            return null
        }
        activity.created = true
        return delivered(kind)
    }

    fun delivered(kind: Kind): Kind {
        if (foreground) episode?.consumed = true else handledBeforeStart = true
        return kind
    }

    fun foregroundStarted(revision: Long, entryActivity: Boolean) {
        foreground = true
        // Trampolines do not acquire app ownership or leave a delivery record for a later UI.
        episode = if (observedStop && entryActivity) {
            Episode(revision, handledBeforeStart)
        } else null
        observedStop = false
        handledBeforeStart = false
    }

    fun fullyStopped(changingConfigurations: Boolean) {
        foreground = false
        observedStop = !changingConfigurations
        episode = null
        handledBeforeStart = false
    }

    /** Consume globally even if compare-and-accept subsequently rejects a newer logical decision. */
    fun resumeRevision(): Long? {
        val current = episode ?: return null
        if (current.consumed) return null
        current.consumed = true
        return current.revision
    }
}
