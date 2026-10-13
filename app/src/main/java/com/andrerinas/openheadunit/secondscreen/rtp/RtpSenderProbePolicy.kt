package com.andrerinas.openheadunit.secondscreen.rtp

/**
 * Reads `/proc/net/udp` text for a second sender on the push's destination. An unconnected sender
 * shows no remote, so the list names who holds a UDP socket, not who sends.
 */
object RtpSenderProbePolicy {

    private const val FIRST_APP_UID = 10000
    private const val MAX_UIDS = 8
    private const val UID_COLUMN = 7

    fun socketSummary(
        tables: List<String>,
        destination: ByteArray,
        port: Int,
        ownUid: Int,
        nameOf: (Int) -> String,
    ): String {
        val hex = destination.reversed().joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        val portHex = String.format("%04X", port and 0xFFFF)
        val text = destination.joinToString(".") { (it.toInt() and 0xFF).toString() } + ":$port"
        val to = sortedSetOf<Int>()
        val apps = sortedMapOf<Int, Int>()
        for (table in tables) for (line in table.lineSequence()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size <= UID_COLUMN || !f[0].endsWith(":")) continue
            val uid = f[UID_COLUMN].toIntOrNull() ?: continue
            if (f[2].endsWith("$hex:$portHex") || f[1].endsWith(":$portHex")) to.add(uid)
            if (uid >= FIRST_APP_UID) apps[uid] = (apps[uid] ?: 0) + 1
        }
        fun label(uid: Int) = "uid $uid (${if (uid == ownUid) "this app" else nameOf(uid)})"
        val toText = if (to.isEmpty()) "none" else to.joinToString(", ") { label(it) }
        val appText = if (apps.isEmpty()) "none" else apps.entries.take(MAX_UIDS)
            .joinToString(", ") { "${label(it.key)} x${it.value}" }
        return "to $text $toText; app uids $appText"
    }

    fun displayLine(kind: String, id: Int, name: String, widthPx: Int, heightPx: Int, dpi: Int, presentation: Boolean): String =
        "display $kind $id:$name ${widthPx}x$heightPx@$dpi" + if (presentation) " presentation" else ""

    fun displayRemovedLine(id: Int): String = "display removed $id"
}
