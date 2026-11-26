package com.andrerinas.openheadunit.connection.carplaynative

import android.content.Context
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Reads the user's own PKCS#7 certificate and unencrypted PKCS#8 key (P-256 or RSA), DER or PEM.
 * The app ships and logs none of it. Ported from LIVI (https://github.com/f-io/LIVI), crate iap2-mfi.
 */
object CarPlayIdentityImport {

    sealed class Result {
        /** [certificate] is the PKCS#7 DER the phone receives unchanged. */
        class Ok(val certificate: ByteArray, val protocolMajor: Int, val key: PrivateKey) : Result()
        object WrongKey : Result()
        object Corrupt : Result()
        object WrongFormat : Result()
    }

    private enum class Role { CERTIFICATE, KEY, OTHER }

    private class Decoded(val label: String?, val der: ByteArray)

    private class Failure(val result: Result) : Exception()

    private val PEM_BLOCK = Regex("-----BEGIN ([A-Z0-9 ]*)-----([\\s\\S]*?)-----END \\1-----")
    private val PEM_BEGIN = Regex("-----BEGIN ([A-Z0-9 ]*)-----")
    private const val KEY_LABEL = "PRIVATE KEY"
    private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    /** One file holds both halves, or two files hold one each in any order. A third file is refused. */
    fun parseFiles(files: List<ByteArray>, base64: (String) -> ByteArray): Result = when (files.size) {
        1 -> parseCombined(files[0], base64)
        2 -> {
            val roles = files.map { roleOf(it) }
            val cert = roles.indexOf(Role.CERTIFICATE)
            val key = roles.indexOf(Role.KEY)
            if (cert < 0 || key < 0) Result.WrongFormat else parse(files[cert], files[key], base64)
        }
        else -> Result.WrongFormat
    }

    /** One PEM file with a certificate block and a key block. */
    fun parseCombined(bytes: ByteArray, base64: (String) -> ByteArray): Result {
        val text = pemText(bytes) ?: return Result.WrongFormat
        val blocks = PEM_BLOCK.findAll(text).toList()
        val keyBlock = blocks.firstOrNull { it.groupValues[1].contains(KEY_LABEL) }
        val certBlock = blocks.firstOrNull { !it.groupValues[1].contains(KEY_LABEL) }
        if (keyBlock == null || certBlock == null) {
            // A cut file has a BEGIN line with no END to match it.
            return if (PEM_BEGIN.findAll(text).count() != blocks.size) Result.Corrupt else Result.WrongFormat
        }
        return parse(certBlock.value.toByteArray(), keyBlock.value.toByteArray(), base64)
    }

    /** [base64] is a function because `java.util.Base64` is API 26 and this flavor runs on API 16. */
    fun parse(certificate: ByteArray, key: ByteArray, base64: (String) -> ByteArray): Result {
        try {
            val cert = decode(certificate, base64)
            val keyDer = decode(key, base64)
            when (Der.kind(cert.der)) {
                Der.Kind.SIGNED_DATA -> Unit
                Der.Kind.UNKNOWN -> throw Failure(if (cert.label != null) Result.Corrupt else Result.WrongFormat)
                else -> throw Failure(Result.WrongFormat)
            }
            if (keyDer.label != null && keyDer.label != KEY_LABEL) return Result.WrongFormat
            when (Der.kind(keyDer.der)) {
                Der.Kind.PKCS8 -> Unit
                Der.Kind.UNKNOWN -> throw Failure(if (keyDer.label != null) Result.Corrupt else Result.WrongFormat)
                else -> throw Failure(Result.WrongFormat)
            }
            val privateKey = parseKey(keyDer.der) ?: return Result.Corrupt
            if (privateKey is ECPrivateKey && privateKey.params.order != P256_ORDER) return Result.WrongFormat
            val leaf = leafOf(cert.der) ?: return Result.Corrupt
            val signer = FileSigner(cert.der, privateKey)
            return if (matches(signer, leaf)) Result.Ok(cert.der, signer.protocolMajor(), privateKey) else Result.WrongKey
        } catch (f: Failure) {
            return f.result
        }
    }

    private fun pemText(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.ISO_8859_1)
        return if (text.trimStart().startsWith("-----BEGIN")) text else null
    }

    /** PEM or raw DER to DER. A PEM block that is cut or not base64 is corrupt. */
    private fun decode(bytes: ByteArray, base64: (String) -> ByteArray): Decoded {
        val text = pemText(bytes) ?: return Decoded(null, bytes)
        val block = PEM_BLOCK.find(text) ?: throw Failure(Result.Corrupt)
        val der = try {
            base64(block.groupValues[2].replace(Regex("\\s"), ""))
        } catch (e: Exception) {
            throw Failure(Result.Corrupt)
        }
        return Decoded(block.groupValues[1], der)
    }

    private fun roleOf(bytes: ByteArray): Role {
        val text = pemText(bytes)
        if (text != null) {
            val labels = PEM_BEGIN.findAll(text).map { it.groupValues[1] }.toList()
            val hasKey = labels.any { it.contains(KEY_LABEL) }
            val hasCert = labels.any { !it.contains(KEY_LABEL) }
            return when {
                hasKey && !hasCert -> Role.KEY
                hasCert && !hasKey -> Role.CERTIFICATE
                else -> Role.OTHER
            }
        }
        return when (Der.kind(bytes)) {
            Der.Kind.SIGNED_DATA, Der.Kind.X509 -> Role.CERTIFICATE
            Der.Kind.PKCS8, Der.Kind.ENCRYPTED_PKCS8 -> Role.KEY
            Der.Kind.UNKNOWN -> Role.OTHER
        }
    }

    private fun parseKey(der: ByteArray): PrivateKey? {
        val spec = PKCS8EncodedKeySpec(der)
        for (algorithm in listOf("RSA", "EC")) {
            try { return KeyFactory.getInstance(algorithm).generatePrivate(spec) } catch (_: Exception) { }
        }
        return null
    }

    /** The certificate no other certificate in the set was issued by. */
    private fun leafOf(pkcs7: ByteArray): X509Certificate? = try {
        val all = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pkcs7))
            .filterIsInstance<X509Certificate>()
        all.firstOrNull { c -> all.none { it !== c && it.issuerX500Principal == c.subjectX500Principal } } ?: all.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** A digest signed through [signer] that the certificate's key verifies over the message proves the pair. */
    private fun matches(signer: FileSigner, leaf: X509Certificate): Boolean = try {
        val message = byteArrayOf(1, 2, 3, 4)
        val ec = signer.protocolMajor() == 3
        val digest = MessageDigest.getInstance(if (ec) "SHA-256" else "SHA-1").digest(message)
        val signature = signer.sign(digest)
        val verifier = Signature.getInstance(if (ec) "SHA256withECDSA" else "SHA1withRSA")
        verifier.initVerify(leaf.publicKey)
        verifier.update(message)
        verifier.verify(if (ec) EcdsaSignature.rawToDer(signature) else signature)
    } catch (e: Exception) {
        false
    }
}

