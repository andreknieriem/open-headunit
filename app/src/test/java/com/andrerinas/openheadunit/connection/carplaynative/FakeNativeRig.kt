package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayStorage
import com.andrerinas.openheadunit.connection.carplaynative.airplay.RecordingAudioSink
import com.andrerinas.openheadunit.connection.carplaynative.airplay.RecordingVideoSink
import com.andrerinas.openheadunit.connection.carplaynative.airplay.StubSigner
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2Csm
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2Frame
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2Link
import com.andrerinas.openheadunit.connection.carplaynative.usb.BlockingDuplexByteStream
import com.andrerinas.openheadunit.connection.carplaynative.usb.EthernetIpv6
import com.andrerinas.openheadunit.connection.carplaynative.usb.EthernetLink
import com.andrerinas.openheadunit.connection.carplaynative.usb.FakeIphone
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneModeAnswer
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPipe
import com.andrerinas.openheadunit.connection.carplaynative.usb.TunProbePacket
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxCodec
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What the fake iPhone read from the 0x4301: where AirPlay listens and what the head unit called itself. */
internal class StartSeen(val address: String, val port: Int, val deviceIdentifier: String, val publicKey: String)

/** The iPhone side of iAP2 on the carkit stream: a real [Iap2Link] and the scripted messages of the recorded start. */
internal class FakeIap2Phone(private val stream: BlockingDuplexByteStream, private val availabilities: Int = 1) {
    val seen = CopyOnWriteArrayList<Int>()
    val starts = LinkedBlockingQueue<StartSeen>()
    private val challenge = ByteArray(32) { it.toByte() }

    private fun now() = System.nanoTime() / 1_000_000

    private fun script(f: Iap2Frame): List<Iap2Frame> {
        seen += f.messageId
        return when (f.messageId) {
            0x1D01 -> listOf(Iap2Csm.build(0x1D02), Iap2Csm.build(0xAA00))
            0xAA01 -> listOf(Iap2Csm.build(0xAA02) { bytes(0, challenge) })
            0xAA03 -> listOf(Iap2Csm.build(0xAA05))
            0x6800 -> List(availabilities) { Iap2Csm.build(0x4300) { group(0) { u8(0, 1); string(1, "usb-1") }; group(1) { u8(0, 0) } } }
            0x4301 -> { starts.add(parseStart(f)); emptyList() }
            else -> emptyList()
        }
    }

    private fun parseStart(f: Iap2Frame): StartSeen {
        val address = Iap2Csm.readString(Iap2Csm.parseParams(f.first(0)!!).first().payload)
        val port = f.first(2)!!.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
        return StartSeen(address, port, Iap2Csm.readString(f.first(3)!!), Iap2Csm.readString(f.first(4)!!))
    }

    fun run() {
        val link = Iap2Link()
        val framer = Iap2Csm.Framer()
        link.start(false, now())
        var up = false
        while (true) {
            val chunk = try { stream.recv(16 * 1024, 50) } catch (_: IOException) { return }
            val t = now()
            if (chunk != null) { if (chunk.isEmpty()) return; link.feed(chunk, t) }
            link.advanceTime(t)
            if (!up && link.state == Iap2Link.State.Normal) {
                up = true
                link.send(Iap2Link.CONTROL_SESSION_ID, Iap2Csm.build(0x1D00).encode(), t)
            }
            while (true) {
                val event = link.pollEvent() ?: break
                if (event is Iap2Link.Event.Dead) return
                if (event is Iap2Link.Event.Control) for (f in framer.offer(event.bytes)) for (reply in script(f)) link.send(Iap2Link.CONTROL_SESSION_ID, reply.encode(), t)
            }
            val out = link.takeOutput()
            if (out.isNotEmpty()) try { stream.send(out) } catch (_: IOException) { return }
        }
    }
}

