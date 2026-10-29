package com.andrerinas.openheadunit.connection

/** Serialized by CommManager's lifecycle lock; Android effects run on Main, outside that lock. */
internal class AppExitPolicy {
    class Request internal constructor(
        val id: Long,
        val killProcess: Boolean,
        val userStop: Boolean,
        val requiresKillOnDisconnect: Boolean,
    )
    enum class Completion { NONE, STOPPED, KILL_PROCESS, RESTART_SERVICE }

    private var nextId = 0L
    private var current: Request? = null
    private var stopping: Request? = null
    private var destructionCompleted = false
    val isStopping: Boolean get() = stopping != null

    fun request(killProcess: Boolean, userStop: Boolean, requiresKillOnDisconnect: Boolean = true): Request? {
        // Once destruction is committed, another queued Stop cannot adopt its request or
        // restore permission revoked by a reopen. Its own request is explicitly rejected.
        if (isStopping) return null
        val request = Request(++nextId, killProcess, userStop, requiresKillOnDisconnect)
        current = request
        destructionCompleted = false
        return request
    }

    fun supersede() {
        current = null
    }

    /** Call only at accepted attempt publication, never for an unaccepted candidate. */
    fun acceptConnection(): Boolean {
        if (isStopping) return false
        supersede()
        return true
    }

    fun owns(id: Long, killOnDisconnect: Boolean): Boolean =
        current?.let { it.id == id && (!it.requiresKillOnDisconnect || killOnDisconnect) } == true

    fun userStopAtDestroy(request: Request): Boolean =
        stopping === request && current === request && request.userStop

    fun commit(request: Request, killOnDisconnect: Boolean): Boolean {
        if (current !== request || isStopping || destructionCompleted) return false
        if (request.requiresKillOnDisconnect && !killOnDisconnect) {
            supersede()
            return false
        }
        stopping = request
        return true
    }

    fun destroyed(request: Request, killOnDisconnect: Boolean): Completion {
        if (stopping !== request || destructionCompleted) return Completion.NONE
        destructionCompleted = true
        if (current !== request) {
            stopping = null
            return Completion.RESTART_SERVICE
        }
        // sendBroadcast is asynchronous: the old activities still owe their finish after
        // service cleanup. Keep only that permission until a launch/attempt supersedes it.
        if (request.killProcess && killOnDisconnect) return Completion.KILL_PROCESS
        stopping = null
        return Completion.STOPPED
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
