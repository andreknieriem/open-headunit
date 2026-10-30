package com.andrerinas.openheadunit.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONObject

/**
 * The app's command surface for automation tools and shell scripts. A receiver rather than an
 * activity because a broadcast needs no "Display over other apps"; `AutomationActivity` is the same
 * vocabulary for deep links. Replies land on the ordered-broadcast result, so `am` prints `data=`;
 * a sender that cannot send ordered reads state from `SessionStateIntent` instead.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        AppLog.i("AutomationReceiver: $action")

        val effects = AutomationCommandPolicy.effectsFor(
            action,
            IntentExtras(intent),
            AutomationCommandPolicy.State(
                externalConfigAllowed = App.provide(context).settings.allowExternalConfiguration
            ),
            acceptStop = {
                val component = App.provide(context)
                component.commManager.requestExplicitAppExit(
                    component.settings.killOnDisconnect && component.commManager.isConnected).id
            }
        )

        val reply = JSONObject().put("action", action ?: "")

        val ok = try {
            AutomationEffectRunner.run(context, effects, reply)
        } catch (e: Exception) {
            reply.put("error", e.message ?: e.javaClass.simpleName)
            AppLog.e("AutomationReceiver: $action failed: ${e.message}", e)
            false
        }

        reply.put("ok", ok)
        // Answered inline, not through goAsync(): that detaches the pending result and setResultData
        // then throws. The two background effects are best effort either way, and every command that
        // matters starts a foreground service that keeps the process up.
        if (isOrderedBroadcast) {
            resultCode = if (ok) 0 else 1
            resultData = reply.toString()
        }
    }
}
