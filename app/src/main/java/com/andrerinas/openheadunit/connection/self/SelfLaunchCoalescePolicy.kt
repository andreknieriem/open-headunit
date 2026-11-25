package com.andrerinas.openheadunit.connection.self

/**
 * Whether a Self Mode launch request should start a launch.
 *
 * `auto-start-self-mode` and an explicit `ACTION_START_SELF_MODE` are two doors to the same room:
 * the setting is guarded inside `HomeFragment`, the intent reaches `AapService` directly, and
 * nothing downstream stopped both firing. On the AA 17.4+ route that is destructive rather than
 * merely wasteful, because there is one launcher, it dials `127.0.0.1:5277`, and the first request
 * to run out of launchers used to report an error that disconnected the session the second one
 * had just established.
 *
 * Hence the two rules. One launch at a time, here, and a launch that failed is not a failure once
 * something has connected. The second is decided by `CommManager.reportLaunchFailure` from the
 * physical arrivals since the launch was accepted, for running out of launchers and for
 * [com.andrerinas.openheadunit.connection.self.SelfLaunchTimeoutPolicy]'s deadline alike, so a
 * connection that formed or ended unobserved still counts.
 */
object SelfLaunchCoalescePolicy {

    /**
     * A second request while one is in flight is a duplicate, not a retry, and a request arriving
     * on a live session has nothing to do.
     */
    fun shouldStart(launchInFlight: Boolean, isConnected: Boolean): Boolean =
        !launchInFlight && !isConnected
}
