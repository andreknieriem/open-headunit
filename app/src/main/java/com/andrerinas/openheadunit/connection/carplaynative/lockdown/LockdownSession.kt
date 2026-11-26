package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue

/**
 * Orders the lockdown requests: pair or reuse a record, start a session, enable TLS, start the carkit service.
 * The caller does the I/O and reports each event with a clock reading in milliseconds. Never log a record.
 * Ported from xcertplay (https://github.com/shilapi/xcertplay) `LockdownPairingClient.kt`, `LockdownCarKitClient.kt` and `CarPlayController.kt`.
 */
class LockdownSession(
    private val label: String,
    private val systemBuid: String,
    saved: PairRecord?,
    private val newRecord: (devicePublicKeyPem: ByteArray, wifiAddress: String) -> PairRecord,
) {
    sealed class Action {
        /** Open a new plaintext connection to the lockdown port, then call [onConnected]. */
        object Connect : Action()

        /** Send [request] and call [onReply] with the answer. [secure] means over the session TLS. */
        class Send(val request: PlistValue.Dict, val secure: Boolean) : Action()

        /** Run TLS on the open lockdown connection with [record], then call [onTlsReady]. */
        class StartTls(val record: PairRecord) : Action()

        /** Connect to the service [port], with TLS when [tls] is set. The session ends here. */
        class OpenService(val port: Int, val tls: Boolean, val record: PairRecord) : Action()

        class SaveRecord(val record: PairRecord) : Action()
        object ClearRecord : Action()
        class Log(val line: String) : Action()
        class Fail(val reason: String) : Action()
    }

    private enum class Phase {
        Idle, ConnectForPair, SetUntrusted, GetKey, GetWifi, Pairing, WaitRetry, ConnectForSession, StartSession, AwaitTls, StartService, Done, Failed,
    }

    private var phase = Phase.Idle
    private var record: PairRecord? = saved
    private var usedSaved = saved != null
    private var repaired = false
    private var devicePublicKey: ByteArray? = null
    private var trustSinceMs = -1L
    private var retryAtMs = 0L
    private var waitingLogged = false

    /** True while the session runs on a record taken from storage. False once it pairs. */
    val usingSavedRecord: Boolean get() = usedSaved

    val finished: Boolean get() = phase == Phase.Done || phase == Phase.Failed

    fun start(): List<Action> {
        check(phase == Phase.Idle) { "LockdownSession is single use" }
        return if (record != null) beginSession() else beginPairing()
    }

    fun onConnected(): List<Action> = when (phase) {
        Phase.ConnectForPair -> {
            phase = Phase.SetUntrusted
            listOf(send(request("SetValue", "Key" to PlistValue.Text("UntrustedHostBUID"), "Value" to PlistValue.Text(systemBuid))))
        }
        Phase.ConnectForSession -> {
            phase = Phase.StartSession
            val r = record!!
            listOf(send(request("StartSession", "HostID" to PlistValue.Text(r.hostId), "SystemBUID" to PlistValue.Text(r.systemBuid))))
        }
        else -> emptyList()
    }

    fun onReply(reply: PlistValue.Dict, nowMs: Long): List<Action> {
        val error = errorCode(reply)
        return when (phase) {
            Phase.SetUntrusted -> if (error != null) fail("SetValue", error) else {
                val known = record
                if (known != null) { phase = Phase.Pairing; listOf(send(pairRequest(known))) }
                else { phase = Phase.GetKey; listOf(send(request("GetValue", "Key" to PlistValue.Text("DevicePublicKey")))) }
            }
            Phase.GetKey -> if (error != null) fail("GetValue", error) else {
                val key = reply["Value"]?.asBytes ?: return failNow("lockdown GetValue DevicePublicKey was not data")
                devicePublicKey = key
                phase = Phase.GetWifi
                listOf(send(request("GetValue", "Key" to PlistValue.Text("WiFiAddress"))))
            }
            Phase.GetWifi -> if (error != null) fail("GetValue", error) else {
                val wifi = reply["Value"]?.asText ?: return failNow("lockdown GetValue WiFiAddress was not text")
                // A bad DevicePublicKey throws here; the line names no record bytes.
                val made = try { newRecord(devicePublicKey!!, wifi) } catch (_: Exception) {
                    return failNow("CarPlayNative: lockdown pair record could not be made")
                }
                record = made
                phase = Phase.Pairing
                listOf(send(pairRequest(made)))
            }
            Phase.Pairing -> onPairReply(error, nowMs)
            Phase.StartSession -> onSessionReply(reply, error)
            Phase.StartService -> onServiceReply(reply, error)
            else -> emptyList()
        }
    }

    fun onTlsReady(): List<Action> {
        if (phase != Phase.AwaitTls) return emptyList()
        phase = Phase.StartService
        return listOf(Action.Send(request("StartService", "Service" to PlistValue.Text(CARKIT_SERVICE), label = false), secure = true))
    }

    /** Drives the retry pause and the Trust bound, which holds in every phase of the pairing. Call it on every clock tick. */
    fun tick(nowMs: Long): List<Action> {
        val pairing = phase == Phase.ConnectForPair || phase == Phase.SetUntrusted || phase == Phase.Pairing || phase == Phase.WaitRetry
        if (trustSinceMs < 0 || !pairing) return emptyList()
        if (nowMs - trustSinceMs >= TRUST_WAIT_MS) return failNow("CarPlayNative: Trust was not given on the iPhone in 60 s")
        if (phase != Phase.WaitRetry || nowMs < retryAtMs) return emptyList()
        phase = Phase.ConnectForPair
        return listOf(Action.Connect)
    }

    private fun beginPairing(): List<Action> {
        phase = Phase.ConnectForPair
        return listOf(Action.Connect)
    }

    private fun beginSession(): List<Action> {
        phase = Phase.ConnectForSession
        return listOf(Action.Connect)
    }

    private fun onPairReply(error: String?, nowMs: Long): List<Action> = when (error) {
        null -> {
            usedSaved = false
            beginSession().let { listOf(Action.SaveRecord(record!!)) + it }
        }
        PENDING -> {
            if (trustSinceMs < 0) trustSinceMs = nowMs
            if (nowMs - trustSinceMs >= TRUST_WAIT_MS) failNow("CarPlayNative: Trust was not given on the iPhone in 60 s")
            else {
                phase = Phase.WaitRetry
                retryAtMs = nowMs + RETRY_MS
                if (waitingLogged) emptyList() else { waitingLogged = true; listOf(Action.Log("CarPlayNative: waiting for Trust on the iPhone")) }
            }
        }
        "UserDeniedPairing" -> failNow("CarPlayNative: Trust was denied on the iPhone")
        "PasswordProtected" -> failNow("CarPlayNative: the iPhone is locked, unlock it to pair")
        else -> fail("Pair", error)
    }

    private fun onSessionReply(reply: PlistValue.Dict, error: String?): List<Action> {
        if (error != null) {
            if (usedSaved && !repaired && (error == "InvalidHostID" || error == "InvalidPairRecord")) {
                repaired = true
                usedSaved = false
                record = null
                devicePublicKey = null
                return listOf(Action.ClearRecord, Action.Log("CarPlayNative: saved pair record rejected ($error); pairing again")) + beginPairing()
            }
            return fail("StartSession", error)
        }
        if (reply["EnableSessionSSL"]?.asBool != true) return failNow("CarPlayNative: lockdown StartSession did not enable TLS")
        phase = Phase.AwaitTls
        return listOf(Action.StartTls(record!!))
    }

    private fun onServiceReply(reply: PlistValue.Dict, error: String?): List<Action> {
        if (error != null) return fail("StartService", error)
        val port = reply["Port"]?.asLong
        if (port == null || port !in 1..0xffff) return failNow("CarPlayNative: lockdown StartService returned no valid port")
        phase = Phase.Done
        return listOf(Action.OpenService(port.toInt(), reply["EnableServiceSSL"]?.asBool ?: false, record!!))
    }

    private fun pairRequest(r: PairRecord): PlistValue.Dict = PlistValue.dict(
        "Label" to PlistValue.Text(label),
        "PairRecord" to r.toPairRequestDictionary(),
        "Request" to PlistValue.Text("Pair"),
        "ProtocolVersion" to PlistValue.Text("2"),
        "PairingOptions" to PlistValue.dict("ExtendedPairingErrors" to PlistValue.Bool(true)),
    )

    private fun request(name: String, vararg extra: Pair<String, PlistValue>, label: Boolean = true): PlistValue.Dict =
        PlistValue.Dict((if (label) listOf("Label" to PlistValue.Text(this.label)) else emptyList()) + ("Request" to PlistValue.Text(name)) + extra)

    private fun send(request: PlistValue.Dict) = Action.Send(request, secure = false)

    private fun fail(request: String, code: String) = failNow("CarPlayNative: lockdown $request failed ($code)")

    private fun failNow(reason: String): List<Action> {
        phase = Phase.Failed
        return listOf(Action.Fail(reason))
    }

    companion object {
        const val CARKIT_SERVICE = "com.apple.carkit.service"
        const val TRUST_WAIT_MS = 60_000L
        const val RETRY_MS = 1_000L
        const val STEP_TIMEOUT_MS = 5_000L
        private const val PENDING = "PairingDialogResponsePending"

        /** The lockdown error code of a reply, or null for a success. An integer error uses its `ErrorString`. */
        fun errorCode(reply: PlistValue.Dict): String? = when (val error = reply["Error"]) {
            null -> null
            is PlistValue.Text -> error.value
            is PlistValue.Integer -> reply["ErrorString"]?.asText ?: error.value.toString()
            else -> "BadErrorType"
        }
    }
}
