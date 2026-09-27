package com.andrerinas.openheadunit.connection.wifi.direct

import kotlin.random.Random

/** Why a typed network name or password cannot be used. */
enum class P2pIdentityRejection { NAME_SHAPE, PASSPHRASE_LENGTH, PASSPHRASE_CHARSET }

/** What a user's edit of the WiFi Direct name and password comes to. */
sealed class P2pIdentityEdit {
    /** What was typed is already what is stored. */
    object Unchanged : P2pIdentityEdit()

    /** Both fields left empty: back to a pair the app draws itself. */
    object Cleared : P2pIdentityEdit()

    /** Nothing is stored; [why] says which field to tell the user about. */
    data class Rejected(val why: P2pIdentityRejection) : P2pIdentityEdit()

    /** [identity] is the pair to store. [recoded] when the name's code was redrawn; see the policy. */
    data class Accepted(
        val identity: StoredP2pIdentity,
        val recoded: Boolean,
        val reason: String,
    ) : P2pIdentityEdit()
}

/**
 * What a typed network name and password do to the kept pair.
 *
 * A changed password forces a changed name, because a name reused with a different password is the
 * one combination a phone's saved profile cannot recover from. A changed name does not force a
 * changed password: that is the one thing the user came here to choose.
 */
object P2pIdentityEditPolicy {

    fun edit(
        current: StoredP2pIdentity?,
        typedName: String,
        typedPassphrase: String,
        deviceName: String?,
        random: Random = Random.Default,
    ): P2pIdentityEdit {
        val name = typedName.trim()
        val passphrase = typedPassphrase.trim()
        if (name.isEmpty() && passphrase.isEmpty()) return P2pIdentityEdit.Cleared

        val code = current?.networkName?.let { P2pGroupIdentityPolicy.codeOf(it) }
            ?: P2pGroupIdentityPolicy.newCode(random)
        // A bare "MyCar" is built into the shape the platform demands, so the `DIRECT-xy-` prefix
        // never has to be typed and the two-character code stays ours to redraw below.
        val resolvedName = when {
            name.isEmpty() -> current?.networkName ?: P2pGroupIdentityPolicy.networkName(code, deviceName)
            name.startsWith(P2pGroupIdentityPolicy.NAME_PREFIX) -> name
            else -> P2pGroupIdentityPolicy.networkName(code, name)
        }
        if (!P2pGroupIdentityPolicy.isValidName(resolvedName)) {
            return P2pIdentityEdit.Rejected(P2pIdentityRejection.NAME_SHAPE)
        }

        val resolvedPassphrase = passphrase.ifEmpty {
            current?.passphrase ?: P2pGroupIdentityPolicy.newPassphrase(random)
        }
        if (!P2pGroupIdentityPolicy.isValidPassphrase(resolvedPassphrase)) {
            val why =
                if (resolvedPassphrase.length !in
                    P2pGroupIdentityPolicy.MIN_PASSPHRASE_LENGTH..P2pGroupIdentityPolicy.MAX_PASSPHRASE_LENGTH
                ) P2pIdentityRejection.PASSPHRASE_LENGTH
                else P2pIdentityRejection.PASSPHRASE_CHARSET
            return P2pIdentityEdit.Rejected(why)
        }

        if (current != null &&
            resolvedName == current.networkName &&
            resolvedPassphrase == current.passphrase
        ) return P2pIdentityEdit.Unchanged

        val passphraseMoved = current != null && resolvedPassphrase != current.passphrase
        val nameHeld = current != null && resolvedName == current.networkName
        if (passphraseMoved && nameHeld) {
            val recoded = P2pGroupIdentityPolicy.withCode(resolvedName, P2pGroupIdentityPolicy.newCode(random))
            return P2pIdentityEdit.Accepted(
                identity = StoredP2pIdentity(recoded, resolvedPassphrase),
                recoded = true,
                reason = "group identity: the password changed, so the network is now $recoded, " +
                    "because the same name with a new password is what a phone cannot rejoin.",
            )
        }
        return P2pIdentityEdit.Accepted(
            identity = StoredP2pIdentity(resolvedName, resolvedPassphrase),
            recoded = false,
            reason = "group identity: $resolvedName and the password with it are what the user typed.",
        )
    }
}
