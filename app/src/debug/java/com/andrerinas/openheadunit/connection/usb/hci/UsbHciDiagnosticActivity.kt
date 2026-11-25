package com.andrerinas.openheadunit.connection.usb.hci

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.usb.UsbBluetoothControllerNotice
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Debug-only rig entry for the USB Bluetooth controller sequence. Shell starts it with `am start`
 * and the extras `page`, `drop`, `noload` and `notice`. The manifest guards it with DUMP.
 */
class UsbHciDiagnosticActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreate must not start a second run.
        if (savedInstanceState != null) return
        val request = HciDiagnostic.begin(
            intent.getStringExtra("page"), intent.getBooleanExtra("drop", false),
            intent.getBooleanExtra("noload", false), intent.getStringExtra("notice"),
            Build.VERSION.SDK_INT, ::log,
        )
        if (request == null) {
            finish()
            return
        }
        val notice = request.notice
        if (notice != null) {
            val app = applicationContext
            // MainActivity stays started beneath this translucent screen, so the wait lets its flow redraw.
            Thread({
                UsbBluetoothControllerNotice.settle(app, notice == "raise")
                log("HCI: notice $notice")
                SystemClock.sleep(3000)
                log("HCI: end result=ok claim=none")
                runOnUiThread { finish() }
            }, "UsbHciDiagnosticNotice").start()
            return
        }
        UsbHciDiagnosticRunner.start(applicationContext, request) { runOnUiThread { finish() } }
    }

    private fun log(line: String) = AppLog.i(line)
}
