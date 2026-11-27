package com.andrerinas.openheadunit.connection.carplaynative.airplay

/**
 * The paired controllers, by identifier, with their long-term Ed25519 public keys.
 * Follows xcertplay airplay/PairingStore.kt (https://github.com/shilapi/xcertplay).
 */
class PairingStore(
    /** Called for each entry the cap drops, so the saved copy drops it too. */
    private val onDrop: ((String) -> Unit)? = null,
    private val onSave: ((String, ByteArray) -> Unit)? = null,
) {
    // Oldest first. The setup code is public, so a peer can pair again and again with new identifiers.
    private val entries = LinkedHashMap<String, ByteArray>()

    @Synchronized
    fun save(identifier: String, longTermPublicKey: ByteArray) {
        val copy = longTermPublicKey.copyOf()
        put(identifier, copy)
        onSave?.invoke(identifier, copy.copyOf())
        trim()
    }

    @Synchronized
    fun get(identifier: String): ByteArray? = entries[identifier]?.copyOf()

    /** Loads a stored entry without calling [onSave]. */
    @Synchronized
    fun restore(identifier: String, longTermPublicKey: ByteArray) {
        put(identifier, longTermPublicKey.copyOf())
        trim()
    }

    private fun put(identifier: String, key: ByteArray) {
        entries.remove(identifier)
        entries[identifier] = key
    }

    private fun trim() {
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.first()
            entries.remove(oldest)
            onDrop?.invoke(oldest)
        }
    }

    companion object {
        const val MAX_ENTRIES = 16
    }
}
