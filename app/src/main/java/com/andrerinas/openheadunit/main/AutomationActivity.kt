package com.andrerinas.openheadunit.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.automation.AutomationCommandPolicy
import com.andrerinas.openheadunit.automation.AutomationEffectRunner
import com.andrerinas.openheadunit.automation.IntentExtras
import com.andrerinas.openheadunit.contract.HeadUnitCommand
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.DisplayTargets
import com.andrerinas.openheadunit.utils.Settings
import org.json.JSONObject

/**
 * Handles App Shortcuts and `headunit://` deep links, sharing every decision and action with
 * `AutomationReceiver`. It exists because those can only target an activity, and because the Self
 * Mode pre-launch below needs a foreground window. Prefer the receiver where either will do.
 */
class AutomationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Invisible activity
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val data = intent.data
        AppLog.i("AutomationActivity: Received intent. Action: ${intent.action}, Data: $data")

        val action = if (data?.scheme == "headunit") actionFor(data.host) else intent.action
        if (action != null) {
            runCommand(action, if (data?.scheme == "headunit") queryParameters(data) else emptyMap())
        }

        finish()
    }

    /** `headunit://connect?ip=…`, `disconnect`, `exit`, `nightmode?state=…`. */
    private fun actionFor(host: String?): String? = when (host) {
        "connect" -> HeadUnitCommand.ACTION_CONNECT
        "disconnect" -> HeadUnitCommand.ACTION_DISCONNECT
        "exit" -> HeadUnitCommand.ACTION_STOP_SERVICE
        "nightmode" -> HeadUnitCommand.ACTION_SET_NIGHT_MODE
        else -> null
    }

    /**
     * A deep link carries arguments as query parameters, not extras, so they go to [IntentExtras] as
     * overrides. Passing all of them keeps this door accepting exactly what the receiver does.
     */
    private fun queryParameters(data: Uri): Map<String, String> =
        data.queryParameterNames.mapNotNull { name ->
            data.getQueryParameter(name)?.let { name to it }
        }.toMap()

    private fun runCommand(action: String, overrides: Map<String, String>) {
        val effects = AutomationCommandPolicy.effectsFor(
            action,
            IntentExtras(intent, overrides),
            AutomationCommandPolicy.State(
                externalConfigAllowed = App.provide(this).settings.allowExternalConfiguration
            )
        )
        AutomationEffectRunner.run(this, effects, JSONObject())

        if (action == HeadUnitCommand.ACTION_START_SELF_MODE) preLaunchProjection()
    }

    /**
     * [FIX] Launch the projection now, while this activity still has a foreground window: Android 10+
     * silently blocks the background launch AapService would otherwise make once the Self Mode
     * handshake finishes, leaving the user at the launcher. This is the one thing a broadcast cannot
     * do, and the reason this activity is more than a second spelling of the receiver.
     */
    private fun preLaunchProjection() {
        try {
            startActivity(
                AapProjectionActivity.intent(this).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
                DisplayTargets.projectionLaunchOptions(this, Settings(this)),
            )
        } catch (e: Exception) {
            AppLog.w("AutomationActivity: Could not pre-launch AapProjectionActivity: ${e.message}")
        }
    }
}
