package com.andrerinas.openheadunit.connection.usb.hci

import java.io.File
import java.io.IOException

/**
 * Finds the pushed files in the first root that can read them. A file the app may not read
 * passes `isFile` and fails on the read, so that root is skipped, and the next one still runs.
 */
object HciFirmwareRoots {

    fun load(
        roots: List<Pair<String, File?>>,
        log: (String) -> Unit,
        read: (File) -> ByteArray = File::readBytes,
    ): HciDiagnostic.FirmwareFiles? {
        for ((name, root) in roots) {
            val fw = File(root ?: continue, "rtl_bt/rtl8761bu_fw.bin")
            try {
                if (!fw.isFile) continue
                val config = File(root, "rtl_bt/rtl8761bu_config.bin").takeIf { it.isFile }
                return HciDiagnostic.FirmwareFiles(name, read(fw), config?.let(read))
            } catch (e: IOException) {
                log("HCI: firmware skipped dir=$name reason=${e.javaClass.simpleName}")
            } catch (e: SecurityException) {
                log("HCI: firmware skipped dir=$name reason=${e.javaClass.simpleName}")
            }
        }
        return null
    }
}
