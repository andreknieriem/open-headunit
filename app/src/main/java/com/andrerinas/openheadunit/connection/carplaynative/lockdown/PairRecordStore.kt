package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistException
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.codec.XmlPlist
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File
import java.io.IOException

/**
 * One pair record file per iPhone serial, in a private directory. A record holds private keys: nothing here logs a field.
 * [LockdownClient] calls [load], [save] and [clear].
 * [directory] must be outside Android backup, for example under `noBackupFilesDir`.
 */
class PairRecordStore(private val directory: File) {

    fun load(serial: String): PairRecord? {
        val file = fileFor(serial)
        if (!file.isFile) return null
        return try {
            val dict = XmlPlist.parse(file.readBytes())
            fun text(key: String) = dict[key]?.asText?.takeIf { it.isNotBlank() }
            fun data(key: String) = dict[key]?.asBytes?.takeIf { it.isNotEmpty() }
            PairRecord(
                text("HostID") ?: return corrupt(), text("SystemBUID") ?: return corrupt(), text("WiFiAddress") ?: return corrupt(),
                data("DevicePublicKey") ?: return corrupt(), data("DeviceCertificate") ?: return corrupt(),
                data("HostPrivateKey") ?: return corrupt(), data("HostCertificate") ?: return corrupt(),
                data("RootPrivateKey") ?: return corrupt(), data("RootCertificate") ?: return corrupt(),
            )
        } catch (_: PlistException) {
            corrupt()
        } catch (_: IOException) {
            corrupt()
        }
    }

    /** Writes through a temp file and a rename. False means the record is not stored and the next connect pairs again. */
    fun save(serial: String, record: PairRecord): Boolean {
        val target = fileFor(serial)
        val temp = File(directory, target.name + ".tmp")
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create the record directory")
            temp.delete()
            temp.createNewFile()
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(false, false)
            temp.setWritable(true, true)
            temp.writeBytes(encode(record))
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("cannot replace the record file")
            }
            true
        } catch (e: IOException) {
            temp.delete()
            AppLog.w("CarPlayNative: pair record not saved (${e.javaClass.simpleName})")
            false
        }
    }

    fun clear(serial: String) {
        val file = fileFor(serial)
        File(directory, file.name + ".tmp").delete()
        file.delete()
    }

    private fun corrupt(): PairRecord? {
        AppLog.w("CarPlayNative: saved pair record is unreadable; it is ignored")
        return null
    }

    private fun fileFor(serial: String) = File(directory, safeName(serial) + ".plist")

    companion object {
        /** A serial is a file name only after every character outside letters, digits, dot, dash and underscore becomes an underscore. */
        fun safeName(serial: String): String {
            val cleaned = serial.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")
            return if (cleaned.isBlank() || cleaned.all { it == '.' }) "unknown" else cleaned.take(MAX_NAME)
        }

        private const val MAX_NAME = 64

        private fun encode(r: PairRecord): ByteArray = XmlPlist.encode(
            PlistValue.dict(
                "HostID" to PlistValue.Text(r.hostId),
                "SystemBUID" to PlistValue.Text(r.systemBuid),
                "WiFiAddress" to PlistValue.Text(r.wifiAddress),
                "DevicePublicKey" to PlistValue.Data(r.devicePublicKeyPem),
                "DeviceCertificate" to PlistValue.Data(r.deviceCertificatePem),
                "HostPrivateKey" to PlistValue.Data(r.hostPrivateKeyPem),
                "HostCertificate" to PlistValue.Data(r.hostCertificatePem),
                "RootPrivateKey" to PlistValue.Data(r.rootPrivateKeyPem),
                "RootCertificate" to PlistValue.Data(r.rootCertificatePem),
            ),
        )
    }
}
