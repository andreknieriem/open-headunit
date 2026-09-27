package com.andrerinas.openheadunit.automation

import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.contract.HeadUnitCommand

/**
 * Decides what an incoming automation command should do. The receiver and the deep-link activity
 * both run the effects this returns, so the two doors cannot answer the same command differently.
 */
object AutomationCommandPolicy {

    /** The subset of an `Intent` a command can read. Abstract so a test can pass a plain map. */
    interface Extras {
        fun string(key: String): String?
        fun flag(key: String): Boolean
    }

    /** The parts of the app's state that change what a command does. */
    data class State(
        /** "Allow external configuration" — off by default, and gates every configuring verb. */
        val externalConfigAllowed: Boolean
    )

    sealed interface Effect {
        /** Send [action] to [AapService]. */
        data class StartService(
            val action: String,
            val stringExtras: Map<String, String> = emptyMap(),
            val flagExtras: Map<String, Boolean> = emptyMap()
        ) : Effect

        /** Open a TCP session to a head unit server. Paired with a [StartService] of the same name. */
        data class ConnectSocket(val ip: String, val port: Int) : Effect

        data class SetNightMode(val mode: String) : Effect

        data class ImportSettings(val json: String?, val path: String?) : Effect
        data class ExportSettings(val path: String?) : Effect
        object ResetSettings : Effect

        data class SetLogLevel(val level: String) : Effect
        object StartLogCapture : Effect
        object StopLogCapture : Effect
        data class ExportLog(val path: String?) : Effect
        data class LogMarker(val text: String) : Effect

        object QueryState : Effect

        /** Nothing was done, and [reason] says why. Reported back on the ordered-broadcast result. */
        data class Refuse(val reason: String) : Effect
    }

    /** Port of Android Auto's built-in head unit server, which is what an `ip` command targets. */
    const val HEADUNIT_SERVER_PORT = 5277

    private val NIGHT_MODES = setOf("day", "night", "auto")

    private val LOG_LEVELS = setOf("verbose", "debug", "info", "warn", "error", "silent")

    /**
     * Verbs that rewrite the unit's configuration or drive the log capture. Writing a log marker is
     * not one: it reads and changes nothing, and gating it made it useless for the job it exists
     * for, which is separating two runs inside one capture before the switch is ever turned on.
     */
    private val CONFIGURING = setOf(
        HeadUnitCommand.ACTION_SET_SETTINGS,
        HeadUnitCommand.ACTION_GET_SETTINGS,
        HeadUnitCommand.ACTION_RESET_SETTINGS,
        HeadUnitCommand.ACTION_SET_LOG_LEVEL,
        HeadUnitCommand.ACTION_START_LOG_CAPTURE,
        HeadUnitCommand.ACTION_STOP_LOG_CAPTURE,
        HeadUnitCommand.ACTION_EXPORT_LOG
    )

    /** Commands that need no extras and map straight onto a service action. */
    private val PLAIN_RELAYS = mapOf(
        HeadUnitCommand.ACTION_DISCONNECT to AapService.ACTION_DISCONNECT,
        HeadUnitCommand.ACTION_STOP_SERVICE to AapService.ACTION_STOP_SERVICE,
        HeadUnitCommand.ACTION_EXIT to AapService.ACTION_STOP_SERVICE,
        HeadUnitCommand.ACTION_STOP_WIRELESS to AapService.ACTION_STOP_WIRELESS,
        HeadUnitCommand.ACTION_CANCEL_WIRELESS to AapService.ACTION_CANCEL_WIRELESS,
        HeadUnitCommand.ACTION_START_WIRELESS_SCAN to AapService.ACTION_START_WIRELESS_SCAN,
        HeadUnitCommand.ACTION_END_SESSION_STAY_ARMED to AapService.ACTION_END_SESSION_STAY_ARMED,
        HeadUnitCommand.ACTION_REFRESH_SENSORS to AapService.ACTION_REFRESH_SENSORS,
        HeadUnitCommand.ACTION_RESTART_AUDIO to AapService.ACTION_RESTART_AUDIO,
        HeadUnitCommand.ACTION_RAISE_PROJECTION to AapService.ACTION_RAISE_PROJECTION,
        // Relayed by its own name rather than an AapService constant: the handler ships with the
        // driver-selection feature, and the two strings are the same either way.
        HeadUnitCommand.ACTION_NATIVE_AA_CANCEL_POKE to HeadUnitCommand.ACTION_NATIVE_AA_CANCEL_POKE,
        // These three were the internal service actions before this surface existed, so both
        // spellings are answered: the documented one, and the one already in people's scripts.
        HeadUnitCommand.LEGACY_ACTION_REFRESH_SENSORS to AapService.ACTION_REFRESH_SENSORS,
        HeadUnitCommand.LEGACY_ACTION_RESTART_AUDIO to AapService.ACTION_RESTART_AUDIO,
        HeadUnitCommand.LEGACY_ACTION_RAISE_PROJECTION to AapService.ACTION_RAISE_PROJECTION
    )

