package com.andrerinas.openheadunit.app

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.main.SettingsActivity
import java.util.UUID

internal class AppUiEntryIdentity : ViewModel() {
    val activity = AppForegroundPolicy.LogicalActivity()
}

/** Actual delivery classification and fully-stopped return handling; all callers run on Main. */
internal object AppUiEntry {
    private const val EXTRA_CONTINUATION = "com.andrerinas.openheadunit.UI_CONTINUATION"
    // A stored marker from a previous process is not evidence of a current internal handoff.
    private val continuationTag = UUID.randomUUID().toString()
    private val policy = AppForegroundPolicy()

    fun isEntryActivity(activity: Activity): Boolean =
        activity is MainActivity || activity is AapProjectionActivity || activity is SettingsActivity

    /** Mark automatic/internal handoffs, including full-screen launch intents, but not human taps. */
    fun continuation(intent: Intent): Intent = intent.putExtra(EXTRA_CONTINUATION, continuationTag)

    private fun classify(intent: Intent?): AppForegroundPolicy.Kind {
        val internal = intent?.getStringExtra(EXTRA_CONTINUATION) == continuationTag
        intent?.removeExtra(EXTRA_CONTINUATION)
        return when {
            AppExitPolicy.isExitCommand(intent?.action, intent?.data?.scheme, intent?.data?.host) ->
                AppForegroundPolicy.Kind.EXIT
            internal -> AppForegroundPolicy.Kind.INTERNAL_CONTINUATION
            else -> AppForegroundPolicy.Kind.OPEN
        }
    }

    fun created(activity: Activity, identity: AppUiEntryIdentity): AppForegroundPolicy.Kind? {
        val delivery = policy.created(identity.activity, classify(activity.intent)) ?: return null
        acceptDelivery(activity, delivery)
        return delivery
    }

    fun delivered(activity: Activity, intent: Intent) {
        acceptDelivery(activity, policy.delivered(classify(intent)))
    }

    private fun acceptDelivery(activity: Activity, delivery: AppForegroundPolicy.Kind) {
        if (delivery != AppForegroundPolicy.Kind.OPEN) return
        val manager = App.provide(activity).commManager
        val owner = manager.onAppRelaunched()
        manager.restoreAppForEntry(owner)
    }
    fun foregroundStarted(activity: Activity) {
        val entryActivity = isEntryActivity(activity) && App.isUserUnlocked(activity)
        val revision = if (entryActivity) App.provide(activity).commManager.appOwnershipRevision() else 0L
        policy.foregroundStarted(revision, entryActivity)
    }

    fun fullyStopped(activity: Activity) {
        policy.fullyStopped(activity.isChangingConfigurations)
    }

    fun resumed(activity: Activity) {
        val revision = policy.resumeRevision() ?: return
        val manager = App.provide(activity).commManager
        val owner = manager.acceptAppResume(revision) ?: return
        manager.restoreAppForEntry(owner)
    }
}
