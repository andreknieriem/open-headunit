package carrierproof

import android.net.LocalSocket
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink.*
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct.HctLocalSocket
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.*
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket.*
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.net.SocketAddress

private val results = mutableListOf<String>()
private var failed = 0
private fun case(name: String, action: () -> Unit) {
    try { action(); results += "{\"case\":\"$name\",\"passed\":true}"; println("PASS $name") }
    catch (error: Throwable) {
        failed++; results += "{\"case\":\"$name\",\"passed\":false}"
        println("FAIL $name: ${error.message}"); error.printStackTrace()
    }
}

private fun zbt(mode: String) = runBlocking {
    ZbtDaemonReachability.forget()
    var allowed = mode != "denied"
    var commandAllowed = true
    var running = true
    var connects = 0
    val sinks = mutableListOf<ByteArrayOutputStream>()
    val admission = ConnectionAdmission { action -> if (!allowed) false else { action(); true } }
    val commandAdmission = ConnectionAdmission { action -> if (!commandAllowed) false else { action(); true } }
    val carrier = ZbtAaCarrier(
        admission = admission,
        serve = { error("no phone is present") }, isRunning = { running }, isFinishedForSession = { false },
        isSessionConnected = { false }, isSettling = { false }, isHandshakeInFlight = { false },
        mayServeHandshake = { false }, onPhoneEvidence = {},
        now = { SystemClock.current }, elapsedNow = { SystemClock.current },
        openChannel = { opening, control, rfcomm ->
            val sink = ByteArrayOutputStream().also { sinks.add(it) }
            var firstRead = true
            val frame = ZbtFraming.encodeFrame(byteArrayOf(), ZbtMessages.INIT_INFO)
            val input = object : ByteArrayInputStream(if (mode in listOf("wake", "save-wake", "accepted")) frame else byteArrayOf()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (firstRead) {
                        firstRead = false
                        if (mode == "wake") allowed = false
                        if (mode == "save-wake") commandAllowed = false
                        if (mode in listOf("wake", "save-wake", "accepted")) SystemClock.current += 8_001
                    }
                    val count = super.read(b, off, len)
                    if (count == -1) {
                        if (mode == "reopen") allowed = false else running = false
                    }
                    return count
                }
            }
            ZbtByteChannel.open(opening = opening, onControlFrame = control, onRfcommData = rfcomm,
                socketFactory = { object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) {
                        connects++
                        if (mode == "connect-return") allowed = false
                        if (mode == "denied" || connects > 1) running = false
                    }
                    override fun setSoTimeout(timeout: Int) {}
                    override fun getInputStream(): InputStream = input
                    override fun getOutputStream(): OutputStream = sink
                    override fun close() {}
                } })
        })
    fun messages(): List<Int> = sinks.flatMap { sink ->
        val bytes = sink.toByteArray()
        var cursor = 0
        buildList {
            while (cursor < bytes.size) {
                val header = bytes.copyOfRange(cursor, cursor + ZbtFraming.HEADER_SIZE)
                add(ZbtFraming.decodeMsgId(header))
                cursor += ZbtFraming.HEADER_SIZE + ZbtFraming.decodeBodySize(header)
            }
        }
    }
    try {
        SystemClock.current = 100_000
        if (mode in listOf("wake", "save-wake", "accepted")) carrier.requestWake(true, admission = commandAdmission)
        withTimeout(5_000) { carrier.run() }
        val ids = messages()
        check(connects == if (mode == "denied") 0 else 1) { "daemon connects=$connects" }
        if (mode == "connect-return") check(ids.isEmpty()) { "revoked RequestInit=$ids" }
        if (mode in listOf("wake", "save-wake")) check(ZbtMessages.REQUEST_RECONN !in ids) { "revoked queued reconnect=$ids" }
        if (mode == "accepted") check(ids.count { it == ZbtMessages.REQUEST_RECONN } == 1) { "accepted wake missing: $ids" }
        check(!ZbtDaemonReachability.carrierWantsClient()) { "carrier claim leaked" }
    } finally { carrier.close(); ZbtDaemonReachability.forget() }
}

