package com.andrerinas.openheadunit.connection.carplaynative

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** Reads the user's own certificate and key from one PEM file. The app ships and logs none of it. */
object CarPlayIdentityImport {

    sealed class Result {
        class Ok(val certificate: X509Certificate, val key: PrivateKey) : Result()
        object WrongKey : Result()
        object Corrupt : Result()
        object WrongFormat : Result()
    }

    /** [base64] is a function because `java.util.Base64` is API 26 and this flavor runs on API 16. */
    fun parse(bytes: ByteArray, base64: (String) -> ByteArray): Result {
        val text = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { return Result.WrongFormat }
        val certBody = block(text, "CERTIFICATE") ?: block(text, "PKCS7")
        val keyBody = block(text, "PRIVATE KEY")
        if (!text.contains("-----BEGIN")) return Result.WrongFormat
        if (certBody == null || keyBody == null) {
            // A cut file has a BEGIN line with no END to match it.
            return if (text.split("-----BEGIN").size != text.split("-----END").size) Result.Corrupt else Result.WrongFormat
        }
        val cert: X509Certificate
        val key: PrivateKey
        try {
            val certDer = base64(certBody.replace(Regex("\\s"), ""))
            cert = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(certDer)).filterIsInstance<X509Certificate>().first()
            val keyDer = base64(keyBody.replace(Regex("\\s"), ""))
            key = parseKey(keyDer) ?: return Result.Corrupt
        } catch (e: Exception) {
            return Result.Corrupt
        }
        return if (matches(cert, key)) Result.Ok(cert, key) else Result.WrongKey
    }

    /** The text between a BEGIN and END line whose label ends with [label], or null. */
    private fun block(text: String, label: String): String? {
        val match = Regex("-----BEGIN ([A-Z0-9 ]*)-----([\\s\\S]*?)-----END \\1-----").findAll(text)
            .firstOrNull { it.groupValues[1] == label } ?: return null
        return match.groupValues[2]
    }

    private fun parseKey(der: ByteArray): PrivateKey? {
        val spec = PKCS8EncodedKeySpec(der)
        for (algorithm in listOf("RSA", "EC")) {
            try { return KeyFactory.getInstance(algorithm).generatePrivate(spec) } catch (_: Exception) { }
        }
        return null
    }

    /** A signature the certificate's public key verifies proves the two halves belong together. */
    private fun matches(cert: X509Certificate, key: PrivateKey): Boolean = try {
        val algorithm = FileSigner.algorithmFor(key.algorithm)
        val probe = byteArrayOf(1, 2, 3, 4)
        val signer = Signature.getInstance(algorithm)
        signer.initSign(key)
        signer.update(probe)
        val signature = signer.sign()
        val verifier = Signature.getInstance(algorithm)
        verifier.initVerify(cert.publicKey)
        verifier.update(probe)
        verifier.verify(signature)
    } catch (e: Exception) {
        false
    }
}

/** The one imported identity, in app-private storage. */
object CarPlayIdentityFile {

    fun file(context: Context): File = File(File(context.filesDir, "carplay"), "identity.pem")

    fun save(context: Context, bytes: ByteArray) {
        val target = file(context)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    fun isValid(context: Context, base64: (String) -> ByteArray): Boolean {
        val f = file(context)
        if (!f.isFile) return false
        return CarPlayIdentityImport.parse(f.readBytes(), base64) is CarPlayIdentityImport.Result.Ok
    }
}
