package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink

import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.utils.AppLog
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * A root-owned bridge to `/dev/auto_serial`, the pseudo-terminal FYT's `blink` daemon relays the
 * module's Android Auto RFCOMM channel through.
 *
 * Where the node is app-openable, as it is for the stock client, [openDirect] runs the copy loop
 * as this app. Otherwise it is reached through `su` ([open]): one shell checks the stock client, puts the
 * terminal in raw mode without echo (as shipped it echoes every line back to the daemon, which
 * logs each one as an unsupported command), then copies the port to our stdin and our stdout to
 * the port. Lines are dispatched to [onLine] on a reader thread.
 *
 * The stock `com.syu.carlink` must not be running: two readers on one terminal split the phone's
 * lines between them.
 */
class BlinkAutoSerialChannel internal constructor(
    private val process: Process,
    private val onLine: (String) -> Unit,
    private val onEnded: (String) -> Unit,
    private val cleanupScheduler: ((() -> Unit) -> Unit) = ::launchBridgeCleanup,
) {
    companion object {
        const val PORT = "/dev/auto_serial"

        private const val PID_TAG = "BLINKBRIDGE_PID "
        private const val ERR_TAG = "BLINKBRIDGE_ERR "

        // Toybox's `stty raw` on this FYT build leaves IUCLC, ICRNL, IXON/IXOFF, and OPOST on.
        // The captured result was lowercase `aa`/`jh...`, extra newlines, and would also transform
        // commands written back to blink. Name every transformation explicitly instead.
        internal const val TTY_MODE_COMMAND =
            "stty raw -echo -iuclc -icrnl -inlcr -igncr -ixon -ixoff -opost"

        /** Exit codes of [STOCK_CLIENT_GUARD], so the carrier can name the refusal. */
        internal const val EXIT_STOCK_ENABLED = 7
        internal const val EXIT_STOCK_RUNNING = 8
        internal const val EXIT_UNVERIFIED = 9

        /**
         * What a bridge that ended before opening the port was refused for.
         *
         * @param scriptSpoke whether the script printed anything. Every failure path in it does,
         *   so silence means it never ran: `su` was denied or is missing.
         */
        internal fun refusalFor(exitCode: Int?, scriptSpoke: Boolean): BlinkRefusal = when {
            exitCode == EXIT_STOCK_ENABLED -> BlinkRefusal.STOCK_CLIENT_ENABLED
            exitCode == EXIT_STOCK_RUNNING -> BlinkRefusal.STOCK_CLIENT_RUNNING
            exitCode == EXIT_UNVERIFIED -> BlinkRefusal.OWNERSHIP_UNVERIFIED
            !scriptSpoke -> BlinkRefusal.ROOT_DENIED
            else -> BlinkRefusal.BRIDGE_FAILED
        }

        internal val STOCK_CLIENT_GUARD = """
            STOCK_PACKAGE=com.syu.carlink
            DISABLED_PACKAGE=${'$'}(pm list packages -d "${'$'}STOCK_PACKAGE" 2>/dev/null)
            PM_STATUS=${'$'}?
            if [ "${'$'}PM_STATUS" -ne 0 ]; then
                echo "${ERR_TAG}cannot read whether the stock client is disabled"
                exit $EXIT_UNVERIFIED
            fi
            if [ "${'$'}DISABLED_PACKAGE" != "package:${'$'}STOCK_PACKAGE" ]; then
                echo "${ERR_TAG}stock client must be disabled before opening $PORT"
                exit $EXIT_STOCK_ENABLED
            fi
            RUNNING_PIDS=${'$'}(pidof "${'$'}STOCK_PACKAGE" 2>/dev/null)
            PIDOF_STATUS=${'$'}?
            if [ "${'$'}PIDOF_STATUS" -eq 0 ] && [ -n "${'$'}RUNNING_PIDS" ]; then
                echo "${ERR_TAG}stock client is still running (${ '$' }RUNNING_PIDS)"
                exit $EXIT_STOCK_RUNNING
            fi
            if [ "${'$'}PIDOF_STATUS" -ne 1 ] || [ -n "${'$'}RUNNING_PIDS" ]; then
                echo "${ERR_TAG}cannot verify that the stock client is stopped"
                exit $EXIT_UNVERIFIED
            fi
        """.trimIndent()

        /** The copy loop itself, which needs no root where the node is app-openable. */
        internal val BRIDGE_BODY = """
            P=${'$'}(readlink -f $PORT)
            if [ ! -c "${'$'}P" ]; then echo "${ERR_TAG}$PORT is not a character device (${'$'}P)"; exit 3; fi
            OLD_MODE=${'$'}(stty -g < "${'$'}P") || { echo "${ERR_TAG}cannot read tty mode on ${'$'}P"; exit 4; }
            READER_PID=
            cleanup() {
                trap - EXIT HUP INT TERM
                if [ -n "${'$'}READER_PID" ]; then
                    kill "${'$'}READER_PID" 2>/dev/null
                    wait "${'$'}READER_PID" 2>/dev/null
                fi
                stty "${'$'}OLD_MODE" < "${'$'}P" 2>/dev/null
            }
            trap cleanup EXIT HUP INT TERM
            $TTY_MODE_COMMAND < "${'$'}P" || { echo "${ERR_TAG}stty failed on ${'$'}P"; exit 5; }
            exec 3<>"${'$'}P" || { echo "${ERR_TAG}cannot open ${'$'}P"; exit 6; }
            cat <&3 &
            READER_PID=${'$'}!
            echo "$PID_TAG${'$'}READER_PID"
            cat >&3
        """.trimIndent()

        /** The root bridge: the stock-client guard, then the copy loop. */
        internal val BRIDGE_SCRIPT = STOCK_CLIENT_GUARD + "\n" + BRIDGE_BODY

        /** Starts the root bridge. Throws if `su` cannot be run at all. */
        fun open(onLine: (String) -> Unit, onEnded: (String) -> Unit,
                 admission: ConnectionAdmission): BlinkAutoSerialChannel {
            if (!admission.run {}) throw IOException("BLINK connection authority withdrawn")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", BRIDGE_SCRIPT))
            return BlinkAutoSerialChannel(process, onLine, onEnded).also { it.start() }
        }

        /**
         * Starts the same copy loop as this app, with no root, the way the stock client opens the
         * node. Only the caller can check the stock client here, so it must have. A node this app
         * cannot open or set the mode of ends the channel before [portOpened], and the caller falls
         * back to [open]. Throws if `sh` cannot be run at all.
         */
        fun openDirect(onLine: (String) -> Unit, onEnded: (String) -> Unit,
                       admission: ConnectionAdmission): BlinkAutoSerialChannel {
            if (!admission.run {}) throw IOException("BLINK connection authority withdrawn")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", BRIDGE_BODY))
            return BlinkAutoSerialChannel(process, onLine, onEnded).also { it.start() }
        }
    }

    @Volatile
    var isFinished = false
        private set

    @Volatile
    private var readerPid: String? = null

    /** Whether the bridge got as far as reading the port. */
    @Volatile
    var portOpened = false
        private set

    /** Whether the script printed any line of its own. */
    @Volatile
    var scriptSpoke = false
        private set

    /** The bridge's exit code once it has ended, if it could be read. */
    @Volatile
    var exitCode: Int? = null
        private set

    private val sink: OutputStream = process.outputStream
    private val writeLock = Any()

    private fun start() {
        Thread({ pump() }, "BlinkAutoSerial-reader").apply { isDaemon = true }.start()
        Thread({
            runCatching {
                process.errorStream.bufferedReader().forEachLine { AppLog.w("NativeAA: [BLINK] su: $it") }
            }
        }, "BlinkAutoSerial-stderr").apply { isDaemon = true }.start()
    }

    private fun pump() {
        var reason = "the bridge ended"
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.US_ASCII), 8192)
            while (true) {
                val raw = reader.readLine() ?: break
                val line = raw.trim('\r', '\n', ' ', '\u0000')
                if (line.isEmpty()) continue
                when {
                    line.startsWith(PID_TAG) -> {
                        readerPid = line.removePrefix(PID_TAG).trim()
                        scriptSpoke = true
                        portOpened = true
                        AppLog.i("NativeAA: [BLINK] bridge up on $PORT (reader pid $readerPid)")
                    }
                    // Not logged here: a refused bridge repeats on every retry, and the carrier logs
                    // each distinct refusal once.
                    line.startsWith(ERR_TAG) -> {
                        scriptSpoke = true
                        reason = line.removePrefix(ERR_TAG)
                    }
                    // A closed channel's reader may still be draining; its lines belong to no one.
                    else -> if (!isFinished) onLine(line)
                }
            }
        } catch (e: IOException) {
            if (!isFinished) reason = "read failed: ${e.message}"
        } catch (e: Exception) {
            reason = "reader crashed: ${e.javaClass.simpleName}: ${e.message}"
            AppLog.e("NativeAA: [BLINK] $reason", e)
        } finally {
            // stdout closes just before the shell exits; wait briefly so the refusal can be named.
            val exit = if (waitForProcess(process, 1_000)) runCatching { process.exitValue() }.getOrNull() else null
            exitCode = exit
            if (!isFinished) {
                isFinished = true
                onEnded(reason + (exit?.let { " (exit $it)" } ?: ""))
            }
        }
    }

    /** Sends one complete WPP frame to the phone. */
    @Throws(IOException::class)
    fun sendFrame(frame: ByteArray) {
        if (isFinished) throw IOException("the $PORT bridge is closed")
        val line = BlinkAutoLine.encode(frame)
        synchronized(writeLock) {
            sink.write(line.toByteArray(Charsets.US_ASCII))
            sink.flush()
        }
        AppLog.d("NativeAA: [BLINK] [TX] ${line.trimEnd()}")
    }

    fun close() {
        isFinished = true
        readerPid = null
        cleanupScheduler { stopBridgeProcess(process, sink) }
    }

    /**
     * Closes on the calling thread and says whether the shell ended by itself, running its trap:
     * that trap is what stops the background reader and restores the terminal mode. Blocking.
     *
     * @return false if the shell had to be killed, when its reader may outlive it
     */
    fun closeAndAwait(timeoutMs: Long): Boolean {
        isFinished = true
        readerPid = null
        runCatching { sink.close() }
        // 128 and up is a signal, such as a kill from outside, which skips the trap.
        if (waitForProcess(process, timeoutMs)) return runCatching { process.exitValue() < 128 }.getOrDefault(false)
        runCatching { process.destroy() }
        waitForProcess(process, timeoutMs)
        return false
    }

    /**
     * One handshake's view of the channel. Frames from the phone are queued by [deliver]; bytes
     * written to [output] go out a whole frame at a time on flush. Closing it ends only the view.
     */
    class FrameStream(private val sendFrame: (ByteArray) -> Unit) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var head: ByteArray? = null
        private var headOffset = 0

        @Volatile
        var closed = false
            private set

        private val END = ByteArray(0)

        fun deliver(frame: ByteArray) {
            if (!closed) queue.put(frame)
        }

        fun close() {
            synchronized(pendingOut) {
                if (closed) return
                closed = true
                pendingOut.reset()
            }
            queue.put(END)
        }

        val input: InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (true) {
                    val current = head
                    if (current != null && headOffset < current.size) {
                        val take = minOf(len, current.size - headOffset)
                        System.arraycopy(current, headOffset, b, off, take)
                        headOffset += take
                        return take
                    }
                    head = null
                    headOffset = 0
                    if (closed && queue.isEmpty()) return -1
                    val next = try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                    if (next === END) return -1
                    head = next
                }
            }

            override fun available(): Int = (head?.size ?: 0) - headOffset

            override fun close() = this@FrameStream.close()
        }

        private val pendingOut = ByteArrayOutputStream()

        val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                synchronized(pendingOut) {
                    if (closed) throw IOException("the BLINK handshake stream is closed")
                    pendingOut.write(b)
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(pendingOut) {
                    if (closed) throw IOException("the BLINK handshake stream is closed")
                    pendingOut.write(b, off, len)
                }
            }

            override fun flush() {
                // Split under the lock, write after releasing it: a write blocks when `blink`
                // stops draining the pty, and close() runs on the reader thread, which must never
                // wait behind it. closed is checked before every frame, so once close() returns no
                // new frame starts; at most one already in the pipe completes.
                val frames = synchronized(pendingOut) {
                    if (closed) throw IOException("the BLINK handshake stream is closed")
                    val bytes = pendingOut.toByteArray()
                    val (whole, used) = splitFrames(bytes)
                    pendingOut.reset()
                    if (used < bytes.size) pendingOut.write(bytes, used, bytes.size - used)
                    whole
                }
                for (frame in frames) {
                    if (closed) throw IOException("the BLINK handshake stream is closed")
                    sendFrame(frame)
                }
            }

            override fun close() = this@FrameStream.close()
        }
    }
}

