package com.andrerinas.openheadunit.connection.usb.hci

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugManifestTest {
    private fun manifest(): String =
        listOf("src/debug/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml")
            .map(::File).first { it.exists() }.readText()

    @Test
    fun theDiagnosticActivityStaysBehindTheDumpPermission() {
        val m = manifest()
        val at = m.indexOf("UsbHciDiagnosticActivity")
        assertTrue(at >= 0)
        val tag = m.substring(m.lastIndexOf("<activity", at), m.indexOf("/>", at))
        assertTrue(tag.contains("android:permission=\"android.permission.DUMP\""))
    }
}