    fun effectsFor(action: String?, extras: Extras, state: State): List<Effect> {
        if (action == null) return listOf(Effect.Refuse("no action"))

        if (action in CONFIGURING && !state.externalConfigAllowed) {
            return listOf(
                Effect.Refuse("external configuration is off; turn on \"Allow external configuration\" in Settings")
            )
        }

        PLAIN_RELAYS[action]?.let { return listOf(Effect.StartService(it)) }

        return when (action) {
            HeadUnitCommand.ACTION_CONNECT -> connect(extras)
            HeadUnitCommand.ACTION_CHECK_USB -> listOf(checkUsb())
            // Not a plain relay: the service honours no_ui on this action, and relaying it without
            // the extra silently dropped the one thing the caller asked for.
            HeadUnitCommand.ACTION_START_WIRELESS -> listOf(
                Effect.StartService(
                    AapService.ACTION_START_WIRELESS,
                    flagExtras = mapOf(AapService.EXTRA_NO_UI to extras.flag(HeadUnitCommand.EXTRA_NO_UI))
                )
            )
            HeadUnitCommand.ACTION_START_SELF_MODE -> listOf(
                Effect.StartService(
                    AapService.ACTION_START_SELF_MODE,
                    flagExtras = mapOf(AapService.EXTRA_NO_UI to extras.flag(HeadUnitCommand.EXTRA_NO_UI))
                )
            )
            HeadUnitCommand.ACTION_SET_NIGHT_MODE -> nightMode(extras)
            HeadUnitCommand.ACTION_NATIVE_AA_POKE -> requiring(extras, HeadUnitCommand.EXTRA_MAC) { mac ->
                Effect.StartService(
                    AapService.ACTION_NATIVE_AA_POKE,
                    stringExtras = mapOf(AapService.EXTRA_MAC to mac)
                )
            }
            HeadUnitCommand.ACTION_NEARBY_CONNECT -> requiring(extras, HeadUnitCommand.EXTRA_ENDPOINT_ID) { id ->
                Effect.StartService(
                    AapService.ACTION_NEARBY_CONNECT,
                    stringExtras = mapOf(AapService.EXTRA_ENDPOINT_ID to id)
                )
            }
            HeadUnitCommand.ACTION_QUERY_STATE -> listOf(Effect.QueryState)

            HeadUnitCommand.ACTION_SET_SETTINGS -> settingsImport(extras)
            HeadUnitCommand.ACTION_GET_SETTINGS ->
                listOf(Effect.ExportSettings(extras.string(HeadUnitCommand.EXTRA_PATH)))
            HeadUnitCommand.ACTION_RESET_SETTINGS -> listOf(Effect.ResetSettings)
            HeadUnitCommand.ACTION_SET_LOG_LEVEL -> logLevel(extras)
            HeadUnitCommand.ACTION_START_LOG_CAPTURE -> listOf(Effect.StartLogCapture)
            HeadUnitCommand.ACTION_STOP_LOG_CAPTURE -> listOf(Effect.StopLogCapture)
            HeadUnitCommand.ACTION_EXPORT_LOG ->
                listOf(Effect.ExportLog(extras.string(HeadUnitCommand.EXTRA_PATH)))
            HeadUnitCommand.ACTION_LOG_MARKER -> requiring(extras, HeadUnitCommand.EXTRA_TEXT) {
                Effect.LogMarker(it)
            }

            else -> listOf(Effect.Refuse("unknown action $action"))
        }
    }

    /**
     * With an address this opens a TCP session to Android Auto's head unit server; without one it
     * means "connect to whatever is there", which is the USB check.
     */
    private fun connect(extras: Extras): List<Effect> {
        val ip = extras.string(HeadUnitCommand.EXTRA_IP)?.trim()
        if (ip.isNullOrEmpty()) return listOf(checkUsb())
        return listOf(
            Effect.StartService(
                AapService.ACTION_CONNECT_SOCKET,
                flagExtras = mapOf(AapService.EXTRA_NO_UI to extras.flag(HeadUnitCommand.EXTRA_NO_UI))
            ),
            Effect.ConnectSocket(ip, HEADUNIT_SERVER_PORT)
        )
    }

    /** An explicit command, so it lifts the status pill's X like the USB button does. */
    private fun checkUsb() = Effect.StartService(
        AapService.ACTION_CHECK_USB,
        flagExtras = mapOf(AapService.EXTRA_USER_REQUESTED to true)
    )

    private fun nightMode(extras: Extras): List<Effect> {
        val mode = extras.string(HeadUnitCommand.EXTRA_STATE)?.lowercase()
        if (mode !in NIGHT_MODES) {
            return listOf(Effect.Refuse("state must be one of ${NIGHT_MODES.joinToString("/")}"))
        }
        return listOf(Effect.SetNightMode(mode!!))
    }

    private fun logLevel(extras: Extras): List<Effect> {
        val level = extras.string(HeadUnitCommand.EXTRA_LEVEL)?.lowercase()
        if (level !in LOG_LEVELS) {
            return listOf(Effect.Refuse("level must be one of ${LOG_LEVELS.joinToString("/")}"))
        }
        return listOf(Effect.SetLogLevel(level!!))
    }

    private fun settingsImport(extras: Extras): List<Effect> {
        val json = extras.string(HeadUnitCommand.EXTRA_JSON)
        val path = extras.string(HeadUnitCommand.EXTRA_PATH)
        if (json.isNullOrBlank() && path.isNullOrBlank()) {
            return listOf(Effect.Refuse("one of json or path is required"))
        }
        return listOf(Effect.ImportSettings(json, path))
    }

    private fun requiring(extras: Extras, key: String, build: (String) -> Effect): List<Effect> {
        val value = extras.string(key)?.trim()
        if (value.isNullOrEmpty()) return listOf(Effect.Refuse("$key is required"))
        return listOf(build(value))
    }
}