/** Runs blocking bridge teardown away from lifecycle callers. */
internal fun launchBridgeCleanup(cleanup: () -> Unit) {
    Thread(cleanup, "BlinkAutoSerial-cleanup").apply { isDaemon = true }.start()
}

/** Closes the bridge's stdin first so its shell trap can stop the reader and restore the tty. */
internal fun stopBridgeProcess(process: Process, sink: OutputStream, timeoutSeconds: Long = 3) {
    runCatching { sink.close() }
    val exited = waitForProcess(process, timeoutSeconds * 1_000)
    if (!exited) {
        runCatching { process.destroy() }
        waitForProcess(process, timeoutSeconds * 1_000)
    }
}

/** API-16-compatible bounded wait; Process.waitFor(timeout, unit) is unavailable on old Android. */
internal fun waitForProcess(process: Process, timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
    while (true) {
        try {
            process.exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
}

/** Splits [bytes] into whole WPP frames; returns them and how many bytes they used. */
internal fun splitFrames(bytes: ByteArray): Pair<List<ByteArray>, Int> {
    val out = ArrayList<ByteArray>()
    var at = 0
    while (bytes.size - at >= WppFraming.HEADER_SIZE) {
        val size = WppFraming.decodePayloadSize(bytes.copyOfRange(at, at + WppFraming.HEADER_SIZE))
        val end = at + WppFraming.HEADER_SIZE + size
        if (end > bytes.size) break
        out.add(bytes.copyOfRange(at, end))
        at = end
    }
    return out to at
}