internal class FakeEthernet(private val order: MutableList<String>) : EthernetLink {
    private val inbound = LinkedBlockingQueue<ByteArray>()
    val sent = CopyOnWriteArrayList<ByteArray>()
    @Volatile var closed = false

    fun deliver(frame: ByteArray) { inbound.add(frame) }

    override fun recv(timeoutMs: Long): ByteArray? = if (closed) null else inbound.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun send(frame: ByteArray, timeoutMs: Int): Boolean { sent.add(frame); return true }

    override fun close() { if (!closed) { closed = true; order += "ncm closed" } }
}

internal class FakeTunPort(private val order: MutableList<String>, private val probe: () -> String) : TunPort {
    private val written = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false

    override fun read(buffer: ByteArray): Int {
        while (!closed) {
            val next = written.poll(20, TimeUnit.MILLISECONDS) ?: continue
            next.copyInto(buffer)
            return next.size
        }
        return -1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {}

    override fun close() { if (!closed) { closed = true; order += "tun closed (airplay ${probe()})" } }
}

internal class FakeTunnel(private val order: MutableList<String>, private val probe: () -> String) : TunnelControl {
    @Volatile var dummyUp = false
    @Volatile var prepared = true
    /** Holds the session thread in the consent check, the last step before the arbiter. */
    @Volatile var holdPrepared: java.util.concurrent.CountDownLatch? = null
    /** Holds the close in its tunnel step. */
    @Volatile var holdStop: java.util.concurrent.CountDownLatch? = null
    val preparedCalls = AtomicInteger()
    val starts = AtomicInteger()
    val stops = AtomicInteger()

    override fun isDummyVpnUp() = dummyUp
    /** Another owner (the tunnel check) holds the tunnel. */
    @Volatile var carPlayUp = false
    override fun isCarPlayTunRunning() = carPlayUp
    override fun isPrepared(): Boolean {
        preparedCalls.incrementAndGet()
        holdPrepared?.await(10, TimeUnit.SECONDS)
        return prepared
    }
    override fun start(onReady: (TunPort) -> Unit, onFailed: (String) -> Unit) { starts.incrementAndGet(); onReady(FakeTunPort(order, probe)) }
    override fun stop() {
        stops.incrementAndGet()
        holdStop?.await(10, TimeUnit.SECONDS)
        order += "tunnel stopped"
    }
}

internal class FakeGate(private val order: MutableList<String>) : BoxGate {
    val begins = CopyOnWriteArrayList<String>()
    val ends = AtomicInteger()
    /** Ends with no begin left to match. Every test expects 0. */
    val unmatched = AtomicInteger()
    @Volatile var refuse = false

    override fun begin(what: String, aaSessionLive: Boolean): Boolean {
        if (refuse) return false
        begins += what
        order += "gate begin"
        return true
    }

    override fun end() {
        if (ends.incrementAndGet() > begins.size) unmatched.incrementAndGet()
        order += "gate end"
    }
}

internal class FakePhone(override val id: String = "/dev/bus/usb/001/005", override val productName: String? = "iPhone") : IphoneDevice {
    override val vendorId = 0x05AC
    override val productId = 0x12A8
    override val label: String get() = productName ?: "iPhone"
}

/** The pipe of an open iPhone that logs its own close, so a test can read the close order. */
internal class RecordingPipe(private val inner: IphoneUsbPipe, private val order: MutableList<String>) : IphoneUsbPipe by inner {
    private val closedOnce = AtomicInteger()
    override fun close() { if (closedOnce.incrementAndGet() == 1) order += "usb pipe closed"; inner.close() }
}

internal class FakeUsb(private val order: MutableList<String>, private val phoneMac: ByteArray?) : IphoneUsbPort {
    @Volatile var permission = true
    @Volatile var configurations = 6
    @Volatile var mode: IphoneModeAnswer = IphoneModeAnswer.Accepted
    val asked = AtomicInteger()
    val modeRequests = AtomicInteger()
    val opens = AtomicInteger()
    val iphones = CopyOnWriteArrayList<FakeIphone>()
    val phones = CopyOnWriteArrayList<FakeIap2Phone>()
    val ethernets = CopyOnWriteArrayList<FakeEthernet>()
    @Volatile var availabilities = 1

