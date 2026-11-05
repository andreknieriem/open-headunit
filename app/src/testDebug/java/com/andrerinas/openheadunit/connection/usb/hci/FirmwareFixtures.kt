package com.andrerinas.openheadunit.connection.usb.hci

/** Builds a synthetic Realtek epatch file. The real blobs stay out of the tree. */
internal object FirmwareFixtures {
    private val sig = "Realtech".toByteArray(Charsets.US_ASCII)
    private val ext = byteArrayOf(0x51, 0x04, 0xFD.toByte(), 0x77)

    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    fun le32(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }

    /** [entries] are (chipId, patchBytes). The trailer carries [projectId]. */
    fun build(
        entries: List<Pair<Int, ByteArray>>, version: Long = 0xDFC6D922L, projectId: Int = 14,
        signature: ByteArray = sig, end: ByteArray = ext, offsetShift: Int = 0,
    ): ByteArray {
        val n = entries.size
        var cursor = 14 + 8 * n
        val offsets = entries.map { (_, p) -> cursor.also { cursor += p.size } }
        var out = signature + le32(version) + le16(n)
        entries.forEach { out += le16(it.first) }
        entries.forEach { out += le16(it.second.size) }
        offsets.forEach { out += le32((it + offsetShift).toLong()) }
        entries.forEach { out += it.second }
        return out + byteArrayOf(projectId.toByte(), 0x01, 0x00) + end
    }

    fun patch(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }
}
