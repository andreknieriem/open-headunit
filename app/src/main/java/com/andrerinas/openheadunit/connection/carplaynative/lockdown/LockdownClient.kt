package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.lockdown.LockdownSession.Action
import com.andrerinas.openheadunit.connection.carplaynative.usb.BlockingDuplexByteStream
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbException
import com.andrerinas.openheadunit.connection.carplaynative.usb.MuxConnector
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxCodec
import com.andrerinas.openheadunit.utils.AppLog
import java.security.GeneralSecurityException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine

/**
 * Runs [LockdownSession] over usbmux streams and returns the carkit service stream. Every connect, request and TLS handshake
 * gets [stepTimeoutMs], because the reducer has no per-step deadline. Blocking: call it off the main thread.
 * On any failure it closes every stream it opened. The returned stream owns the lockdown connection that started it.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `LockdownPairingClient.kt` and `LockdownCarKitClient.kt`.
 */
class LockdownClient(
    private val connector: MuxConnector,
    private val store: PairRecordStore,
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
    private val stepTimeoutMs: Long = LockdownSession.STEP_TIMEOUT_MS,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val engineFor: (PairRecord) -> SSLEngine = { LockdownTlsEngineFactory.create(it) },
) {
    @Throws(IphoneUsbException::class)
    fun open(serial: String, label: String, isCancelled: () -> Boolean): BlockingDuplexByteStream {
        val saved = store.load(serial)
        val systemBuid = saved?.systemBuid ?: newId()
        // The two RSA keys take seconds on a slow unit, so they are made before the iPhone waits on us.
        var keys = if (saved == null) PairRecordFactory.newKeyPair() to PairRecordFactory.newKeyPair() else null
        val session = LockdownSession(label, systemBuid, saved) { devicePublicKeyPem, wifiAddress ->
            val made = keys ?: (PairRecordFactory.newKeyPair() to PairRecordFactory.newKeyPair())
            keys = null
            PairRecordFactory.generate(devicePublicKeyPem, wifiAddress, newId(), systemBuid, wallClock(), made.first, made.second)
        }
        val run = Run(session, serial, isCancelled)
        try {
            return run.drive()
        } catch (e: Exception) {
            run.closeAll()
            val failure = e as? IphoneUsbException
                ?: if (e is GeneralSecurityException) IphoneUsbException.Protocol("CarPlayNative: lockdown TLS could not start (${e.javaClass.simpleName})", e) else throw e
            AppLog.w(if (failure.message?.startsWith(PREFIX) == true) failure.message!! else "$PREFIX lockdown failed (${failure.message})")
            throw failure
        }
    }

    private inner class Run(private val session: LockdownSession, private val serial: String, private val isCancelled: () -> Boolean) {
        private val queue = ArrayDeque<Action>()
        private var plain: BlockingDuplexByteStream? = null
        private var plainChannel: LockdownPlistChannel? = null
        private var secure: BlockingDuplexByteStream? = null
        private var secureChannel: LockdownPlistChannel? = null

        fun drive(): BlockingDuplexByteStream {
            val started = clock()
            queue.addAll(session.start())
            while (true) {
                if (isCancelled()) throw IphoneUsbException.Protocol("$PREFIX lockdown cancelled")
                if (clock() - started > LockdownSession.TRUST_WAIT_MS + TOTAL_MARGIN_STEPS * stepTimeoutMs) {
                    throw IphoneUsbException.TimedOut("$PREFIX lockdown did not finish in time")
                }
                val action: Action? = queue.pollFirst()
                if (action == null) {
                    if (session.finished) throw IphoneUsbException.Protocol("$PREFIX lockdown ended with no service stream")
                    val ticked = session.tick(clock())
                    if (ticked.isEmpty()) sleep(POLL_MS) else queue.addAll(ticked)
                    continue
                }
                when (action) {
                    Action.Connect -> {
                        closeConnections()
                        val stream = connector.connect(UsbmuxCodec.LOCKDOWN_PORT, stepTimeoutMs)
                        plain = stream
                        plainChannel = LockdownPlistChannel(stream, clock)
                        queue.addAll(session.onConnected())
                    }
                    is Action.Send -> {
                        val channel = (if (action.secure) secureChannel else plainChannel)
                            ?: throw IphoneUsbException.Protocol("$PREFIX lockdown has no open channel")
                        queue.addAll(session.onReply(channel.request(action.request, stepTimeoutMs), clock()))
                    }
                    is Action.StartTls -> {
                        val engine = engineFor(action.record)
                        val under = plain ?: throw IphoneUsbException.Protocol("$PREFIX lockdown has no stream for TLS")
                        plain = null
                        plainChannel = null
                        val tls = TlsDuplexChannel.open(under, engine, stepTimeoutMs, clock)
                        secure = tls
                        secureChannel = LockdownPlistChannel(tls, clock)
                        queue.addAll(session.onTlsReady())
                    }
                    is Action.OpenService -> return openService(action)
                    is Action.SaveRecord -> if (store.save(serial, action.record)) AppLog.i("$PREFIX pair record saved")
                    Action.ClearRecord -> store.clear(serial)
                    is Action.Log -> AppLog.i(action.line)
                    is Action.Fail -> throw IphoneUsbException.Protocol(action.reason)
                }
            }
        }

        private fun openService(action: Action.OpenService): BlockingDuplexByteStream {
            // The engine comes first: once the service stream is open, nothing may throw before an owner holds it.
            val engine = if (action.tls) engineFor(action.record) else null
            val connection = connector.connect(action.port, stepTimeoutMs)
            val stream = if (engine != null) TlsDuplexChannel.open(connection, engine, stepTimeoutMs, clock) else connection
            val owner = secure
            secure = null
            secureChannel = null
            closeConnections()
            AppLog.i("$PREFIX carkit service stream open")
            return ServiceStream(stream, owner)
        }

        private fun closeConnections() {
            plain?.let { runCatching { it.close() } }
            secure?.let { runCatching { it.close() } }
            plain = null; plainChannel = null; secure = null; secureChannel = null
        }

        fun closeAll() = closeConnections()
    }

    /** Keeps the lockdown session that started the service open for as long as the service stream lives. */
    private class ServiceStream(private val service: BlockingDuplexByteStream, private val lockdown: BlockingDuplexByteStream?) : BlockingDuplexByteStream {
        private val closed = AtomicBoolean(false)

        override fun send(data: ByteArray) = service.send(data)

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = service.recv(maxBytes, timeoutMillis)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { service.close() } finally { lockdown?.close() }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().uppercase(Locale.US)

    private companion object {
        const val PREFIX = "CarPlayNative:"
        const val POLL_MS = 100L
        const val TOTAL_MARGIN_STEPS = 8
    }
}
