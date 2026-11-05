package com.andrerinas.openheadunit.connection.usb.hci

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.connection.usb.UsbBluetoothControllerPolicy
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one [HciDiagnostic] on a plain thread that owns the USB claim. The thread outlives the
 * activity that started it, and a `finally` always releases the claim and prints the end line.
 */
object UsbHciDiagnosticRunner {

    private val running = AtomicBoolean(false)
    private const val PERMISSION_WAIT_S = 60L

    /** [onEnd] runs on the worker thread after the end line. */
    fun start(context: Context, request: HciDiagnostic.Request, onEnd: () -> Unit) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            log("HCI: failed step=busy reason=a run is already in flight")
            log("HCI: end result=failed claim=none")
            onEnd()
            return
        }
        Thread({
            var ok = false
            var claim = "none"
            var step = "find"
            try {
                ok = run(app, request, { claim = it }, { step = it })
            } catch (t: Throwable) {
                log("HCI: failed step=$step reason=${t.javaClass.simpleName}")
            } finally {
                log("HCI: end result=${if (ok) "ok" else "failed"} claim=$claim")
                running.set(false)
                onEnd()
            }
        }, "UsbHciDiagnostic").start()
    }

    private fun run(
        app: Context, request: HciDiagnostic.Request, setClaim: (String) -> Unit, setStep: (String) -> Unit,
    ): Boolean {
        val usb = UsbDeviceCompat.usbManager(app) ?: return fail("find", "no USB service")
        val device = usb.deviceList.values.firstOrNull {
            UsbBluetoothControllerPolicy.classify(UsbDeviceCompat.describe(it)) != null
        } ?: return fail("find", "no Bluetooth controller on the bus")
        log(String.format(Locale.US, "HCI: device %04x:%04x", device.vendorId, device.productId))

        setStep("permission")
        if (!usb.hasPermission(device) && !askPermission(app, usb, device)) {
            return fail("permission", "not granted")
        }
        setStep("open")
        val conn = usb.openDevice(device) ?: return fail("open", "openDevice returned null")
        val intf = controllerInterface(device)
        setStep("claim")
        val how = when {
            conn.claimInterface(intf, false) -> "plain"
            conn.claimInterface(intf, true) -> "forced"
            else -> {
                conn.close()
                return fail("claim", "claimInterface refused")
            }
        }
        setClaim("released")
        try {
            log("HCI: claim $how")
            setStep("endpoint")
            val evt = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN
            } ?: return fail("endpoint", "no interrupt IN endpoint")
            setStep("reset")
            val link = UsbHciLink(conn, intf, evt)
            return HciDiagnostic(link, { loadFirmware(app) }, ::log, SystemClock::elapsedRealtime, SystemClock::sleep)
                .run(request.address, request.drop, request.noload)
        } finally {
            try { conn.releaseInterface(intf) } catch (e: Exception) { AppLog.d("UsbHciDiagnosticRunner: release failed: ${e.message}") }
            conn.close()
        }
    }

    /** The first E0/01/01 interface that has an interrupt IN endpoint, else the first E0/01/01 one. */
    private fun controllerInterface(d: UsbDevice): UsbInterface {
        val hci = (0 until d.interfaceCount).map { d.getInterface(it) }.filter {
            it.interfaceClass == 0xE0 && it.interfaceSubclass == 0x01 && it.interfaceProtocol == 0x01
        }
        return hci.firstOrNull { i ->
            (0 until i.endpointCount).any {
                i.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    i.getEndpoint(it).direction == UsbConstants.USB_DIR_IN
            }
        } ?: hci.first()
    }

    private fun askPermission(app: Context, usb: UsbManager, device: UsbDevice): Boolean {
        val action = "${app.packageName}.HCI_DIAG_PERMISSION"
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(device, PendingIntent.getBroadcast(app, 931, Intent(action).setPackage(app.packageName), flags))
            latch.await(PERMISSION_WAIT_S, TimeUnit.SECONDS)
        } finally {
            try { app.unregisterReceiver(receiver) } catch (e: Exception) { AppLog.d("UsbHciDiagnosticRunner: unregister failed: ${e.message}") }
        }
        return granted
    }

    /** The activity takes no path, so a caller cannot point it at another file. */
    private fun loadFirmware(app: Context): HciDiagnostic.FirmwareFiles? =
        HciFirmwareRoots.load(listOf("external" to app.getExternalFilesDir(null), "internal" to app.filesDir), ::log)

    private fun fail(step: String, reason: String): Boolean {
        log("HCI: failed step=$step reason=$reason")
        return false
    }

    private fun log(line: String) = AppLog.i(line)
}
