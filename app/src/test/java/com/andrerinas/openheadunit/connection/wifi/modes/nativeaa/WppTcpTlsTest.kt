package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Drives the handshake against a peer configured the way Android Auto 17.5 configures its end of
 * this socket: it dials us, then answers in server mode and demands a client certificate. So the
 * side that accepted has to speak first and present one, and getting that backwards is not a
 * refusal but a stall - both ends wait to read until the phone's timeout closes the socket.
 */
class WppTcpTlsTest {

    private val sslContext = sslContext()
    private val pool = Executors.newSingleThreadExecutor()

    @Test
    fun `the accepted socket hand shakes as the client, against a phone that requires one`() {
        val phone = (sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply {
            useClientMode = false
            needClientAuth = true
        }
        val peer = pool.submit<SSLSocket> { (phone.accept() as SSLSocket).also { it.startHandshake() } }

        val raw = Socket(InetAddress.getLoopbackAddress(), phone.localPort)
        val ours = WppTcpTls.clientSocket(sslContext.socketFactory, raw)
        ours.startHandshake()

        val theirs = peer.get(15, TimeUnit.SECONDS)
        assertTrue(ours.useClientMode)
        assertNotNull(ours.session.cipherSuite)
        // The certificate reached them, which is the half the phone refuses to continue without.
        assertEquals(1, theirs.session.peerCertificates.size)
        assertEquals(
            (ours.session.localCertificates.first() as X509Certificate).subjectX500Principal,
            (theirs.session.peerCertificates.first() as X509Certificate).subjectX500Principal
        )

        ours.close()
        theirs.close()
        phone.close()
    }

    private fun sslContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        // A Robolectric test earlier in this JVM puts Conscrypt first, and its handshake fails on JDK 17.
        return SSLContext.getInstance("TLS", "SunJSSE").apply {
            init(arrayOf(SingleKeyKeyManager(certificate(), privateKey())), trustAll, SecureRandom())
        }
    }

    private fun certificate(): X509Certificate =
        File(RAW, "cert").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun privateKey() = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                File(RAW, "privkey").readText()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
            )
        )
    )

    private companion object {
        /** Gradle runs unit tests with the module directory as the working directory. */
        const val RAW = "src/main/res/raw"
    }
}
