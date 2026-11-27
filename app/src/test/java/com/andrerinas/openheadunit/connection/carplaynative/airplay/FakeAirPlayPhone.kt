package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.BinaryPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.codec.Rtsp
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The phone side of the AirPlay server, for JVM tests. It talks to the real sockets, so it holds the wire layout of
 * the server once, in test code. It is the script of record for the rig fake iPhone.
 */
internal class FakeAirPlayPhone(private val host: InetAddress, port: Int) : Closeable {
    class Reply(val status: Int, val body: ByteArray)

    private val control = Socket(host, port).also { it.soTimeout = 3000 }
    private var cipher: ControlCipher? = null
    private var pending = ByteArray(0)
    private var cseq = 0
    private var shared: ByteArray? = null
    private val opened = ArrayList<Closeable>()
    private var eventSocket: Socket? = null
    private var eventCipher: ControlCipher? = null
    private var eventBuffer = ByteArray(0)
    private val eventMessages = ArrayDeque<Rtsp.Request>()
    val client = PairingClient()

    fun request(method: String, path: String, body: ByteArray = ByteArray(0), contentType: String? = null): Reply {
        cseq++
        val head = buildString {
            append("$method $path RTSP/1.0\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII) + body
        control.getOutputStream().write(cipher?.encrypt(head) ?: head)
        control.getOutputStream().flush()
        return readReply()
    }

    private fun readReply(): Reply {
        val buffer = ByteArray(16 * 1024)
        var plain = ByteArray(0)
        while (true) {
            val parsed = Rtsp.parse(plain) as Rtsp.Parsed.Ok
            if (parsed.requests.isNotEmpty()) return parsed.requests[0].let { Reply(it.path.toInt(), it.body) }
            val count = control.getInputStream().read(buffer)
            check(count >= 0) { "the server closed the control connection" }
            pending += buffer.copyOf(count)
            val active = cipher
            if (active == null) { plain += pending; pending = ByteArray(0) } else {
                val decrypted = active.decrypt(pending)!!
                pending = decrypted.rest
                plain += decrypted.data
            }
        }
    }

    fun isClosedByServer(): Boolean = try { control.getInputStream().read() < 0 } catch (_: SocketTimeoutException) { false } catch (_: java.io.IOException) { true }

    fun info(): PlistValue.Dict = BinaryPlist.decode(request("GET", "/info").body) as PlistValue.Dict

    /** Pair-setup, pair-verify and the switch to the control cipher. Returns the pair-verify result for the identity checks. */
    fun pair(identity: AirPlayIdentity): PairingClient.Verified {
        val post = { path: String -> { body: ByteArray -> request("POST", path, body, RtspRoutes.CONTENT_PAIRING).body } }
        client.pairSetup(post("/pair-setup"))
        val verified = client.pairVerify(post("/pair-verify"), identity.publicKey, identity.pairingId)
        shared = verified.shared
        val salt = "Control-Salt".toByteArray()
        cipher = ControlCipher(
            readKey = CarPlayCrypto.hkdfSha512(verified.shared, salt, "Control-Read-Encryption-Key".toByteArray()),
            writeKey = CarPlayCrypto.hkdfSha512(verified.shared, salt, "Control-Write-Encryption-Key".toByteArray()),
        )
        return verified
    }

    /** Pair-verify with no pair-setup first, so the server knows no such controller. True when it passed. */
    fun pairVerifyUnknown(identity: AirPlayIdentity): Boolean = runCatching {
        val post = { body: ByteArray -> request("POST", "/pair-verify", body, RtspRoutes.CONTENT_PAIRING).body }
        PairingTlv.ERROR !in client.pairVerify(post, identity.publicKey, identity.pairingId).m4
    }.getOrDefault(false)

    fun authSetup(): Reply = request("POST", "/auth-setup", byteArrayOf(1) + CarPlayCrypto.x25519Generate().publicKey, RtspRoutes.CONTENT_OCTET)

    private fun plist(value: PlistValue) = BinaryPlist.encode(value)

    private fun setupBody(vararg entries: Pair<String, PlistValue>) = plist(PlistValue.Dict(entries.toList()))

    class Ports(val timing: Int, val event: Int, val keepAlive: Int?)

    /** The session SETUP. [timingPort] is the UDP port of [timingSocket], so the server can ask for the clock. */
    fun setupSession(timingSocket: DatagramSocket?, keepAlive: Boolean = true): Ports {
        val entries = mutableListOf<Pair<String, PlistValue>>(
            "name" to PlistValue.Text("Phone"), "model" to PlistValue.Text("iPhone1,1"),
            "features" to PlistValue.Items(listOf(PlistValue.Text("viewAreas"), PlistValue.Text("iAPChannel"))),
            "keepAliveLowPower" to PlistValue.Bool(keepAlive),
        )
        if (timingSocket != null) entries += "timingPort" to PlistValue.Integer(timingSocket.localPort.toLong())
        val reply = request("SETUP", "/s", setupBody(*entries.toTypedArray()), RtspRoutes.CONTENT_PLIST)
        check(reply.status == 200) { "session SETUP answered ${reply.status}" }
        val dict = BinaryPlist.decode(reply.body) as PlistValue.Dict
        return Ports((dict["timingPort"]!!.asLong!!).toInt(), (dict["eventPort"]!!.asLong!!).toInt(), dict["keepAlivePort"]?.asLong?.toInt())
    }

    fun enabledFeatures(reply: PlistValue.Dict): List<String> = reply["enabledFeatures"]!!.asItems!!.mapNotNull { it.asText }

    fun stream(type: Long, connectionId: Long?, audioType: String? = null, audioFormat: Long? = null): PlistValue.Dict {
        val entries = mutableListOf<Pair<String, PlistValue>>("type" to PlistValue.Integer(type))
        if (connectionId != null) entries += "streamConnectionID" to PlistValue.Integer(connectionId)
        if (audioType != null) entries += "audioType" to PlistValue.Text(audioType)
        if (audioFormat != null) entries += "audioFormat" to PlistValue.Integer(audioFormat)
        return PlistValue.Dict(entries)
    }

    /** The streams SETUP. Returns each answered entry as a dictionary. */
    fun setupStreams(vararg streams: PlistValue.Dict): List<PlistValue.Dict> {
        val reply = request("SETUP", "/s", setupBody("streams" to PlistValue.Items(streams.toList())), RtspRoutes.CONTENT_PLIST)
        check(reply.status == 200) { "streams SETUP answered ${reply.status}" }
        return ((BinaryPlist.decode(reply.body) as PlistValue.Dict)["streams"]!!.asItems!!).map { it as PlistValue.Dict }
    }

    fun record(): Reply = request("RECORD", "/s")

    fun connectEvents(port: Int) {
        val keys = StreamKeys.events(shared!!)
        eventSocket = Socket(host, port).also { it.soTimeout = 3000; opened.add(it) }
        eventCipher = ControlCipher(readKey = keys.writeKey, writeKey = keys.readKey)
    }

    /** The next request the server sent on the event channel. */
    fun nextEvent(): Rtsp.Request {
        val socket = eventSocket!!
        val buffer = ByteArray(16 * 1024)
        while (eventMessages.isEmpty()) {
            val count = socket.getInputStream().read(buffer)
            check(count >= 0) { "the server closed the event channel" }
            eventBuffer += buffer.copyOf(count)
            val decrypted = eventCipher!!.decrypt(eventBuffer)!!
            eventBuffer = decrypted.rest
            plainEvents += decrypted.data
            val parsed = Rtsp.parse(plainEvents) as Rtsp.Parsed.Ok
            plainEvents = parsed.rest
            eventMessages.addAll(parsed.requests)
        }
        return eventMessages.removeFirst()
    }

    private var plainEvents = ByteArray(0)

    fun eventType(message: Rtsp.Request): String = ((BinaryPlist.decode(message.body) as PlistValue.Dict)["type"]!!.asText!!)

    fun answerEvent(message: Rtsp.Request) {
        val reply = "RTSP/1.0 200 OK\r\nCSeq: ${message.headers["cseq"]}\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        eventSocket!!.getOutputStream().write(eventCipher!!.encrypt(reply))
        eventSocket!!.getOutputStream().flush()
    }

    private val screenCounters = HashMap<Socket, Long>()

    fun connectScreen(port: Int): Socket = Socket(host, port).also { opened.add(it); screenCounters[it] = 0 }

    private fun screenHeader(bodySize: Int, opcode: Int) = ByteArray(128).also {
        it[0] = bodySize.toByte(); it[1] = (bodySize shr 8).toByte(); it[2] = (bodySize shr 16).toByte(); it[3] = (bodySize shr 24).toByte()
        it[4] = opcode.toByte()
    }

    fun sendScreenConfig(socket: Socket, avcc: ByteArray) {
        socket.getOutputStream().write(screenHeader(avcc.size, 1) + avcc)
        socket.getOutputStream().flush()
    }

    fun sendScreenFrame(socket: Socket, connectionId: Long, plain: ByteArray, tamper: Boolean = false) {
        val key = StreamKeys.dataStream(shared!!, java.lang.Long.toUnsignedString(connectionId))
        val counter = screenCounters.getValue(socket)
        val header = screenHeader(plain.size + CarPlayCrypto.TAG_SIZE, 0)
        val sealed = CarPlayCrypto.chachaSeal(key, Nonces.counter(counter), plain, header)
        if (tamper) sealed[0] = (sealed[0].toInt() xor 1).toByte() else screenCounters[socket] = counter + 1
        socket.getOutputStream().write(header + sealed)
        socket.getOutputStream().flush()
    }

    fun sendAudio(port: Int, connectionId: Long, pcm: ByteArray, sequence: Int = 1) {
        val key = StreamKeys.dataStream(shared!!, java.lang.Long.toUnsignedString(connectionId))
        val header = byteArrayOf(0x80.toByte(), 96, (sequence shr 8).toByte(), sequence.toByte(), 0, 0, 0, 1, 0, 0, 0, 2)
        val tail = ByteArray(8).also { it[0] = sequence.toByte() }
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        val sealed = CarPlayCrypto.chachaSeal(key, nonce, pcm, header.copyOfRange(4, 12))
        val packet = header + sealed + tail
        DatagramSocket().use { it.send(DatagramPacket(packet, packet.size, host, port)) }
    }

    override fun close() {
        opened.forEach { runCatching { it.close() } }
        runCatching { control.close() }
    }
}
