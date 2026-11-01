package com.andrerinas.openheadunit.connection

/**
 * A connect to an endpoint an attempt holds a claim for joins it, so a higher tier cannot kill our
 * own attempt before SSL. A live session is not joined, so a deliberate redial still works.
 * A socket discovery holds to the endpoint is adopted first, since the server is already bound to it.
 */
object SameEndpointConnectPolicy {
    fun joins(inFlight: String?, requested: String?): Boolean =
        inFlight != null && requested != null && inFlight == requested

    /** One spelling per endpoint on every connect path: no IPv6 scope id, no brackets. */
    fun endpoint(host: String, port: Int): String = "${host.substringBefore('%').trim('[', ']')}:$port"
}
