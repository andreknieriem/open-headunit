package com.andrerinas.openheadunit.aap

internal interface AapMessageHandler {
    @Throws(HandleException::class)
    fun handle(message: AapMessage)

    /** A fully consumed, identified DATA was discarded before normal handler delivery. */
    fun onDroppedMediaData(channel: Int) {}

    /** Cancel session-owned display work without waiting on its executor. */
    fun close() {}

    class HandleException internal constructor(cause: Throwable) : Exception(cause)
}
