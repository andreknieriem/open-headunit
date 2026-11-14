package com.andrerinas.openheadunit.connection.carplaynative

import java.security.PrivateKey
import java.security.Signature

/** Signs in software with the imported key, by the key's own type. The phone's wanted digest is a later fact. */
class FileSigner(private val key: PrivateKey) : CarPlaySigner {

    override fun sign(challenge: ByteArray): ByteArray {
        val signature = Signature.getInstance(algorithmFor(key.algorithm))
        signature.initSign(key)
        signature.update(challenge)
        return signature.sign()
    }

    companion object {
        fun algorithmFor(keyAlgorithm: String): String = when (keyAlgorithm) {
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> "SHA256withRSA"
        }
    }
}
