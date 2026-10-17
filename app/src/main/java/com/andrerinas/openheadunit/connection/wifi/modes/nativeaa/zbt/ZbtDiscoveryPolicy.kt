package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

/** Selects the ZBT discovery procedure from common module evidence and firmware hints. */
object ZbtDiscoveryPolicy {
    enum class Mode { NONE, REACHABILITY, IDENTITY }

    data class Decision(val mode: Mode, val evidence: String?) {
        init { require((mode == Mode.NONE) == (evidence == null)) }
        val available: Boolean get() = mode != Mode.NONE
        val requiresIdentity: Boolean get() = mode == Mode.IDENTITY
        val allowsAndroidFallback: Boolean get() = mode != Mode.REACHABILITY
        val retriesOnPress: Boolean get() = mode == Mode.REACHABILITY
    }

    /** A routing read couples evidence to the result from that decision's cache. */
    data class Snapshot(val decision: Decision, val reachable: Boolean?)

    /**
     * Hold a selected procedure until fresh idle arming. Common module evidence is itself
     * process-latched by ExternalBtPolicy, so reconsidering it still preserves established
     * handling. NONE is re-read because firmware metadata and properties can appear after
     * boot. Late property publication cannot swap an identity decision to the other cache.
     */
    class Latch(private val read: () -> Decision) {
        private var held: Decision? = null

        /** Called only at a fresh arming; a retiring or active carrier still owns its decision. */
        @Synchronized
        fun reconsiderForArming(carrierOwnsSlot: Boolean) {
            if (!carrierOwnsSlot) held = null
        }

        @Synchronized
        fun decision(): Decision {
            held?.let { return it }
            return read().also { if (it.available) held = it }
        }
    }

    /**
     * One ordered discovery entry point: common module markers, then HCT, then QF.
     * [externalModuleEvidence] uses the process-latched
     * [com.andrerinas.openheadunit.utils.ExternalBtPolicy.detect] checks:
     * /dev/rf_serial, /dev/zj_bt_serial, rw.zlink.bt.type=extra and rw.zj.bt.type=extra.
     * Reuse BluetoothHelper.externalBtEvidence rather than copying those checks or their latch.
     * ExternalBtPolicy documents their source in the reference software and the observed
     * external-module unit; that evidence does not establish HCT/QF compatibility.
     * The common detector remains useful to other transports: its markers do not prove that
     * ZBT is installed. An existing marker keeps the established reachability
     * procedure and avoids unnecessary firmware reads. [Latch] prevents a later marker
     * from replacing an identity decision during an active attempt.
     *
     * HCT QCM6125 Android 13 starts fscsdkbw123 for bt.goc.type=13. The daemon contains
     * libzbt-main.so loading and RFCOMM registration code, not proof of a loaded library.
     * The relay node alone does not establish the backend, so require the matching daemon.
     * An exported HCT Bluetooth service substitutes for a relay node hidden by SELinux.
     *
     * QF K706 Android 10's gocsdk_zj loader tries libzbt-main.so in the ZLink app's
     * private directory, /usr/z-loader/lib and other locations, then the bare library
     * name, /oem/lib and finally /system/lib (disassembly: sub_7bf10, logging
     * "open_libzbt_so"). The inspected library matches the Dasaita ZLink TCP 3152
     * implementation. Library visibility to this app
     * is not required: the daemon can load a copy outside /system/lib or behind app access
     * restrictions. The known loader filename only makes the unit a probe candidate;
     * QF also ships gocsdk_lt, so it does not identify the active backend. Other gocsdk
     * variants are not ZBT evidence. Do not require the runtime-created goc_serial node
     * either: boot timing or SELinux can hide it.
     *
     * Firmware hints only authorize a passive InitInfo probe. Missing or unrecognized replies
     * keep Android Bluetooth available. These checks neither open the UART nor start a daemon.
     */
    fun detect(
        externalModuleEvidence: () -> String?,
        nodeExists: (String) -> Boolean,
        hctServiceAvailable: () -> Boolean = { false }
    ): Decision {
        val externalEvidence = externalModuleEvidence()
        return when {
            externalEvidence != null -> Decision(Mode.REACHABILITY, externalEvidence)
            nodeExists("/system/bin/fscsdkbw123") &&
                (nodeExists("/dev/goc_serial") || hctServiceAvailable()) ->
                Decision(Mode.IDENTITY, "HCT Bluetooth bridge with fscsdkbw123")
            nodeExists("/system/bin/gocsdk_zj") ->
                Decision(Mode.IDENTITY, "QF gocsdk_zj loader")
            else -> Decision(Mode.NONE, null)
        }
    }
}
