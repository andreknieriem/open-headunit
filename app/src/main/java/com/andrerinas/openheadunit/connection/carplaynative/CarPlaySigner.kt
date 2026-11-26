package com.andrerinas.openheadunit.connection.carplaynative

/** The Apple identity a CarPlay session signs with. No byte goes to a phone in this stage. */
interface CarPlaySigner {
    /** 2 is RSA with SHA-1, 3 is ECDSA P-256 with SHA-256. */
    fun protocolMajor(): Int

    /** DER PKCS#7, sent to the phone unchanged. */
    fun certificate(): ByteArray

    /** Signs a digest the caller already made. A signer never hashes it again. */
    fun sign(challenge: ByteArray): ByteArray
}

/** A chip or a Link that did not answer or refused. A bad argument is an IllegalArgumentException. */
class SignerException(message: String) : Exception(message)

/** The challenge sizes the chip and the Link service accept. */
object MfiLimits {
    const val CHALLENGE_MIN = 1
    const val CHALLENGE_MAX = 128
}
