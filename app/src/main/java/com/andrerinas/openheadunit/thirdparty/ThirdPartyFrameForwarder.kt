package com.andrerinas.openheadunit.thirdparty

import android.content.Context
import android.content.Intent

/**
 * Forwards the raw Android Auto navigation and media-playback messages to the apps on the user's whitelist, unchanged, so that a
 * companion app (for example an instrument-cluster display) does its own parsing and this app stays universal.
 * See contract/README.md ("Frame forwarding"): action, extras and the privacy rules.
 *
 * Nothing is sent when the whitelist is empty. Every broadcast is explicit (setPackage), so no other app can receive it, and
 * the receiving app's signing certificate is checked against the one pinned by the user before every send. Payloads are never
 * logged or stored.
 */
class ThirdPartyFrameForwarder(private val context: Context) {
    // created on the first frame, not at construction: nothing happens (and no preferences are opened) until a message arrives
    private val store by lazy { ThirdPartyAccessStore(context) }
    private val appContext: Context get() = context.applicationContext ?: context
    private var seq = 0L

    /** @param data the message bytes; [offset] to [end] is the payload (without the 2-byte type) */
    fun forward(channel: String, msg: Int, data: ByteArray, offset: Int, end: Int) {
        try {
            val entries = store.entries()
            if (entries.isEmpty() || offset < 0 || end > data.size || offset > end) return
            val pm = appContext.packageManager
            val recipients = ThirdPartyForwardPolicy.recipients(entries) { ThirdPartyAccessStore.installedDigest(pm, it) }
            if (recipients.isEmpty()) return
            // copied: inbound arrays may be borrowed from the TLS layer
            val payload = ThirdPartyForwardPolicy.payloadFor(channel, msg, data.copyOfRange(offset, end), store.includeAlbumArt) ?: return
            val n = seq++
            for (pkg in recipients) {
                appContext.sendBroadcast(
                    Intent(ACTION_FRAME)
                        .setPackage(pkg)
                        .putExtra(EXTRA_VERSION, VERSION)
                        .putExtra(EXTRA_CHANNEL, channel)
                        .putExtra(EXTRA_MSG, msg)
                        .putExtra(EXTRA_PAYLOAD, payload)
                        .putExtra(EXTRA_SEQ, n)
                )
            }
        } catch (e: Exception) {
            // forwarding is a courtesy to other apps: it must never disturb the projection
        }
    }

    companion object {
        const val ACTION_FRAME = "com.andrerinas.openheadunit.AAP_FRAME"
        const val VERSION = 1
        const val EXTRA_VERSION = "v"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_MSG = "msg"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_SEQ = "seq"
    }
}