    @Volatile var present = true
    override fun hasPermission(device: IphoneDevice) = permission
    override fun isPresent(device: IphoneDevice) = present
    override fun configurationCount(device: IphoneDevice) = configurations
    override fun askPermission(device: IphoneDevice) { asked.incrementAndGet() }
    override fun requestMode(device: IphoneDevice): IphoneModeAnswer { modeRequests.incrementAndGet(); return mode }
    override fun serial(device: IphoneDevice): String? = "SERIAL0001"

    override fun open(device: IphoneDevice): OpenedIphone {
        opens.incrementAndGet()
        val iphone = FakeIphone().also { iphones += it }
        iphone.listen(UsbmuxCodec.LOCKDOWN_PORT) { stream -> FakeIap2Phone(stream, availabilities).also { phones += it }.run() }
        val ethernet = FakeEthernet(order).also { ethernets += it }
        return object : OpenedIphone {
            override val pipe: IphoneUsbPipe = RecordingPipe(iphone, order)
            override val configurationId = 6
            override val usbmuxEndpoints = 0x04 to 0x85
            override val ncmControlInterface = 2
            override fun openNcm() = NcmOpened(ethernet, phoneMac)
            override fun close() = pipe.close()
        }
    }
}

/** The lockdown stage as a fake: it opens the iAP2 stream on the usbmux port the fake iPhone listens on. */
internal class FakeCarkit(private val usedSaved: Boolean = false) : CarkitOpener {
    val opens = AtomicInteger()
    /** Holds the first open until released, then fails it: a real late event from the first session's own thread. */
    @Volatile var holdFirst: java.util.concurrent.CountDownLatch? = null
    override fun open(connector: com.andrerinas.openheadunit.connection.carplaynative.usb.MuxConnector, serial: String, label: String, isCancelled: () -> Boolean): Carkit {
        val hold = holdFirst
        if (opens.incrementAndGet() == 1 && hold != null) {
            hold.await(10, TimeUnit.SECONDS)
            throw IOException("late failure of the first session")
        }
        return Carkit(connector.connect(UsbmuxCodec.LOCKDOWN_PORT, 5000), usedSaved)
    }
}

/** Real time plus a skew a test adds, so a deadline passes at once while sockets still time out in real time. */
internal class SkewClock {
    private val skew = AtomicLong(0)
    fun now(): Long = System.nanoTime() / 1_000_000 + skew.get()
    fun advance(ms: Long) { skew.addAndGet(ms) }
}

internal fun ncmFrameFromPhone(hostMac: ByteArray, airPlayPort: Int): ByteArray =
    EthernetIpv6.build(byteArrayOf(0x0a, 1, 2, 3, 4, 5), hostMac, TunProbePacket.syn(airPlayPort))

internal fun canConnect(address: InetAddress, port: Int): Boolean = try { Socket(address, port).use { true } } catch (_: IOException) { false }

internal class NativeFixtures(folder: File) {
    val order = java.util.Collections.synchronizedList(ArrayList<String>())
    val clock = SkewClock()
    val video = RecordingVideoSink()
    val audio = RecordingAudioSink()
    val signer = StubSigner()
    val loopback: InetAddress = InetAddress.getLoopbackAddress()
    @Volatile var airPlayPort = 0
    val usb = FakeUsb(order, byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55))
    val tunnel = FakeTunnel(order) { if (airPlayPort != 0 && canConnect(loopback, airPlayPort)) "open" else "closed" }
    val gate = FakeGate(order)
    val carkit = FakeCarkit()
    val storage = AirPlayStorage(File(folder, "airplay"))
}
