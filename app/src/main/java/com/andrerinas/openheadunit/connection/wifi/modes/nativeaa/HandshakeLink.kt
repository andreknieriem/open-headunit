package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * One phone-side connection the wireless handshake can run over.
 *
 * The handshake needs surprisingly little from its transport: two streams, a way to close them, and
 * enough identity to write a useful log line. Everything that makes the exchange what it is —
 * `WppFraming`, `WppHandshakeSession`, the reader coroutine, the stage deadlines — is already
 * written against `InputStream`/`OutputStream` and never mentions Bluetooth. Naming that surface
 * here is what lets a second transport exist without any of it changing.
 *
 * The second transport is the head unit's external Bluetooth module, reached through a vendor
 * daemon over a loopback socket, on units where `android.bluetooth` transmits nothing the phone
 * will ever see. See `connection/wifi/modes/nativeaa/zbt/ZbtByteChannel`.
 */
interface HandshakeLink : Closeable {

    /** Bytes from the phone. */
    val input: InputStream

    /** Bytes to the phone. */
    val output: OutputStream

    /** The phone's name, when the transport knows it. Logging only. */
    val peerName: String?

    /**
     * The phone's Bluetooth address as canonical `AA:BB:CC:DD:EE:FF`, or null.
     *
     * Canonical because every consumer compares it with addresses from `android.bluetooth`, and the
     * module publishes its own without separators. See [persistPeerForAutoStart].
     */
    val peerAddress: String?

    /** Which radio or channel carried this, for logs that have to tell several apart. */
    val radioLabel: String?

    /**
     * Whether [peerAddress] should be remembered as an auto-start device.
     *
     * True for a phone that reached us over this unit's own Bluetooth, because that address is
     * later dialled to wake it and its ACL events start the service. False for a phone on the
     * far side of an external module: nothing in `android.bluetooth` can reach that address, so
     * storing it would only feed a wake path that cannot work and a receiver that will not fire.
     */
    val persistPeerForAutoStart: Boolean

    /**
     * Whether the transport knows a phone was really there.
     *
     * An accept proves it on this unit's own radio. On the module the daemon owns the accept, so a
     * handshake may be started on nothing more than a channel that has gone quiet, and a failure
     * then says nothing about the phone or the radio. Callers that would otherwise blame the
     * hardware for silence must ask this first.
     */
    val peerReportedPresent: Boolean

    /**
     * Whether the opening message should be repeated while the phone has said nothing.
     *
     * False on this unit's own radio, where an accept proves the phone is there. True on the
     * module, where the daemon owns the accept and a message sent before the phone's channel
     * exists is dropped with nobody told.
     */
    val retransmitsWhileSilent: Boolean
}

/** The ordinary route: a phone that connected to one of this unit's own RFCOMM listeners. */
class BluetoothSocketLink(
    private val socket: BluetoothSocket,
    override val radioLabel: String? = null
) : HandshakeLink {

    override val input: InputStream by lazy { socket.inputStream }
    override val output: OutputStream by lazy { socket.outputStream }
    override val peerName: String? get() = runCatching { socket.remoteDevice?.name }.getOrNull()
    override val peerAddress: String? get() = runCatching { socket.remoteDevice?.address }.getOrNull()
    override val persistPeerForAutoStart: Boolean get() = true
    override val peerReportedPresent: Boolean get() = true
    override val retransmitsWhileSilent: Boolean get() = false

    override fun close() {
        socket.close()
    }
}
