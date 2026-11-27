package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.Der
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.usb.BlockingDuplexByteStream
import com.andrerinas.openheadunit.connection.carplaynative.usb.FakeIphone
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbException
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxCodec
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509TrustManager
import java.math.BigInteger
import java.security.Signature

/**
 * The lockdown service of the fake iPhone: its own RSA key, pair requests that wait for Trust, a TLS session with a certificate of its own, and the carkit service that echoes. Test only.
 */
class FakeLockdown(private val phone: FakeIphone, private val clock: () -> Long) {
    val requests = CopyOnWriteArrayList<String>()
    val pairRequests = AtomicInteger()
    val sessionTlsHandshakes = AtomicInteger()
    val serviceTlsHandshakes = AtomicInteger()

    /** How many Pair requests answer "waiting for Trust" before the next one is accepted. A negative number never accepts. */
    @Volatile var pendingPairs = 0
    @Volatile var knownHostId: String? = null
    @Volatile var neverAnswer: Boolean = false
    @Volatile var wifiAddress = "aa:bb:cc:dd:ee:ff"

    val key: KeyPair = shared

    init {
        phone.listen(UsbmuxCodec.LOCKDOWN_PORT) { serve(it) }
        phone.listen(SERVICE_PORT) { serveService(it) }
    }

    private fun serve(plain: BlockingDuplexByteStream) {
        var stream: BlockingDuplexByteStream = plain
        var channel = LockdownPlistChannel(stream, clock)
        while (true) {
            val request = try { channel.receive(3000) } catch (_: IphoneUsbException) { return }
            val name = request["Request"]?.asText ?: return
            requests.add(name)
            if (neverAnswer) { Thread.sleep(10_000); return }
            when (name) {
                "SetValue" -> reply(stream, PlistValue.dict())
                "GetValue" -> when (request["Key"]?.asText) {
                    "DevicePublicKey" -> reply(stream, PlistValue.dict("Value" to PlistValue.Data(pkcs1Pem(key))))
                    "WiFiAddress" -> reply(stream, PlistValue.dict("Value" to PlistValue.Text(wifiAddress)))
                    else -> reply(stream, PlistValue.dict("Error" to PlistValue.Text("InvalidKey")))
                }
                "Pair" -> {
                    pairRequests.incrementAndGet()
                    val record = (request["PairRecord"] as PlistValue.Dict)
                    if (pendingPairs != 0) {
                        if (pendingPairs > 0) pendingPairs--
                        reply(stream, PlistValue.dict("Error" to PlistValue.Text("PairingDialogResponsePending")))
                    } else {
                        knownHostId = record["HostID"]!!.asText
                        reply(stream, PlistValue.dict("EscrowBag" to PlistValue.Data(ByteArray(4))))
                    }
                }
                "StartSession" -> {
                    if (request["HostID"]?.asText != knownHostId) {
                        reply(stream, PlistValue.dict("Error" to PlistValue.Text("InvalidHostID")))
                    } else {
                        reply(stream, PlistValue.dict("EnableSessionSSL" to PlistValue.Bool(true), "SessionID" to PlistValue.Text("S")))
                        stream = TlsDuplexChannel.open(stream, serverEngine(), 3000, clock)
                        sessionTlsHandshakes.incrementAndGet()
                        channel = LockdownPlistChannel(stream, clock)
                    }
                }
                "StartService" -> reply(stream, PlistValue.dict(
                    "Port" to PlistValue.Integer(SERVICE_PORT.toLong()), "EnableServiceSSL" to PlistValue.Bool(true), "Service" to PlistValue.Text("carkit"),
                ))
                else -> reply(stream, PlistValue.dict("Error" to PlistValue.Text("UnknownRequest")))
            }
        }
    }

    private fun serveService(plain: BlockingDuplexByteStream) {
        val tls = TlsDuplexChannel.open(plain, serverEngine(), 3000, clock)
        serviceTlsHandshakes.incrementAndGet()
        try {
            while (true) {
                val chunk = tls.recv(65536, 3000) ?: break
                if (chunk.isEmpty()) break
                tls.send(chunk)
            }
        } finally { tls.close() }
    }

    private fun reply(stream: BlockingDuplexByteStream, dict: PlistValue.Dict) = stream.send(LockdownFrame.encode(dict))

    private fun serverEngine(): SSLEngine {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(selfSigned(key))) as X509Certificate
        val password = "x".toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, password); setKeyEntry("d", key.private, password, arrayOf(certificate)) }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }.keyManagers
        // No client authentication: the TLS stack of the JVM cannot parse the empty names of the host certificates.
        val context = SSLContext.getInstance("TLS", conscrypt).apply { init(managers, arrayOf(AcceptAny), null) }
        return context.createSSLEngine().apply { useClientMode = false }
    }

    private object AcceptAny : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val SERVICE_PORT = 49152

        /** The provider Android ships. The JDK parser refuses the empty names of the lockdown certificates. */
        val conscrypt: java.security.Provider by lazy { org.conscrypt.Conscrypt.newProvider() }
        private val shared: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

        /** The `RSA PUBLIC KEY` PEM of PKCS#1 that `GetValue DevicePublicKey` returns. */
        fun pkcs1Pem(pair: KeyPair): ByteArray {
            val rsa = pair.public as java.security.interfaces.RSAPublicKey
            val der = Der.sequence(Der.integer(rsa.modulus), Der.integer(rsa.publicExponent))
            val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
            return "-----BEGIN RSA PUBLIC KEY-----\n$b64\n-----END RSA PUBLIC KEY-----\n".toByteArray()
        }

        /** A self-signed certificate with a real name, the kind an iPhone presents. */
        fun selfSigned(pair: KeyPair): ByteArray {
            val name = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.tlv(0x0c, "fake iPhone".toByteArray()))))
            val algorithm = Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.nullValue())
            val tbs = Der.sequence(
                Der.explicit(0, Der.integer(BigInteger.valueOf(2))), Der.integer(BigInteger.ONE), algorithm, name,
                Der.sequence(Der.tlv(0x17, "200101000000Z".toByteArray()), Der.tlv(0x17, "490101000000Z".toByteArray())),
                name, pair.public.encoded,
            )
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }.sign()
            return Der.sequence(tbs, algorithm, Der.bitString(signature))
        }
    }
}
