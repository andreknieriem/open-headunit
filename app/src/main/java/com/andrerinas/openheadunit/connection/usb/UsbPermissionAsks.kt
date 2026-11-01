package com.andrerinas.openheadunit.connection.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Process-wide record of the bus paths the app has asked USB permission for. It is not owned by
 * the service, so a service restart in this process cannot forget a dialog still on screen. Each
 * entry holds its request's token, and only a grant with that token or a detach ends it.
 */
object UsbPermissionAsks {

    private class Entry(val atMs: Long, val token: String)

    private val asked = HashMap<String, Entry>()
    private val attachedAtMs = HashMap<String, Long>()
    private val hinted = HashSet<String>()
    private var ownSwitchAtMs: Long? = null

    /** True when [path] was not in the record. Paths missing from [presentPaths] are dropped first. */
    @Synchronized
    fun tryMark(path: String, presentPaths: Collection<String>, token: String, nowMs: Long = nowMs()): Boolean {
        prune(presentPaths)
        if (asked.containsKey(path)) return false
        asked[path] = Entry(nowMs, token)
        return true
    }

    @Synchronized
    fun isAsked(path: String, presentPaths: Collection<String>): Boolean {
        prune(presentPaths)
        return asked.containsKey(path)
    }

    private fun prune(presentPaths: Collection<String>) {
        asked.keys.retainAll(presentPaths.toSet())
    }

    /** A user ask goes through an open entry, because a lost answer must not silence the button. */
    @Synchronized
    fun claim(
        path: String, presentPaths: Collection<String>, source: UsbPermissionAskPolicy.Source, token: String,
        nowMs: Long = nowMs(),
    ): Boolean {
        if (tryMark(path, presentPaths, token, nowMs)) return true
        val since = asked[path]?.atMs ?: return false
        if (source != UsbPermissionAskPolicy.Source.USER || !UsbPermissionAskPolicy.userPassesOpenEntry(nowMs - since)) {
            return false
        }
        asked[path] = Entry(nowMs, token)
        return true
    }

    /**
     * Ends the entry for [path] only when [token] is its request's. An answer to an older request
     * at a reused path, or to a dialog a later user ask replaced, leaves the newer entry alone.
     */
    @Synchronized
    fun complete(path: String, token: String): Boolean {
        if (asked[path]?.token != token) return false
        asked.remove(path)
        return true
    }

    /**
     * Android's answer to the request [token]. Below Android 12 its dialog also answers "denied"
     * when it is only rebuilt (a rotation, night mode), so only a grant ends the entry. After a
     * real denial the entry holds automatic asks until the device leaves; the USB button still asks.
     */
    @Synchronized
    fun answered(path: String, token: String, granted: Boolean): Boolean = granted && complete(path, token)

    @Synchronized
    fun noteAttach(path: String, nowMs: Long) {
        attachedAtMs[path] = nowMs
    }

    @Synchronized
    fun attachAgeMs(path: String, nowMs: Long): Long? = attachedAtMs[path]?.let { nowMs - it }

    /** A detached path ends any dialog for it, so the next plug-in may ask again. */
    @Synchronized
    fun noteDetach(path: String) {
        asked.remove(path)
        attachedAtMs.remove(path)
        hinted.remove(path)
    }

    /** True once per plug-in, so the USB access note does not repeat on every home resume. */
    @Synchronized
    fun tryHint(path: String): Boolean = hinted.add(path)

    /**
     * The record lives as long as the process, so its detach observer must too: a detach missed
     * while the service was stopped left a stale entry at a reused path. It touches no settings,
     * so it is safe before unlock.
     */
    fun observeDetaches(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val device = intent?.let { UsbReceiver.deviceOf(it) } ?: return
                noteDetach(device.deviceName)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @Synchronized
    fun noteOwnSwitch(nowMs: Long) {
        ownSwitchAtMs = nowMs
    }

    @Synchronized
    fun ownSwitchAgeMs(nowMs: Long): Long? = ownSwitchAtMs?.let { nowMs - it }

    @Synchronized
    internal fun resetForTest() {
        asked.clear()
        attachedAtMs.clear()
        hinted.clear()
        ownSwitchAtMs = null
    }

    /** The USB button and the USB list: the user asked, so only a dialog already up stops it. */
    fun askFromUi(context: Context, device: UsbDevice) {
        val usbManager = UsbDeviceCompat.usbManager(context) ?: return
        val settings = App.provide(context).settings
        val present = usbManager.deviceList.values.map { it.deviceName }
        val inputs = UsbDeviceCompat.permissionDecisionInputs(
            context, settings, device, UsbPermissionAskPolicy.Source.USER, present,
        )
        val decision = UsbPermissionAskPolicy.decide(inputs)
        AppLog.i("UsbLauncher: permission for ${UsbDeviceCompat(device).uniqueName} at ${device.deviceName}: $decision")
        if (decision.action != UsbPermissionAskPolicy.Action.ASK) return
        val token = newToken()
        if (!claim(device.deviceName, present, UsbPermissionAskPolicy.Source.USER, token)) {
            AppLog.i("UsbPermissionAsks: a dialog for ${device.deviceName} opened under ${UsbPermissionAskPolicy.USER_REPEAT_MS}ms ago; not opening a second")
            return
        }

        AppLog.i("UsbPermissionAsks: Requesting USB permission for ${UsbDeviceCompat(device).uniqueName}")
        try {
            usbManager.requestPermission(device, UsbReceiver.createPermissionPendingIntent(context, token))
        } catch (e: Exception) {
            complete(device.deviceName, token)
            AppLog.w("UsbPermissionAsks: the permission request failed: ${e.message}")
        }
    }

    internal fun stampAttach(path: String) = noteAttach(path, nowMs())

    fun newToken(): String = java.util.UUID.randomUUID().toString()

    /** The clock [noteOwnSwitch] and [ownSwitchAgeMs] share; monotonic and free of Android. */
    internal fun nowMs(): Long = System.nanoTime() / 1_000_000L

    internal fun stampOwnSwitch() = noteOwnSwitch(nowMs())
}