private class FinishedProcess : Process() {
    var closed = 0
    private val sink = object : ByteArrayOutputStream() { override fun close() { closed++ } }
    override fun getOutputStream(): OutputStream = sink
    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun waitFor() = 0
    override fun exitValue() = 0
    override fun destroy() { closed++ }
}

private fun blink(mode: String) = runBlocking {
    var allowed = mode != "denied"
    var running = true
    var directOpens = 0
    var rootOpens = 0
    val processes = mutableListOf<FinishedProcess>()
    val admission = ConnectionAdmission { action -> if (!allowed) false else { action(); true } }
    fun channel(onLine: (String) -> Unit, onEnded: (String) -> Unit, finished: Boolean): BlinkAutoSerialChannel {
        val process = FinishedProcess().also { processes.add(it) }
        return BlinkAutoSerialChannel(process, onLine, onEnded, cleanupScheduler = { it() }).also {
            BlinkAutoSerialChannel::class.java.getDeclaredField("isFinished").apply { isAccessible = true }.set(it, finished)
            BlinkAutoSerialChannel::class.java.getDeclaredField("portOpened").apply { isAccessible = true }.set(it, !finished)
        }
    }
    val carrier = BlinkAaCarrier(
        admission = admission,
        serve = { error("no phone is present") }, isRunning = { running },
        isFinishedForSession = { running = false; false }, mayServeHandshake = { false }, onPhoneEvidence = {},
        stockClient = { if (mode == "stock-return") allowed = false; StockCarLink.State.DISABLED },
        openDirect = { line, ended ->
            directOpens++
            if (mode == "fallback") allowed = false
            channel(line, ended, finished = mode == "fallback")
        },
        openRoot = { line, ended -> rootOpens++; running = false; channel(line, ended, finished = false) })
    try {
        // Accepted open ends after its first live iteration; no phone or shell runs here.
        val work = launch { carrier.run() }
        repeat(20) { if (processes.isNotEmpty() && mode != "fallback") running = false; delay(1) }
        withTimeout(5_000) { work.join() }
        val expectedDirect = if (mode in listOf("accepted", "fallback")) 1 else 0
        check(directOpens == expectedDirect && rootOpens == 0) {
            "BLINK shell effects: direct=$directOpens root=$rootOpens expectedDirect=$expectedDirect"
        }
        check(processes.all { it.closed > 0 }) { "opened shell escaped local cleanup" }
    } finally { carrier.close() }
}

private fun zj(mode: String) {
    LocalSocket.reset()
    var allowed = mode != "denied"
    val admission = ConnectionAdmission { action -> if (!allowed) false else { action(); true } }
    if (mode == "worker") LocalSocket.onCreate = { allowed = false }
    if (mode == "connect-return") LocalSocket.onConnect = { allowed = false }
    val attempt = ZjSocketConnector.connect("/fixture/unused", admission)
    try {
        val expected = if (mode in listOf("accepted", "connect-return")) 1 else 0
        check(LocalSocket.connects == expected) { "LocalSocket connects=${LocalSocket.connects} expected=$expected" }
        check((attempt.socket != null) == (mode == "accepted")) { "revoked socket transferred to carrier" }
        if (mode != "accepted") check(LocalSocket.created == LocalSocket.closes) { "revoked socket leaked" }
    } finally { attempt.socket?.let(HctLocalSocket::close); LocalSocket.reset() }
}

fun main() {
    for (mode in listOf("denied", "connect-return", "reopen", "wake", "save-wake", "accepted"))
        case("Native.ZBT.$mode") { zbt(mode) }
    for (mode in listOf("denied", "stock-return", "fallback", "accepted"))
        case("Native.BLINK.$mode") { blink(mode) }
    for (mode in listOf("denied", "worker", "connect-return", "accepted"))
        case("Native.ZJ.$mode") { zj(mode) }
    println("RESULTS=[${results.joinToString(",")}]")
    check(failed == 0) { "$failed Native carrier authority cases failed" }
}
