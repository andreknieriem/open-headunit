package com.andrerinas.openheadunit.aap

internal interface AapMessageHandler {
    @Throws(HandleException::class)
    fun handle(message: AapMessage)

    /** A fully consumed, identified DATA was discarded before normal handler delivery. */
    fun onDroppedMediaData(channel: Int) {}

    class HandleException internal constructor(cause: Throwable) : Exception(cause)
}
