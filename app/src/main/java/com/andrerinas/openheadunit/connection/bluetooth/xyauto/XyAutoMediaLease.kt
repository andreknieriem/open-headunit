package com.andrerinas.openheadunit.connection.bluetooth.xyauto

import java.io.IOException

/**
 * One projection session temporarily owns the firmware's media policy. All calls are serialized
 * by XyAutoMediaSession. The journal must reach disk before changing the shared property.
 *
 * This is conditional restoration, not a lock: Zlink does not participate in our ownership
 * protocol. A different value relinquishes ownership; another writer using our identical value
 * cannot be distinguished. Concurrent projection apps can therefore still compete for policy.
 */
internal class XyAutoMediaLease(private val port: Port) {
    data class Journal(val previous: String, val boot: String)

    interface Port {
        fun boot(): String?
        fun read(): String?
        fun write(value: String): Boolean
        fun saved(): Journal?
        fun save(record: Journal): Boolean
        fun clear(): Boolean
    }

    enum class Result { ACTIVE, RESTORED, IDLE, BUSY, UNAVAILABLE, RECOVERY_PENDING, CANCELLED }
    private var active = false

    fun acquire(isCurrent: () -> Boolean = { true }): Result {
        if (!isCurrent()) return Result.CANCELLED
        if (active && port.read() == AA) return Result.ACTIVE
        if (release() == Result.RECOVERY_PENDING) return Result.RECOVERY_PENDING
        val boot = port.boot() ?: return Result.UNAVAILABLE
        val previous = port.read() ?: return Result.UNAVAILABLE
        // Only the idle states observed in the XYAuto parser are eligible. Never take over
        // CarPlay, HiCar, another AA owner, or an unknown vendor token.
        if (previous !in setOf("", "none")) return Result.BUSY
        if (!port.save(Journal(previous, boot))) return Result.UNAVAILABLE
        // Recheck after synchronous journal I/O: another projection may have started meanwhile.
        if (port.read() != previous) {
            port.clear()
            return Result.BUSY
        }
        if (!isCurrent()) {
            port.clear() // No property write was attempted by this acquisition.
            return Result.CANCELLED
        }
        val written = port.write(AA)
        if (!written || port.read() != AA) {
            val recovery = release()
            return if (recovery == Result.RECOVERY_PENDING) recovery else Result.UNAVAILABLE
        }
        if (!isCurrent()) {
            val recovery = release()
            return if (recovery == Result.RECOVERY_PENDING) recovery else Result.CANCELLED
        }
        active = true
        return Result.ACTIVE
    }

    fun release(): Result {
        active = false
        val record = try { port.saved() } catch (_: IOException) {
            // An unreadable journal is not proof of ownership. Never invent its previous
            // value. Once AA policy is absent, the unusable record can safely be discarded.
            val current = port.read()
            return if (current != null && current != AA && port.clear()) Result.RESTORED
                else Result.RECOVERY_PENDING
        } ?: return Result.IDLE
        val boot = port.boot() ?: return Result.RECOVERY_PENDING
        val previous = record.previous
        val current = port.read() ?: return Result.RECOVERY_PENDING
        // BOOT_COUNT identifies framework boots, not proof that init cleared this property.
        // A framework restart may leave our AA value alive; a full reboot may create a new
        // AA owner. In either case retain ambiguous recovery data and do not write over AA.
        if (record.boot != boot) {
            return if (current != AA && port.clear()) Result.RESTORED else Result.RECOVERY_PENDING
        }
        if (current == AA && (!port.write(previous) || port.read() != previous)) {
            // Leave the journal intact for the next service startup. A failed restore is not
            // success, and clearing it would strand the firmware in projection media policy.
            return Result.RECOVERY_PENDING
        }
        return if (port.clear()) Result.RESTORED else Result.RECOVERY_PENDING
    }

    companion object { const val AA = "auto_wireless" }
}
