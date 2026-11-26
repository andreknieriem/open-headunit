package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.utils.AppLog

/** One I2C bus. `write` and `read` return false or null when the chip does not acknowledge. */
interface I2cTransport {
    fun write(address: Int, bytes: ByteArray): Boolean
    fun read(address: Int, count: Int): ByteArray?
}

/**
 * The Apple MFi chip register sequence over [transport]. [nanoClock] and [sleepNanos] are injected so
 * a test runs without waiting. Ported from LIVI (https://github.com/f-io/LIVI), crate iap2-mfi.
 */
class MfiChipClient(
    private val transport: I2cTransport,
    private val nanoClock: () -> Long,
    private val sleepNanos: (Long) -> Unit,
) {
    private var address = -1

    /** A sleeping chip refuses the first access and wakes from it, so the probe goes round a few times. */
    fun probe(): Boolean {
        val deadline = nanoClock() + PROBE_TIMEOUT
        while (nanoClock() < deadline) {
            for (candidate in ADDRESS_CANDIDATES) {
                if (transport.read(candidate, 1) != null) {
                    address = candidate
                    AppLog.i("CarPlayNative: MFi chip answers at 0x${candidate.toString(16)}")
                    return true
                }
            }
            sleepNanos(BUSY_RETRY)
        }
        return false
    }

    fun protocolMajorRegister(): Int = readRegister(REG_PROTOCOL_MAJOR, 1)[0].toInt() and 0xff

    fun readCertificate(): ByteArray {
        val size = readLength(REG_CERT_LENGTH)
        return readRegister(REG_CERT_DATA, size)
    }

    fun sign(challenge: ByteArray): ByteArray {
        val n = challenge.size
        require(n in MfiLimits.CHALLENGE_MIN..MfiLimits.CHALLENGE_MAX) { "challenge must be 1..128 bytes, got $n" }
        writeRegister(REG_CHALLENGE_LENGTH, byteArrayOf((n shr 8).toByte(), n.toByte()))
        writeRegister(REG_CHALLENGE_DATA, challenge)
        writeRegister(REG_AUTH_CONTROL_STATUS, byteArrayOf(AUTH_START))
        sleepNanos(AUTH_START_WAIT)
        val deadline = nanoClock() + AUTH_TIMEOUT
        while (true) {
            val status = try { readRegister(REG_AUTH_CONTROL_STATUS, 1)[0] } catch (e: SignerException) { null }
            if (status == AUTH_DONE) break
            if (nanoClock() >= deadline) {
                val code = try { readRegister(REG_ERROR_CODE, 1)[0].toInt() and 0xff } catch (e: SignerException) { null }
                throw SignerException("auth failed (error code ${code?.let { "0x" + it.toString(16) } ?: "unreadable"})")
            }
            sleepNanos(AUTH_POLL)
        }
        return readRegister(REG_SIGNATURE_DATA, readLength(REG_SIGNATURE_LENGTH))
    }

    private fun readLength(register: Int): Int {
        val v = readRegister(register, 2)
        return ((v[0].toInt() and 0xff) shl 8) or (v[1].toInt() and 0xff)
    }

    private fun readRegister(register: Int, count: Int): ByteArray {
        retry("register select 0x${register.toString(16)}") { transport.write(address(), byteArrayOf(register.toByte())) }
        if (count == 0) return ByteArray(0)
        var data: ByteArray? = null
        retry("read at 0x${register.toString(16)}") {
            data = transport.read(address(), count)?.takeIf { it.size == count }
            data != null
        }
        return data!!
    }

    private fun writeRegister(register: Int, data: ByteArray) {
        val frame = byteArrayOf(register.toByte()) + data
        retry("write at 0x${register.toString(16)}") { transport.write(address(), frame) }
    }

    private fun address(): Int {
        if (address < 0) throw SignerException("no MFi chip answered")
        return address
    }

    /** The chip is busy for a short time after each access, so an operation repeats until [IO_TIMEOUT]. */
    private fun retry(what: String, operation: () -> Boolean) {
        val deadline = nanoClock() + IO_TIMEOUT
        while (true) {
            if (operation()) return
            if (nanoClock() >= deadline) throw SignerException("timeout during $what")
            sleepNanos(BUSY_RETRY)
        }
    }

    companion object {
        val ADDRESS_CANDIDATES = intArrayOf(0x10, 0x11)

        const val REG_PROTOCOL_MAJOR = 0x02
        const val REG_ERROR_CODE = 0x05
        const val REG_AUTH_CONTROL_STATUS = 0x10
        const val REG_SIGNATURE_LENGTH = 0x11
        const val REG_SIGNATURE_DATA = 0x12
        const val REG_CHALLENGE_LENGTH = 0x20
        const val REG_CHALLENGE_DATA = 0x21
        const val REG_CERT_LENGTH = 0x30
        const val REG_CERT_DATA = 0x31

        const val AUTH_START: Byte = 0x01
        const val AUTH_DONE: Byte = 0x10

        const val BUSY_RETRY = 500_000L
        const val IO_TIMEOUT = 2_000_000_000L
        const val PROBE_TIMEOUT = 200_000_000L
        const val AUTH_POLL = 10_000_000L
        const val AUTH_START_WAIT = 10_000_000L
        const val AUTH_TIMEOUT = 3_000_000_000L
    }
}

/**
 * [CarPlaySigner] over a probed [MfiChipClient]. Build one per opened chip: the protocol major and the certificate are read once.
 * Ported from LIVI (https://github.com/f-io/LIVI), crate iap2-mfi.
 */
class ChipSigner(private val client: MfiChipClient) : CarPlaySigner {

    private var major = 0
    private var cached: ByteArray? = null

    override fun protocolMajor(): Int {
        if (major != 0) return major
        val register = try { client.protocolMajorRegister() } catch (e: SignerException) { 0 }
        // A register that reads neither 2 nor 3 falls back on the certificate size, as LIVI's server does.
        major = if (register == 2 || register == 3) register else if (certificate().size < CERT_LEN_SPLIT) 3 else 2
        return major
    }

    override fun certificate(): ByteArray {
        cached?.let { return it.copyOf() }
        val cert = client.readCertificate()
        if (cert.isEmpty()) throw SignerException("the MFi chip holds no certificate")
        cached = cert
        return cert.copyOf()
    }

    override fun sign(challenge: ByteArray): ByteArray = client.sign(challenge)

    companion object {
        /** An RSA (major 2) certificate is longer than this, an ECDSA (major 3) one shorter. */
        const val CERT_LEN_SPLIT = 768
    }
}
