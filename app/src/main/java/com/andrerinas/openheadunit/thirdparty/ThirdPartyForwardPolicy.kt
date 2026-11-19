package com.andrerinas.openheadunit.thirdparty

import java.security.MessageDigest

/**
 * What may leave the app and to whom. Pure logic, no Android types, so it can be tested on the JVM.
 *
 * The receiving app is identified by its package name **and** the SHA-256 digest of its signing certificate(s), pinned when the
 * user adds it. If the installed package no longer has that digest (it was replaced by an app signed with another key), nothing
 * is sent to it. This stands in for a signature-level permission, which would force every receiving app to be signed with this
 * app's key.
 */
object ThirdPartyForwardPolicy {

    data class Entry(val pkg: String, val certDigest: String)

    const val CHANNEL_NAV = "nav"
    const val CHANNEL_MEDIA = "media"

    const val MEDIA_METADATA_MSG = 32771
    private const val MEDIA_ALBUM_ART_FIELD = 4
    const val NAV_NEXT_TURN_MSG = 32772
    private const val NAV_TURN_IMAGE_FIELD = 4   // the turn icon of the legacy next-turn message: bulky, and the receiver draws its own

    /** the messages of the contract: navigation status, state and position, media metadata and playback status */
    fun isForwarded(channel: String, msg: Int): Boolean = when (channel) {
        CHANNEL_NAV -> msg == 32769 || msg == 32770 || msg == 32771 || msg == 32772 || msg == 32773 || msg == 32774 || msg == 32775
        CHANNEL_MEDIA -> msg == 32769 || msg == MEDIA_METADATA_MSG
        else -> false
    }

    /** digest of a set of signing certificates (order independent) */
    fun digestOf(certificates: List<ByteArray>): String =
        certificates.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")

    /** the whitelisted packages whose installed signature still matches the pinned one */
    fun recipients(entries: List<Entry>, installedDigest: (String) -> String?): List<String> =
        entries.filter { e -> installedDigest(e.pkg).let { it != null && it == e.certDigest } }.map { it.pkg }

    /** the payload to forward, or null if it must not be forwarded at all */
    fun payloadFor(channel: String, msg: Int, payload: ByteArray, includeAlbumArt: Boolean): ByteArray? {
        if (channel == CHANNEL_MEDIA && msg == MEDIA_METADATA_MSG && !includeAlbumArt) {
            return ProtoFieldStripper.removeField(payload, MEDIA_ALBUM_ART_FIELD)
        }
        if (channel == CHANNEL_NAV && msg == NAV_NEXT_TURN_MSG) {
            return ProtoFieldStripper.removeField(payload, NAV_TURN_IMAGE_FIELD)
        }
        return payload
    }
}
