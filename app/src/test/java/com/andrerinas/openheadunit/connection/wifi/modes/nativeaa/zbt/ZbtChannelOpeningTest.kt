package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses the real connect/init path with a deterministic socket boundary; no Bluetooth device. */
class ZbtChannelOpeningTest {
    private class FakeSocket(
        val connected: () -> Unit = {},
        val beforeInit: () -> Unit = {},
        val closed: () -> Unit = {},
        val output: OutputStream = ByteArrayOutputStream()
    ) : Socket() {
        var connects = 0
        var closes = 0
        override fun connect(endpoint: SocketAddress, timeout: Int) { connects++; connected() }
        override fun setSoTimeout(timeout: Int) = Unit
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream { beforeInit(); return output }
        override fun close() { closes++; closed() }
    }

    private fun expectCancelled(block: () -> Unit) {
        try { block(); fail("opening should be cancelled") }
        catch (_: ZbtChannelOpening.Cancelled) { }
    }

    @Test fun `close before attach prevents connect and init`() {
        val opening = ZbtChannelOpening { true }
        val socket = FakeSocket()
        opening.close()
        expectCancelled { ZbtByteChannel.open(opening = opening, socketFactory = { socket }) }
        assertEquals(0, socket.connects)
        assertTrue(socket.closes > 0)
        assertEquals(0, (socket.output as ByteArrayOutputStream).size())
    }

    @Test fun `close while connect is blocked reaches the unpublished socket`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opening = ZbtChannelOpening { true }
        val error = AtomicReference<Throwable>()
        val socket = FakeSocket(
            connected = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) },
            closed = { release.countDown() }
        )
        val worker = Thread {
            try { ZbtByteChannel.open(opening = opening, socketFactory = { socket }) }
            catch (e: Throwable) { error.set(e) }
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        opening.close()
        worker.join(2_000)
        assertFalse("close must unblock the opening", worker.isAlive)
        assertTrue(error.get() is ZbtChannelOpening.Cancelled)
        assertEquals(0, (socket.output as ByteArrayOutputStream).size())
    }

    @Test fun `close after connect but before init leaves no initial frame`() {
        val opening = ZbtChannelOpening { true }
        val socket = FakeSocket(beforeInit = { opening.close() })
        expectCancelled { ZbtByteChannel.open(opening = opening, socketFactory = { socket }) }
        assertEquals(1, socket.connects)
        assertTrue(socket.closes > 0)
        assertEquals(0, (socket.output as ByteArrayOutputStream).size())
    }

    @Test fun `retired mode without explicit close still refuses init admission`() {
        var running = true
        val opening = ZbtChannelOpening { running }
        val socket = FakeSocket(beforeInit = { running = false })
        expectCancelled { ZbtByteChannel.open(opening = opening, socketFactory = { socket }) }
        assertTrue(socket.closes > 0)
        assertEquals(0, (socket.output as ByteArrayOutputStream).size())
    }

    @Test fun `normal open retains exactly one Android Auto init`() {
        val socket = FakeSocket()
        val channel = ZbtByteChannel.open(opening = ZbtChannelOpening { true }, socketFactory = { socket })
        try {
            val bytes = (socket.output as ByteArrayOutputStream).toByteArray()
            assertArrayEquals(
                ZbtFraming.encodeFrame(
                    ZbtMessages.encodeRequestInit(ZbtByteChannel.REQUEST_INIT_ID, ZbtMessages.ENABLE_TYPE_ANDROID_AUTO),
                    ZbtMessages.REQUEST_INIT
                ), bytes
            )
        } finally { channel.close() }
    }

    @Test fun `already admitted write may finish during cancellation`() {
        val opening = ZbtChannelOpening { true }
        val bytes = ByteArrayOutputStream()
        val sink = object : OutputStream() {
            override fun write(b: Int) = bytes.write(b)
            override fun write(b: ByteArray) { opening.close(); bytes.write(b) }
        }
        val socket = FakeSocket(output = sink)
        val channel = ZbtByteChannel.open(opening = opening, socketFactory = { socket })
        channel.close()
        assertTrue("admission does not roll back a write already in progress", bytes.size() > 0)
        assertTrue(socket.closes > 0)
    }

    @Test fun `channel close and a cancelled opening both close the socket through the adapter`() {
        val opening = ZbtChannelOpening { true }
        val socket = FakeSocket()
        val channel = ZbtByteChannel.open(opening = opening, socketFactory = { socket })
        channel.close()
        assertEquals(1, socket.closes)
        // The opening holds the same handle; closing it after the channel only repeats Socket.close.
        opening.close()
        assertEquals(2, socket.closes)
        opening.close()
        assertEquals("an opening closes its resource once", 2, socket.closes)
    }

    @Test fun `admitted command refuses after close and after the owner stops`() {
        var running = true
        val opening = ZbtChannelOpening { running }
        opening.admitCommand()
        opening.admitCommand()
        running = false
        expectCancelled { opening.admitCommand() }
        running = true
        opening.close()
        expectCancelled { opening.admitCommand() }
    }

    /**
     * java.net.Socket is Closeable only from API 19. A JVM test cannot load an API 16 Socket, so
     * check the compiled code instead: socket consumers may not cast to Closeable, which is what
     * handing a Socket to a Closeable parameter compiles to.
     */
    @Test fun `no ZBT socket path casts to Closeable`() {
        val classes = listOf(
            ZbtByteChannel::class.java, ZbtByteChannel.Companion::class.java,
            ZbtChannelOpening::class.java, ZbtProbe::class.java,
        )
        val casts = mutableListOf<String>()
        for (type in classes) {
            val resource = type.name.replace('.', '/') + ".class"
            val bytes = type.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() }
            net.bytebuddy.jar.asm.ClassReader(bytes).accept(object : net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
                ) = object : net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                    override fun visitTypeInsn(opcode: Int, operand: String) {
                        if (opcode == net.bytebuddy.jar.asm.Opcodes.CHECKCAST && operand == "java/io/Closeable") {
                            casts.add("${type.simpleName}.$name")
                        }
                    }
                }
            }, 0)
        }
        assertEquals(emptyList<String>(), casts)
    }
}
