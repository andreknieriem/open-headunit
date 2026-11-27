package com.andrerinas.openheadunit.connection.carplaynative.usb

import java.io.Closeable
import java.io.IOException

/**
 * A blocking byte stream: usbmux TCP, or TLS over one. [recv] returns null on timeout and an empty array at the end of the stream.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/BlockingDuplexByteStream.kt`.
 */
interface BlockingDuplexByteStream : Closeable {
    fun send(data: ByteArray)

    fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray?

    override fun close()
}

/** Opens a usbmux TCP stream to a port on the iPhone. */
fun interface MuxConnector {
    fun connect(port: Int, timeoutMillis: Long): BlockingDuplexByteStream
}

/** Failures of the USB, usbmux and lockdown layers. A message never carries a key or a record. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
}
