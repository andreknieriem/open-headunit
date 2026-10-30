package com.andrerinas.openheadunit.connection

/** Serialized by CommManager's lifecycle lock; Android effects run on Main, outside that lock. */
internal class AppExitPolicy {
    class Request internal constructor(
        val id: Long,
        val killProcess: Boolean,
        val userStop: Boolean,
        val requiresKillOnDisconnect: Boolean,
    ) {
        internal var committed = false
    }
    enum class Effect { NONE, STOPPED, KILL_PROCESS, RESTART_SERVICE }
    data class Completion(val effect: Effect, val owner: Long, val request: Request? = null)

    var revision = 0L
        private set
    var stopEpoch = 0L
        private set
    private var current: Request? = null
    private var stopping: Request? = null
    val isStopping: Boolean get() = stopping != null

    fun request(killProcess: Boolean, userStop: Boolean, requiresKillOnDisconnect: Boolean = true): Request {
        stopEpoch++
        return Request(++revision, killProcess, userStop, requiresKillOnDisconnect).also { current = it }
    }

    /** A transport terminal continues cleanup; it cannot weaken an accepted explicit Stop. */
    fun requestAutomatic(userStop: Boolean): Request? {
        if (current?.requiresKillOnDisconnect == false) return null
        return request(killProcess = true, userStop = userStop)
    }

    fun supersede(): Long {
        current = null
        return ++revision
    }

    fun acceptResume(expectedRevision: Long): Long? =
        if (revision == expectedRevision) supersede() else null

    fun isRunOwner(owner: Long): Boolean = current == null && revision == owner

    fun requestFor(id: Long): Request? = current?.takeIf { it.id == id }

    /** A Stop cancels a waiting attempt permanently; a compatible UI Open does not. */
    fun acceptsConnection(ticket: Long): Boolean = ticket == stopEpoch && !isStopping

    fun owns(id: Long, killOnDisconnect: Boolean): Boolean =
        current?.let { it.id == id && (!it.requiresKillOnDisconnect || killOnDisconnect) } == true

    fun userStopAtDestroy(request: Request): Boolean =
        stopping === request && current?.userStop == true

    /** New Stops join the existing physical destruction, never replace its identity. */
    fun commit(request: Request, killOnDisconnect: Boolean): Request? {
        if (current !== request || request.committed) return null
        if (request.requiresKillOnDisconnect && !killOnDisconnect) {
            supersede()
            return null
        }
        request.committed = true
        return stopping ?: request.also { stopping = it }
    }

    fun destroyed(request: Request, killOnDisconnect: Boolean): Completion {
        if (stopping !== request) return Completion(Effect.NONE, revision)
        stopping = null
        val latest = current
        if (latest == null) return Completion(Effect.RESTART_SERVICE, revision)
        latest.committed = true
        // Only the latest Stop's own flags authorize effects, even when A did the cleanup.
        val effect = if (latest.killProcess && killOnDisconnect) Effect.KILL_PROCESS else Effect.STOPPED
        return Completion(effect, latest.id, latest)
    }

    companion object {
        const val DELAY_MS = 500L
        const val ACTION_FINISH_ACTIVITIES = "com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES"
        const val EXTRA_EXIT_OWNER = "app_exit_owner"

        fun isExitCommand(action: String?, scheme: String?, host: String?): Boolean =
            action == "com.andrerinas.openheadunit.ACTION_EXIT" ||
                (action == "android.intent.action.VIEW" && scheme == "headunit" && host == "exit")
    }
}
