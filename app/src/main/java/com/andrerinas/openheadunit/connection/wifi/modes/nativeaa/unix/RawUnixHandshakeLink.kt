package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.unix

import android.net.LocalSocket
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import java.io.InputStream
import java.io.OutputStream

/**
 * A [HandshakeLink] backed directly by an Android [LocalSocket] connected to
 * a Unix Domain Stream Socket (such as `/dev/socket/zj_bt_socket`).
 *
 * On Unisoc / KingPad platforms with the Zlink Bluetooth daemon, the vendor daemon
 * handles the RFCOMM server link and presents incoming/outgoing RFCOMM byte streams
 * directly over `/dev/socket/zj_bt_socket` without any ZBT multiplexing wrappers.
 */
class RawUnixHandshakeLink(
    private val socket: LocalSocket,
    override val peerName: String? = null,
    override val peerAddress: String? = null,
    override val radioLabel: String? = "Unix Domain Socket (zj_bt_socket)"
) : HandshakeLink {

    override val input: InputStream by lazy { socket.inputStream }
    override val output: OutputStream by lazy { socket.outputStream }

    /**
     * False for a phone on the far side of an external daemon socket, because
     * android.bluetooth does not reach or own this pairing.
     */
    override val persistPeerForAutoStart: Boolean = false

    /**
     * An active connection to the Unix domain socket indicates a phone session is present.
     */
    override val peerReportedPresent: Boolean = true

    /**
     * The head unit sends Type 4 (WifiVersionRequest) immediately upon connection.
     * Retransmitting while silent ensures the phone receives the opener even if
     * the daemon opened the socket slightly before the phone's RFCOMM stream was ready.
     */
    override val retransmitsWhileSilent: Boolean = true

    override fun close() {
        runCatching { socket.close() }
    }
}
