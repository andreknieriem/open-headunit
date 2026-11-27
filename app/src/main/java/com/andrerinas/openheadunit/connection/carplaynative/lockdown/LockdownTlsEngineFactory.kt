package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import android.annotation.SuppressLint
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlainBase64
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistException
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Provider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509TrustManager

/**
 * A client TLS engine for lockdown. The pair record root identity is its client certificate, the iPhone certificate is not checked.
 * It sets no endpoint identification (API 24). [provider] lets a JVM test use Conscrypt, as the JDK parser refuses the empty names.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/LockdownTlsEngineFactory.kt`.
 */
object LockdownTlsEngineFactory {
    private const val ALIAS = "lockdown-host"
    private const val PEER_HOST = "Device"
    private const val BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val END = "-----END PRIVATE KEY-----"

    @Throws(GeneralSecurityException::class)
    fun create(record: PairRecord, provider: Provider? = null): SSLEngine {
        val password = CharArray(8) { "lockdown"[it] }
        try {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decodePrivateKey(record.rootPrivateKeyPem)))
            val certificates = if (provider == null) CertificateFactory.getInstance("X.509") else CertificateFactory.getInstance("X.509", provider)
            val certificate = certificates.generateCertificate(ByteArrayInputStream(record.rootCertificatePem)) as X509Certificate
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setKeyEntry(ALIAS, key, password, arrayOf(certificate))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }.keyManagers
            val context = (if (provider == null) SSLContext.getInstance("TLS") else SSLContext.getInstance("TLS", provider)).apply { init(managers, arrayOf(TrustAnyDevice), null) }
            return context.createSSLEngine(PEER_HOST, 0).apply { useClientMode = true }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun decodePrivateKey(pem: ByteArray): ByteArray {
        val text = String(pem, Charsets.US_ASCII)
        val begin = text.indexOf(BEGIN)
        val end = text.indexOf(END)
        if (begin < 0 || end < begin) throw GeneralSecurityException("invalid PKCS#8 PEM")
        return try {
            PlainBase64.decode(text.substring(begin + BEGIN.length, end).filterNot(Char::isWhitespace))
        } catch (e: PlistException) {
            throw GeneralSecurityException("invalid PKCS#8 PEM", e)
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAnyDevice : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
