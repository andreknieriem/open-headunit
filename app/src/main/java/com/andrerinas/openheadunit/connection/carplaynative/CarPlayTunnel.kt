package com.andrerinas.openheadunit.connection.carplaynative

import android.content.Context
import android.os.ParcelFileDescriptor
import com.andrerinas.openheadunit.utils.VpnControl
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket

/** [TunPort] over the tunnel descriptor. Closing the descriptor closes the tunnel and ends a blocked read. */
class DescriptorTunPort(private val descriptor: ParcelFileDescriptor) : TunPort {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = output.write(buffer, offset, length)

    override fun close() = descriptor.close()
}

/** [TunnelControl] over the one VPN seam. The playstore copy of `VpnControl` answers that no tunnel exists. */
class VpnTunnelControl(context: Context) : TunnelControl {
    private val context = context.applicationContext

    fun isAvailable(): Boolean = VpnControl.isCarPlayTunAvailable()

    override fun isDummyVpnUp(): Boolean = VpnControl.isDummyVpnUp()

    override fun isCarPlayTunRunning(): Boolean = VpnControl.isCarPlayTunRunning()

    override fun isPrepared(): Boolean = VpnControl.isPrepared(context)

    override fun start(onReady: (TunPort) -> Unit, onFailed: (String) -> Unit) =
        VpnControl.startCarPlayTun(context, { onReady(DescriptorTunPort(it)) }, onFailed)

    override fun stop() = VpnControl.stopCarPlayTun(context)
}

/** A listener on `[::]` with a port the system picks. */
object ServerSocketProbeListeners : ProbeListenerFactory {
    override fun bind(): ProbeListener {
        val socket = ServerSocket(0, 1, InetAddress.getByName("::"))
        return object : ProbeListener {
            override val port: Int = socket.localPort
            override fun close() = socket.close()
        }
    }
}
