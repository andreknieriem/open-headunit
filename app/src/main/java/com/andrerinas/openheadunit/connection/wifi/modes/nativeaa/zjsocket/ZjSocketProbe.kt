package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct.HctLocalSocket
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ExternalBtPolicy
import java.io.File

/**
 * The connect answer for the ZJ socket. [answer] is the only read a UI path may make and never
 * connects; [measure] connects, so it runs on the IO dispatcher at bring-up and nowhere else.
 */
object ZjSocketProbe {

    @Volatile private var verdict: ZjSocketPolicy.Verdict? = null
    @Volatile private var connectedPath: String? = null

    private fun exists(path: String): Boolean = try { File(path).exists() } catch (e: Exception) { false }

    /** False with no socket file, else the cached answer; null means not measured yet. */
    fun answer(): Boolean? {
        if (ExternalBtPolicy.ZJ_SOCKET_PATHS.none { exists(it) }) return false
        return ZjSocketPolicy.cachedAnswer(verdict, SystemClock.elapsedRealtime())
    }

    /** The socket the carrier dials: the one that connected, else the first present, else the first. */
    fun path(): String = connectedPath
        ?: ExternalBtPolicy.ZJ_SOCKET_PATHS.firstOrNull { exists(it) }
        ?: ExternalBtPolicy.ZJ_SOCKET_PATHS.first()

    /** Connects once per path, closes at once, keeps the answer and logs one line. */
    fun measure(): ZjSocketPolicy.Probe {
        var detail: String? = null
        val result = ZjSocketPolicy.probe(ExternalBtPolicy.ZJ_SOCKET_PATHS, ::exists) { path ->
            val attempt = ZjSocketConnector.connect(path)
            detail = attempt.detail
            attempt.socket?.let { HctLocalSocket.close(it) }
            attempt.outcome
        }
        verdict = ZjSocketPolicy.Verdict(ZjSocketPolicy.connects(result.outcome), SystemClock.elapsedRealtime())
        connectedPath = result.path.takeIf { ZjSocketPolicy.connects(result.outcome) }
        val shown = result.path ?: ExternalBtPolicy.ZJ_SOCKET_PATHS.first()
        AppLog.i("NativeAA: [ZJ] probe $shown: ${result.outcome}" + (detail?.let { " ($it)" } ?: ""))
        return result
    }

    /** A fresh arming keeps a positive answer and asks a negative one again. */
    fun beginArming() {
        if (!ZjSocketPolicy.keptAtArming(verdict)) forgetNegative()
    }

    fun forgetNegative() {
        if (verdict?.connects == false) verdict = null
    }
}
