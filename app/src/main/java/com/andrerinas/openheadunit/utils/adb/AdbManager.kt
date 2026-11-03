package com.andrerinas.openheadunit.utils.adb

import android.content.Context
import android.net.TrafficStats
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object AdbManager {

    private const val ADB_TRAFFIC_STATS_TAG = 0xADB0

    private fun setupCrypto(filesDir: File): AdbCrypto {
        val publicKey = File(filesDir, "adb_public.key")
        val privateKey = File(filesDir, "adb_private.key")

        if (publicKey.exists() && privateKey.exists()) {
            try {
                return AdbCrypto.loadAdbKeyPair(privateKey, publicKey)
            } catch (e: Exception) {
                AppLog.w("AdbManager: Failed to load existing ADB keys, regenerating: ${e.message}")
            }
        }

        val crypto = AdbCrypto.generateAdbKeyPair()
        crypto.saveAdbKeyPair(privateKey, publicKey)
        return crypto
    }

    suspend fun isAdbPortOpen(port: Int = 5555, timeoutMs: Int = 1000): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun exec(context: Context, command: String, port: Int = 5555): Pair<Int, String> = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var adbConnection: AdbConnection? = null
        var stream: AdbStream? = null

        try {
            TrafficStats.setThreadStatsTag(ADB_TRAFFIC_STATS_TAG)
            val crypto = setupCrypto(context.filesDir)

            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 4000)

            adbConnection = AdbConnection.create(socket, crypto)
            adbConnection.connect()

            // Try exec: first (clean execution without PTY echo)
            var usedShell = false
            stream = try {
                adbConnection.open("exec:$command")
            } catch (e: Exception) {
                AppLog.d("AdbManager: exec: service failed, falling back to shell: (${e.message})")
                usedShell = true
                adbConnection.open("shell:")
            }

            val activeStream = stream ?: throw IOException("Failed to open ADB stream")

            if (usedShell) {
                // In interactive shell, write command and exit
                activeStream.write("$command\nexit\n")
            }

            val outputBuilder = StringBuilder()
            // Safety net: a command that leaves the exec stream open (e.g. a daemon launched without
            // proper detachment) would otherwise be read until the process dies — potentially never,
            // freezing whatever UI is waiting on the result. Bound the read to a hard timeout.
            val maxReadMs = 15_000L
            val readStart = System.currentTimeMillis()
            // Read until read() reports the stream closed and drained, not until isClosed flips:
            // output that arrived together with the close is still queued at that point.
            while (true) {
                if (System.currentTimeMillis() - readStart > maxReadMs) {
                    AppLog.w("AdbManager: read timed out after ${maxReadMs}ms for '$command' — closing stream.")
                    break
                }
                try {
                    val data = activeStream.read()
                    if (data.isNotEmpty()) {
                        outputBuilder.append(String(data, AdbProtocol.UTF_8))
                    }
                } catch (e: Exception) {
                    break
                }
            }

            var resultStr = outputBuilder.toString().trim()
            if (usedShell) {
                // Strip echo lines and prompt
                resultStr = resultStr.lines()
                    .map { it.trim() }
                    .filter { line ->
                        line.isNotEmpty() && 
                        !line.startsWith(command) && 
                        !line.startsWith("exit") && 
                        !line.matches(Regex(".*:[/#$] *"))
                    }
                    .joinToString("\n")
            }

            AppLog.i("AdbManager: Command '$command' executed successfully via Self-ADB. Output:\n$resultStr")
            Pair(0, resultStr)
        } catch (e: Exception) {
            AppLog.e("AdbManager: Self-ADB execution failed for '$command': ${e.message}", e)
            Pair(-1, e.message ?: "Self-ADB execution error")
        } finally {
            try { stream?.close() } catch (_: Exception) {}
            try { adbConnection?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