/** The one imported identity, in app-private storage, as DER. */
object CarPlayIdentityFile {

    // The key must stay out of every backup, adb backup included, and only `noBackupFilesDir` (API 21) does that.
    // Below API 21 nothing is stored or read.
    fun storable(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.LOLLIPOP

    private fun dir(context: Context): File = File(context.noBackupFilesDir, "carplay")

    fun certificateFile(context: Context): File = File(dir(context), "certificate.p7b")

    fun keyFile(context: Context): File = File(dir(context), "identity.pk8")

    /** False below API 21, where nothing is written. */
    fun save(context: Context, identity: CarPlayIdentityImport.Result.Ok, sdk: Int = Build.VERSION.SDK_INT): Boolean {
        if (!storable(sdk)) return false
        dir(context).mkdirs()
        certificateFile(context).writeBytes(identity.certificate)
        // PKCS#8 DER is what a Java private key encodes to.
        keyFile(context).writeBytes(identity.key.encoded)
        return true
    }

    /** Both files exist: a stat, no parse. */
    fun present(context: Context, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        storable(sdk) && certificateFile(context).isFile && keyFile(context).isFile

    /** The signer for the stored files, or null when a file is missing or no longer valid. */
    fun signer(context: Context, base64: (String) -> ByteArray, sdk: Int = Build.VERSION.SDK_INT): FileSigner? {
        if (!storable(sdk)) return null
        val cert = certificateFile(context)
        val key = keyFile(context)
        if (!cert.isFile || !key.isFile) return null
        val ok = CarPlayIdentityImport.parse(cert.readBytes(), key.readBytes(), base64) as? CarPlayIdentityImport.Result.Ok
        return ok?.let { FileSigner(it.certificate, it.key) }
    }

    fun isValid(context: Context, base64: (String) -> ByteArray, sdk: Int = Build.VERSION.SDK_INT): Boolean = signer(context, base64, sdk) != null
}
