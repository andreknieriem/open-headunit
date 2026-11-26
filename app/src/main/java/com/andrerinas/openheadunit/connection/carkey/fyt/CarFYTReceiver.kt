package com.andrerinas.openheadunit.connection.carkey.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.SUExecutor
import com.andrerinas.openheadunit.utils.SystemProperties

// based of decompilation of "com.syu.steer_HD.apk"
// this mainly adds support for uis7870 (dudu 7, tested)
// although this should also affect all other uis models (uis7862 ...)
class CarFYTReceiver : CarKeyReceiver {

    private var connection: IPCConnection? = null

    override val isSupported: Boolean get() {
        return SystemProperties.exists("ro.fyt.platform") ||
            SystemProperties.exists("syu.fyt.platform")
    }

    override val isSUNeeded = true
    override val isSessionScoped = true

    override fun register(context: Context) {
        AppLog.i("CarKeyReceiver: Detected FYT device!")

        val suExecutor = App.provide(context).suExecutor
        this.connection = IPCConnection(context, suExecutor, this)
        connection!!.connect()

        // ask for root early
        Handler(Looper.getMainLooper()).post {
            suExecutor.checkPermissionOnBoot()
        }
    }

    override fun unregister() {
        connection?.disconnect()
        connection = null
    }


    // reference: com.syu.ipcself.Conn, com.syu.steer.ipc.Ipc_NewNotifyPage
    private class IPCConnection(
        val context: Context,
        val suExecutor: SUExecutor,
        val receiver: CarFYTReceiver) : ServiceConnection {

        private val PACKAGE_NAME = "com.syu.ms"
        private val CLASS_NAME = "app.ToolkitService"

        private var handler: Handler? = null
        private var toolkit: RemoteToolkit? = null
        private val modules = HashMap<Int, RemoteModule>()

        fun connect() {
            if (this.handler != null)
                return

            // create separate thread
            val thread = HandlerThread("ConnectionThread")
            thread.start()

            handler = Handler(thread.looper)

            // initiate on new thread
            handler!!.post(this::attemptConnect)
        }

        private fun attemptConnect() {
            if (this.handler == null || this.toolkit != null)
                return;

            val intent = Intent()
            intent.setClassName(PACKAGE_NAME, CLASS_NAME)

            if (!context.bindService(intent, this, Context.BIND_AUTO_CREATE))
                handler!!.postDelayed(this::attemptConnect, 2000)
        }

        fun disconnect() {
            if (this.handler == null)
                return

            /*if (this.module != null) {
                // stop receiving updates
                module!!.cmd(2, ints = intArrayOf(0))
                //module!!.cmd(4)
            }*/

            this.handler!!.removeCallbacksAndMessages(null)
            this.handler!!.looper.quit()
            try {
                context.unbindService(this)
            } catch (e: IllegalArgumentException) {
                // Ignore if not registered/bound
            }
            this.handler = null
            this.toolkit = null
            this.modules.clear()
            suExecutor.setProp("sys.carlink.type", "0") // removes key focus from app
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            AppLog.i("CarKeyReceiver: Connected with FYT Service")

            this.toolkit = RemoteToolkit.Stub.asInterface(binder)

            if (!observe(0, intArrayOf(133))) {
                AppLog.w("CarKeyReceiver: Failed to bind into module 0. Not observing keys")
                return
            }

            // obtain key focus
            suExecutor.setProp("sys.carlink.type", "2")
        }

        fun observe(moduleCode: Int, codes: IntArray): Boolean {
            val module = this.toolkit!!.getRemoteModule(moduleCode) ?: return false
            modules[moduleCode] = module
            val callback = AAPCallback(moduleCode)

            // Flag 1 also pushes the current value at once, which replays the last key as a new press.
            for (code in codes) {
                module.register(callback, code, 0)
            }

            return true
        }

        override fun onServiceDisconnected(p0: ComponentName) {
            AppLog.i("CarKeyReceiver: Disconnected from FYT Service")
            this.toolkit = null
        }

        private inner class AAPCallback(val moduleCode: Int) :
            ModuleCallback.Stub() {

            override fun update(
                updateCode: Int,
                ints: IntArray?,
                floats: FloatArray?,
                strings: Array<String>?,
            ) {

                if (moduleCode == 0) {
                    when (updateCode) {
                        133 -> {
                            if (ints == null || ints.isEmpty())
                                return

                            val key = ints[0]
                            AppLog.i("CarKeyReceiver: Clicked key $key")

                            // assistant
                            if (key == 576) {
                                receiver.toggleVoiceAssistant(context)
                                return
                            }

                            val settings = App.provide(context).settings
                            receiver.handleClick(
                                context, key,
                                FytKeyTranslationPolicy.sendCode(key, settings.keyBindings),
                            )
                        }
                    }
                }
            }
        }
    }
}
