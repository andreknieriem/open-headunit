package com.andrerinas.openheadunit.connection.carkey.qf

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.QfWheelKey
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Vendor wheel and panel keys (Navi, Mode, Phone, Voice, KEY1 to KEY15) on QF / K706 units,
 * from the `util_service` key listener. Registered only where that service exists, and only
 * during a session or while the keymap screen learns. The unit still acts on each key itself.
 */
class CarQfReceiver : CarKeyReceiver {

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var service: IBinder? = null
    @Volatile private var active = false
    private val loggedFailures = mutableSetOf<String>()
    private val eventless = QfWheelKey.EventlessGate()
    private var retriesLeft = 0
    private val retry = Runnable { retryListen() }

    private val listener = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TXN_ON_KEY) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(LISTENER_INTERFACE)
                val qfCode = data.readInt()
                val hasEvent = data.readInt() != 0
                val event = if (hasEvent) readEvent(data) else null
                // Android keys also come here and as key events: only the vendor range is ours.
                // A call whose event did not parse is dropped, so a DOWN and an UP never give two clicks.
                if (hasEvent && event == null) {
                    logOnce("event", "CarQfReceiver: Unreadable key event, dropping such calls")
                } else if (active && QfWheelKey.isVendorKey(qfCode)) {
                    main.post { onKey(qfCode, event) }
                }
            } catch (e: Exception) {
                logOnce("read", "CarQfReceiver: Unreadable key call: ${e.message}")
            }
            // The service waits for this reply, so it goes back before any work is done.
            if (flags and FLAG_ONEWAY == 0) reply?.writeNoException()
            return true
        }
    }.apply { attachInterface(null, LISTENER_INTERFACE) }

    private val death = IBinder.DeathRecipient {
        main.post {
            service = null
            AppLog.w("CarQfReceiver: util_service died, listening again in ${RETRY_MS} ms")
            retriesLeft = MAX_RETRIES
            main.removeCallbacks(retry)
            main.postDelayed(retry, RETRY_MS)
        }
    }

    override val isSupported: Boolean get() = serviceBinder() != null
    override val isSUNeeded = false
    override val isSessionScoped = true

    override fun register(context: Context) {
        this.context = context.applicationContext
        active = true
        listen()
    }

    // The service has no known removal call, so the listener stays and its keys are dropped.
    override fun unregister() {
        active = false
        main.removeCallbacks(retry)
        AppLog.i("CarQfReceiver: Stopped taking QF keys")
    }

    // One try per RETRY_MS after a death, at most MAX_RETRIES, and none once unregister() runs.
    private fun retryListen() {
        if (!active || service != null) return
        if (listen()) return
        if (--retriesLeft > 0) {
            main.postDelayed(retry, RETRY_MS)
        } else {
            AppLog.w("CarQfReceiver: $SERVICE did not come back after $MAX_RETRIES tries, QF keys are off")
        }
    }

    private fun listen(): Boolean {
        val binder = serviceBinder() ?: return false
        if (binder === service) {
            AppLog.i("CarQfReceiver: Taking QF keys again")
            return true
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MANAGER_INTERFACE)
            data.writeStrongBinder(listener)
            binder.transact(TXN_REGISTER, data, reply, 0)
            reply.readException()
            binder.linkToDeath(death, 0)
            service = binder
            AppLog.i("CarQfReceiver: Listening for QF keys on $SERVICE")
            return true
        } catch (e: Exception) {
            logOnce("register", "CarQfReceiver: $SERVICE refused the key listener: ${e.message}")
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun onKey(qfCode: Int, event: KeyEvent?) {
        val ctx = context ?: return
        if (!active) return
        val press = if (event == null) {
            eventless.next(qfCode, SystemClock.elapsedRealtime())
        } else {
            QfWheelKey.pressOf(qfCode, event.action, event.repeatCount, 0, 0L, 0L)
        }
        if (press == QfWheelKey.Press.NONE) return

        // No default: a QF key goes on as its virtual code, which reaches the phone only once bound.
        val virtual = QfWheelKey.toKeyCode(qfCode)
        val name = QfWheelKey.nameOf(qfCode) ?: "unnamed"
        if (press != QfWheelKey.Press.UP) {
            AppLog.i("CarQfReceiver: Key $name $qfCode (${press.name.lowercase()}) -> keycode $virtual")
            showRawKey(ctx, "QF.$SERVICE", virtual, "qf_code" to "$qfCode ($name)")
        }
        // Each press carries its own downTime, so two quick real presses are not taken as one.
        when (press) {
            QfWheelKey.Press.CLICK -> handleClick(ctx, virtual, virtual, ROUTE, SystemClock.uptimeMillis())
            QfWheelKey.Press.DOWN -> handleKey(ctx, virtual, true, virtual, ROUTE, event?.downTime)
            QfWheelKey.Press.UP -> handleKey(ctx, virtual, false, virtual, ROUTE, event?.downTime)
            QfWheelKey.Press.NONE -> Unit
        }
    }

    /** The key event after the presence mark, or null when it does not parse. */
    private fun readEvent(data: Parcel): KeyEvent? = try {
        data.readInt()
        @Suppress("DEPRECATION")
        data.readParcelable<KeyEvent>(KeyEvent::class.java.classLoader)
    } catch (e: Exception) {
        logOnce("event", "CarQfReceiver: Unreadable key event, dropping such calls: ${e.message}")
        null
    }

    // Another vendor's util_service must never get our transaction, so the descriptor must match.
    private fun serviceBinder(): IBinder? {
        val binder = try {
            Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
                .invoke(null, SERVICE) as? IBinder
        } catch (e: Throwable) {
            logOnce("lookup", "CarQfReceiver: ServiceManager lookup failed: $e")
            null
        } ?: return null
        val descriptor = try {
            binder.interfaceDescriptor
        } catch (e: Exception) {
            logOnce("descriptor", "CarQfReceiver: $SERVICE gave no interface descriptor: ${e.message}")
            return null
        }
        if (descriptor != MANAGER_INTERFACE) {
            logOnce("descriptor", "CarQfReceiver: $SERVICE is $descriptor, not the QF key service")
            return null
        }
        return binder
    }

    private fun logOnce(key: String, message: String) {
        val first = synchronized(loggedFailures) { loggedFailures.add(key) }
        if (first) AppLog.w(message)
    }

    private companion object {
        const val SERVICE = "util_service"
        const val MANAGER_INTERFACE = "android.qf.util.IUtilEventManager"
        const val LISTENER_INTERFACE = "android.qf.util.IUtilEventListener"
        const val TXN_REGISTER = 2
        const val TXN_ON_KEY = 1
        const val RETRY_MS = 2_000L
        const val MAX_RETRIES = 5
        const val ROUTE = "carkey-qf"
    }
}
