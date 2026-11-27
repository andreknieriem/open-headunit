package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.utils.AppLog
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/** The saved controllers as bytes: for each, a 2-byte id length, the id as UTF-8, and its 32-byte Ed25519 key. */
object AirPlayPairingFile {
    private const val KEY_BYTES = 32

    fun encode(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((id, key) in entries) {
            val name = id.toByteArray(Charsets.UTF_8)
            if (name.size > 0xFFFF || key.size != KEY_BYTES) continue
            out.write(name.size shr 8); out.write(name.size and 0xff)
            out.write(name)
            out.write(key)
        }
        return out.toByteArray()
    }

    /** The entries in file order. A cut or garbled file gives the entries before the fault. */
    fun decode(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        val input = DataInputStream(bytes.inputStream())
        try {
            while (input.available() > 0) {
                val name = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                val key = ByteArray(KEY_BYTES).also { input.readFully(it) }
                entries[String(name, Charsets.UTF_8)] = key
            }
        } catch (_: IOException) {
            // A cut file keeps the entries read so far.
        }
        return entries
    }
}

/**
 * The AirPlay identity and the controllers it paired with, in one private folder. The files hold a private key and
 * public keys, so nothing here logs a byte of them. Writes go to a temporary file first, then replace the old one.
 */
class AirPlayStorage(private val directory: File) {
    private val lock = Any()
    private val saved = LinkedHashMap<String, ByteArray>()
    private var loaded = false

    fun identity(): AirPlayIdentity = AirPlayIdentity.loadOrCreate(
        read = { file(IDENTITY).takeIf { it.isFile }?.readBytes() },
        write = { write(file(IDENTITY), it) },
    )

    /** A store with the saved controllers. A change to it, and a controller it drops at the cap, reach the file. */
    fun pairings(): PairingStore {
        val store = PairingStore(onDrop = ::forget, onSave = ::remember)
        synchronized(lock) {
            load()
            saved.forEach { (id, key) -> store.restore(id, key) }
        }
        return store
    }

    private fun load() {
        if (loaded) return
        loaded = true
        file(PAIRINGS).takeIf { it.isFile }?.let { saved.putAll(AirPlayPairingFile.decode(it.readBytes())) }
    }

    private fun remember(id: String, key: ByteArray) = synchronized(lock) {
        load()
        saved.remove(id)
        saved[id] = key
        persist()
    }

    private fun forget(id: String) = synchronized(lock) {
        load()
        if (saved.remove(id) != null) persist()
    }

    private fun persist() = write(file(PAIRINGS), AirPlayPairingFile.encode(saved))

    private fun file(name: String) = File(directory, name)

    private fun write(target: File, bytes: ByteArray) {
        try {
            directory.mkdirs()
            val temporary = File(directory, "${target.name}.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) AppLog.w("CarPlayNative: ${target.name} could not be saved")
            }
        } catch (e: IOException) {
            AppLog.w("CarPlayNative: ${target.name} could not be saved (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        const val IDENTITY = "identity.bin"
        const val PAIRINGS = "pairings.bin"
    }
}
