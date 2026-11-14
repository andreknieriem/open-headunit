package com.andrerinas.openheadunit.connection.carplaynative

/** Signs a challenge with the user's own Apple identity. No byte goes to a phone in this stage. */
interface CarPlaySigner {
    fun sign(challenge: ByteArray): ByteArray
}
