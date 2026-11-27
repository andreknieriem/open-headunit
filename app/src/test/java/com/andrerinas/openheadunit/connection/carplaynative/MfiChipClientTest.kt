package com.andrerinas.openheadunit.connection.carplaynative

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MfiChipClientTest {

    /** A chip on one address. A sleeping chip refuses [refusals] accesses first. */
    private class FakeChip(val address: Int, var refusals: Int = 0) : I2cTransport {
        val registers = HashMap<Int, ByteArray>()
        val writes = ArrayList<Pair<Int, ByteArray>>()
        var statusPollsBeforeDone = 0
        var neverDone = false
        var selected = -1
        var statusReads = 0
        var protocolReads = 0
        var certDataReads = 0

        private fun accepts(addr: Int): Boolean {
            if (addr != address) return false
            if (refusals > 0) { refusals--; return false }
            return true
        }

        override fun write(address: Int, bytes: ByteArray): Boolean {
            if (!accepts(address)) return false
            selected = bytes[0].toInt() and 0xff
            if (bytes.size > 1) {
                writes.add(selected to bytes.copyOfRange(1, bytes.size))
                if (selected == 0x10 && bytes[1] == 1.toByte()) statusReads = 0
            }
            return true
        }

        override fun read(address: Int, count: Int): ByteArray? {
            if (!accepts(address)) return null
            if (selected == 0x02) protocolReads++
            if (selected == 0x31) certDataReads++
            if (selected == 0x10) {
                statusReads++
                val done = !neverDone && statusReads > statusPollsBeforeDone
                return byteArrayOf(if (done) 0x10 else 0x00)
            }
            return registers[selected]?.copyOf(count) ?: ByteArray(count)
        }
    }

    private class Clock {
        var now = 0L
        val sleeps = ArrayList<Long>()
        fun client(t: I2cTransport) = MfiChipClient(t, { now }, { now += it; sleeps.add(it) })
    }

    @Test
    fun `the probe answers on 0x10 first and on 0x11 when 0x10 is silent`() {
        assertTrue(Clock().client(FakeChip(0x10)).probe())
        assertTrue(Clock().client(FakeChip(0x11)).probe())
    }

    @Test
    fun `a sleeping chip is woken by the retries`() {
        val chip = FakeChip(0x11, refusals = 5)
        val clock = Clock()
        assertTrue(clock.client(chip).probe())
        assertTrue(clock.now < MfiChipClient.PROBE_TIMEOUT)
    }

    @Test
    fun `no chip ends the probe after the window`() {
        val clock = Clock()
        assertFalse(clock.client(FakeChip(0x40)).probe())
        assertTrue(clock.now >= MfiChipClient.PROBE_TIMEOUT)
        assertTrue(clock.now < MfiChipClient.PROBE_TIMEOUT + 10 * MfiChipClient.BUSY_RETRY)
    }

    @Test
    fun `the certificate is read by its length register then its data register`() {
        val chip = FakeChip(0x10)
        val cert = ByteArray(300) { it.toByte() }
        chip.registers[0x30] = byteArrayOf(0x01, 0x2c)
        chip.registers[0x31] = cert
        val client = Clock().client(chip).also { it.probe() }
        assertArrayEquals(cert, client.readCertificate())
    }

    @Test
    fun `a sign writes length then challenge then start and reads the signature`() {
        val chip = FakeChip(0x10)
        chip.statusPollsBeforeDone = 3
        val signature = ByteArray(64) { (it + 9).toByte() }
        chip.registers[0x11] = byteArrayOf(0x00, 0x40)
        chip.registers[0x12] = signature
        val challenge = ByteArray(32) { it.toByte() }
        val client = Clock().client(chip).also { it.probe() }
        assertArrayEquals(signature, client.sign(challenge))
        assertEquals(listOf(0x20, 0x21, 0x10), chip.writes.map { it.first })
        assertArrayEquals(byteArrayOf(0x00, 0x20), chip.writes[0].second)
        assertArrayEquals(challenge, chip.writes[1].second)
        assertArrayEquals(byteArrayOf(0x01), chip.writes[2].second)
        assertEquals(4, chip.statusReads)
    }

    @Test
    fun `a challenge of 1 and of 128 bytes is accepted and 0 and 129 are refused`() {
        for (n in listOf(1, 128)) {
            val chip = FakeChip(0x10)
            chip.registers[0x11] = byteArrayOf(0x00, 0x02)
            chip.registers[0x12] = byteArrayOf(5, 6)
            val client = Clock().client(chip).also { it.probe() }
            assertArrayEquals(byteArrayOf(5, 6), client.sign(ByteArray(n)))
        }
        for (n in listOf(0, 129)) {
            val chip = FakeChip(0x10)
            val client = Clock().client(chip).also { it.probe() }
            try { client.sign(ByteArray(n)); org.junit.Assert.fail("accepted $n bytes") } catch (e: IllegalArgumentException) { }
            assertTrue(chip.writes.isEmpty())
        }
    }

    @Test
    fun `a chip that never finishes gives a timeout with its error code`() {
        val chip = FakeChip(0x10)
        chip.neverDone = true
        chip.registers[0x05] = byteArrayOf(0x07)
        val clock = Clock()
        val client = clock.client(chip).also { it.probe() }
        val start = clock.now
        try {
            client.sign(ByteArray(32))
            org.junit.Assert.fail("no timeout")
        } catch (e: SignerException) {
            assertTrue(e.message!!.contains("0x7"))
        }
        assertTrue(clock.now - start >= MfiChipClient.AUTH_TIMEOUT)
    }

    @Test
    fun `an operation that never works fails after the io timeout`() {
        val chip = FakeChip(0x10)
        val clock = Clock()
        val client = clock.client(chip).also { it.probe() }
        chip.refusals = Int.MAX_VALUE
        val start = clock.now
        try {
            client.readCertificate()
            org.junit.Assert.fail("no timeout")
        } catch (e: SignerException) {
            assertTrue(e.message!!.startsWith("timeout during"))
        }
        assertTrue(clock.now - start >= MfiChipClient.IO_TIMEOUT)
    }

    @Test
    fun `a read before a probe finds a chip fails at once`() {
        val client = Clock().client(FakeChip(0x40))
        try { client.readCertificate(); org.junit.Assert.fail("read without a chip") } catch (e: SignerException) { }
    }

    @Test
    fun `the signer takes the major from the register`() {
        val chip = FakeChip(0x10)
        chip.registers[0x02] = byteArrayOf(3)
        val signer = ChipSigner(Clock().client(chip).also { it.probe() })
        assertEquals(3, signer.protocolMajor())
        assertEquals(3, signer.protocolMajor())
        assertEquals(1, chip.protocolReads)
    }

    @Test
    fun `a register that reads neither 2 nor 3 falls back on the certificate size`() {
        fun major(certSize: Int): Int {
            val chip = FakeChip(0x10)
            chip.registers[0x02] = byteArrayOf(0)
            chip.registers[0x30] = byteArrayOf((certSize shr 8).toByte(), certSize.toByte())
            chip.registers[0x31] = ByteArray(certSize) { 1 }
            return ChipSigner(Clock().client(chip).also { it.probe() }).protocolMajor()
        }
        assertEquals(3, major(767))
        assertEquals(2, major(768))
    }

    @Test
    fun `a chip with an empty certificate is refused`() {
        val chip = FakeChip(0x10)
        chip.registers[0x30] = byteArrayOf(0, 0)
        val signer = ChipSigner(Clock().client(chip).also { it.probe() })
        try { signer.certificate(); org.junit.Assert.fail("empty certificate") } catch (e: SignerException) { }
    }

    @Test
    fun `the certificate is read from the chip once for the life of the signer`() {
        val chip = FakeChip(0x10)
        chip.registers[0x02] = byteArrayOf(0)
        chip.registers[0x30] = byteArrayOf(0, 4)
        chip.registers[0x31] = byteArrayOf(1, 2, 3, 4)
        val signer = ChipSigner(Clock().client(chip).also { it.probe() })
        signer.protocolMajor()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), signer.certificate())
        signer.certificate()[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), signer.certificate())
        assertEquals(1, chip.certDataReads)
    }
}
