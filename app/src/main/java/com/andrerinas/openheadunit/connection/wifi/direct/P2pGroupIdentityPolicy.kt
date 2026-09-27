package com.andrerinas.openheadunit.connection.wifi.direct

import kotlin.random.Random

/** The identity a Native AA P2P group is asked for. */
sealed class P2pGroupIdentity {

    /**
     * A group this app names. [persistent] asks the platform to keep the profile, so the same
     * name and passphrase come back on the next create instead of a temporary group's fresh pair.
     */
    data class Named(
        val networkName: String,
        val passphrase: String,
        val persistent: Boolean,
    ) : P2pGroupIdentity()

    /** The plain two-argument createGroup, whose profile the platform already keeps on its own. */
    object FrameworkProfile : P2pGroupIdentity()
}

/** The pair the app keeps between bring-ups. */
data class StoredP2pIdentity(val networkName: String, val passphrase: String)

/**
 * A group as it was actually read back off the air, whatever named it.
 *
 * Kept because the settings screen stops the wireless stack before it draws, so nothing live is
 * there to ask; and below API 29 this is the only place the platform's own pair is ever legible.
 */
data class ObservedP2pCredentials(
    val networkName: String,
    val passphrase: String,
    val bssid: String,
)

/**
 * Names the Native AA WiFi Direct group, and decides whether the name outlives the group.
 *
 * Every create used to mint a new name and passphrase and build a temporary group, so the phone
 * was provisioned for a new network on every session and could never rejoin one it had saved.
 * A kept pair, asked for as a persistent group, makes a reconnect a saved-network join. The name
 * and passphrase are only ever replaced together: a name reused with a different passphrase is
 * the one combination a phone's stored profile cannot recover from without help.
 */
object P2pGroupIdentityPolicy {

    const val NAME_PREFIX = "DIRECT-"
    const val MAX_NAME_BYTES = 32
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MAX_PASSPHRASE_LENGTH = 63
    const val PASSPHRASE_LENGTH = 12
    const val DEFAULT_SUFFIX = "HeadUnit"
    private const val MAX_SUFFIX_CHARS = 20

    private val CODE_POOL = ('A'..'Z') + ('0'..'9')
    private val PASSPHRASE_POOL = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    private val NAME_SHAPE = Regex("^DIRECT-[a-zA-Z0-9]{2}.*")

    /** What to ask for, what to write back if a pair was just drawn, and why, for the log. */
    data class Choice(
        val identity: P2pGroupIdentity.Named,
        val toStore: StoredP2pIdentity?,
        val reason: String,
    )

    fun decide(
        keepIdentity: Boolean,
        stored: StoredP2pIdentity?,
        deviceName: String?,
        random: Random = Random.Default,
        userSet: Boolean = false,
    ): Choice {
        if (!keepIdentity) {
            val fresh = mint(deviceName, random)
            return Choice(
                identity = P2pGroupIdentity.Named(fresh.networkName, fresh.passphrase, persistent = false),
                toStore = null,
                reason = "group identity: a new network on every create (${fresh.networkName}), " +
                    "as the setting asks; the phone will be set up for it over Bluetooth.",
            )
        }
        if (stored != null && isValid(stored)) {
            return Choice(
                identity = P2pGroupIdentity.Named(stored.networkName, stored.passphrase, persistent = true),
                toStore = null,
                reason = if (userSet)
                    "group identity: asking for ${stored.networkName}, the network name and password " +
                        "the user typed, so the phone is handed exactly those."
                else
                    "group identity: asking for the kept network ${stored.networkName} again, " +
                        "so a phone that saved it can rejoin without being set up for a new one.",
            )
        }
        val fresh = mint(deviceName, random)
        return Choice(
            identity = P2pGroupIdentity.Named(fresh.networkName, fresh.passphrase, persistent = true),
            toStore = fresh,
            reason = "group identity: no kept network yet" +
                (if (stored != null) " (the stored one was not usable)" else "") +
                ", so ${fresh.networkName} is drawn now and kept for every later create.",
        )
    }

    /** A new pair. Public so the settings screen's "new identity" action draws exactly this. */
    fun mint(deviceName: String?, random: Random = Random.Default): StoredP2pIdentity =
        StoredP2pIdentity(networkName(newCode(random), deviceName), newPassphrase(random))

    /**
     * `DIRECT-xy-<suffix>`. The shape is the platform's: `WifiP2pConfig.Builder.setNetworkName`
     * rejects anything that does not start with `DIRECT-` and two alphanumerics, or that runs past
     * 32 bytes. The suffix is the unit's WiFi Direct name with everything but letters and digits
     * removed, so it is one byte a character and the bound is a character count.
     */
    fun networkName(code: String, deviceName: String?): String {
        val suffix = deviceName
            ?.filter { it.isLetterOrDigit() && it.code < 128 }
            ?.take(MAX_SUFFIX_CHARS)
            ?.ifEmpty { null } ?: DEFAULT_SUFFIX
        return "$NAME_PREFIX$code-$suffix".take(MAX_NAME_BYTES)
    }

    fun newCode(random: Random = Random.Default): String =
        "${CODE_POOL.random(random)}${CODE_POOL.random(random)}"

    fun newPassphrase(random: Random = Random.Default): String =
        (1..PASSPHRASE_LENGTH).map { PASSPHRASE_POOL.random(random) }.joinToString("")

    /** The two characters after `DIRECT-`, or null when [name] is not the shape this object builds. */
    fun codeOf(name: String): String? =
        if (isValidName(name)) name.substring(NAME_PREFIX.length, NAME_PREFIX.length + 2) else null

    /** [name] carrying a different two-character code, everything after it kept. */
    fun withCode(name: String, code: String): String =
        NAME_PREFIX + code + name.substring(NAME_PREFIX.length + 2)

    fun isValidName(name: String): Boolean =
        NAME_SHAPE.matches(name) && name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES

    fun isValidPassphrase(passphrase: String): Boolean =
        passphrase.length in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH &&
            passphrase.all { it.code in 0x20..0x7E }

    fun isValid(stored: StoredP2pIdentity): Boolean =
        isValidName(stored.networkName) && isValidPassphrase(stored.passphrase)

    /** A network id at or above zero is a stored profile; -1 is a temporary group. */
    fun isPersistentNetworkId(networkId: Int): Boolean = networkId >= 0

    /**
     * One line saying whether the platform gave us the group we asked for.
     *
     * A unit whose framework ignores the request is the one thing this cannot be argued about
     * afterwards, so it is said on every group rather than only when something differs.
     */
    fun describeReadBack(
        requested: P2pGroupIdentity?,
        networkName: String,
        passphrase: String,
        networkId: Int,
    ): String {
        val stored = if (isPersistentNetworkId(networkId)) "yes (netId $networkId)" else "no (temporary)"
        return when (requested) {
            is P2pGroupIdentity.Named -> {
                val matches = requested.networkName == networkName && requested.passphrase == passphrase
                "group identity ssid=$networkName persistent=$stored " +
                    "asked=${if (requested.persistent) "persistent" else "temporary"} " +
                    "matchesRequest=${if (matches) "yes" else "no"}" +
                    if (!matches) " (the platform renamed or re-keyed the group; the kept pair is not what is on the air)"
                    else ""
            }
            P2pGroupIdentity.FrameworkProfile ->
                "group identity ssid=$networkName persistent=$stored asked=framework profile"
            null -> "group identity ssid=$networkName persistent=$stored asked=nothing (not this app's create)"
        }
    }
}
