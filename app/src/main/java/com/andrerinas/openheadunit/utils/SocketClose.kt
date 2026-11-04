package com.andrerinas.openheadunit.utils

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

// Below API 19 a socket is not a Closeable, so Kotlin's `use` throws IncompatibleClassChangeError,
// which no catch (Exception) stops. These close it through the class instead.

inline fun <T> Socket.useSocket(block: (Socket) -> T): T =
    try { block(this) } finally { try { close() } catch (_: IOException) { } }

inline fun <T> ServerSocket.useSocket(block: (ServerSocket) -> T): T =
    try { block(this) } finally { try { close() } catch (_: IOException) { } }
