package com.andrerinas.openheadunit.connection.wifi.modes.helper

import com.andrerinas.openheadunit.utils.AppLog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch

class NearbySocket : Socket() {
    private var internalInputStream: InputStream? = null
    private var internalOutputStream: OutputStream? = null

    private val inputLatch = CountDownLatch(1)
    private val outputLatch = CountDownLatch(1)

    var inputStreamWrapper: InputStream?
        get() = internalInputStream
        set(value) {
            internalInputStream = value
            if (value != null) {
                AppLog.i("NearbySocket: InputStream is now AVAILABLE. Releasing latch.")
                inputLatch.countDown()
            }
        }

    var outputStreamWrapper: OutputStream?
        get() = internalOutputStream
        set(value) {
            internalOutputStream = value
            if (value != null) outputLatch.countDown()
        }

    override fun isConnected() = true

    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getInputStream(): InputStream {
        AppLog.d("NearbySocket: getInputStream() called")
        return object : InputStream() {
            private fun waitForStream(): InputStream {
                if (inputLatch.count > 0L) {
                    AppLog.i("NearbySocket: Blocking read until InputStream is AVAILABLE via Nearby Payload...")
                }
                inputLatch.await()
                return internalInputStream!!
            }

            override fun read(): Int {
                val b = waitForStream().read()
                return b
            }

            override fun read(b: ByteArray): Int = read(b, 0, b.size)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val readValue = waitForStream().read(b, off, len)
                return readValue
            }
            override fun available(): Int = if (inputLatch.count == 0L) internalInputStream!!.available() else 0
            override fun close() = if (inputLatch.count == 0L) internalInputStream!!.close() else Unit
        }
    }

    override fun getOutputStream(): OutputStream {
        AppLog.d("NearbySocket: getOutputStream() called")
        return object : OutputStream() {
            private fun waitForStream(): OutputStream {
                if (outputLatch.count > 0L) {
                    AppLog.d("NearbySocket: Waiting for outputLatch...")
                }
                outputLatch.await()
                return internalOutputStream!!
            }

            override fun write(b: Int) {
                AppLog.v("NearbySocket: writing 1 byte to pipe")
                waitForStream().write(b)
            }

            override fun write(b: ByteArray) = write(b, 0, b.size)
            override fun write(b: ByteArray, off: Int, len: Int) {
                AppLog.v("NearbySocket: writing $len bytes to pipe")
                waitForStream().write(b, off, len)
                // Force flush since GMS Nearby Stream payloads might buffer a lot
                waitForStream().flush()
            }
            override fun flush() {
                AppLog.v("NearbySocket: flush() called")
                if (outputLatch.count == 0L) internalOutputStream!!.flush()
            }
            override fun close() = if (outputLatch.count == 0L) internalOutputStream!!.close() else Unit
        }
    }
}
