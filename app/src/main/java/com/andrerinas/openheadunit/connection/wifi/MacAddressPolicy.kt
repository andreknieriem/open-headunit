package com.andrerinas.openheadunit.connection.wifi

/** What an address says about itself, which is all we get to ask. */
enum class MacAddressOrigin {
    /** The locally-administered bit is set, so the platform made this one up. */
    GENERATED,
    /** Globally unique, so it is the interface's own and comes back with it. */
    FACTORY,
    /** Not an address: wrong shape, blank, or one of the masking placeholders. */
    UNREADABLE,
}

/**
 * The one answer to what an address is, what it looks like written down, and where it came from.
 *
 * Seventeen places used to ask this, with five spellings of "no address" and two case conventions
 * between them. The ranking layers ([SoftApBssidPolicy], [P2pBssidSourcePolicy]) still decide which
 * source wins; this only says whether a string is an address at all.
 */
object MacAddressPolicy {

    /** Six hex pairs separated by colons or dashes. */
    private val SEPARATED = Regex("^[0-9A-F]{2}([:-][0-9A-F]{2}){5}$")

    /** The same twelve digits with nothing between them. Vendor properties publish this form. */
    private val BARE = Regex("^[0-9A-F]{12}$")

    /**
     * Android's masking placeholders. Note what is not here: anything merely beginning with `02:`.
     * That bit marks a locally-administered MAC and Android's own soft AP routinely uses a
     * randomised one, so rejecting the range would discard the address this exists to find.
     */
    private val PLACEHOLDERS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /** Bit 1 of the first octet: set means locally administered, clear means globally unique. */
    private const val LOCALLY_ADMINISTERED = 0x02

    /**
     * [raw] as canonical `AA:BB:CC:DD:EE:FF`, or null when it is not an address.
     *
     * [allowBare] accepts the separator-less form, and only Bluetooth passes it: `persist.zj.BTmac`
     * reads `008761BF6706` on the ZJ units, and a separator-only check threw that away, so those
     * units announced no Bluetooth service and Android Auto kept calls on the phone.
     */
    fun parse(raw: String?, allowBare: Boolean = false): String? {
        val trimmed = raw?.trim()?.uppercase() ?: return null
        val hex = when {
            SEPARATED.matches(trimmed) -> trimmed.replace("-", "").replace(":", "")
            allowBare && BARE.matches(trimmed) -> trimmed
            else -> return null
        }
        val canonical = hex.chunked(2).joinToString(":")
        return if (canonical in PLACEHOLDERS) null else canonical
    }

    /** Whether [raw] is a real address rather than absent, malformed or a masking placeholder. */
    fun isUsable(raw: String?, allowBare: Boolean = false): Boolean = parse(raw, allowBare) != null

    /** The first of [candidates] that is an address, canonical, or null if none is. */
    fun firstUsable(candidates: List<String?>, allowBare: Boolean = false): String? =
        candidates.firstNotNullOfOrNull { parse(it, allowBare) }

    /**
     * Whether the platform generated [raw] for one network or it belongs to the interface.
     *
     * No API reports whether this unit randomises: the overlay is not portable and the supplicant's
     * config is not ours to read. The address answers instead, because AOSP builds a random MAC with
     * `MacAddress.createRandomUnicastAddress()`, which always sets the locally-administered bit.
     */
    fun origin(raw: String?): MacAddressOrigin {
        val canonical = parse(raw, allowBare = true) ?: return MacAddressOrigin.UNREADABLE
        val firstOctet = canonical.substring(0, 2).toInt(16)
        return if (firstOctet and LOCALLY_ADMINISTERED != 0) MacAddressOrigin.GENERATED
        else MacAddressOrigin.FACTORY
    }

    /**
     * One word for the read-back line, so a reporter's capture says which kind of unit theirs is
     * without a round. Every group address measured so far, on three units, has been generated.
     */
    fun label(raw: String?): String = when (origin(raw)) {
        MacAddressOrigin.GENERATED -> "generated"
        MacAddressOrigin.FACTORY -> "factory"
        MacAddressOrigin.UNREADABLE -> "unreadable"
    }
}
