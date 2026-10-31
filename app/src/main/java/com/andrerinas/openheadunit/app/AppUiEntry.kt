package com.andrerinas.openheadunit.app

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
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

    /**
     * Uses the retained logical activity to recognize configuration continuity, not a restored Intent.
     * A null result means callers must not replay the launch command for this creation.
     * Only a new OPEN delivery acquires app ownership here.
     */
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
        // The Open and its USB scan are one input even if a peer Exit arrives on IO. Keep the
        // hold until delivery; an older queued Open can never release a newer Stop's decision.
        val (owner, usbScan) = manager.withAppConnectionLifecycleLock {
            manager.onAppRelaunched() to
                if (UsbReceiver.isUserStopped(activity)) manager.usbRecheckNow() else null
        }
        manager.restoreAppForEntry(owner, usbScan)
    }
    fun foregroundStarted(activity: Activity) {
        val entryActivity = isEntryActivity(activity) && App.isUserUnlocked(activity)
        val revision = if (entryActivity) App.provide(activity).commManager.appOwnershipRevision() else 0L
        policy.foregroundStarted(revision, entryActivity)
    }

    fun fullyStopped(activity: Activity) {
        policy.fullyStopped(activity.isChangingConfigurations)
    }

    /**
     * Consumes one return after an observed full stop that was not a configuration change.
     * Checks the saved ownership revision before accepting a Run, so a newer Stop still wins.
     * This lifecycle return does not replay an explicitly delivered launch command.
     */
    fun resumed(activity: Activity) {
        val revision = policy.resumeRevision() ?: return
        val manager = App.provide(activity).commManager
        val entry = manager.withAppConnectionLifecycleLock {
            val owner = manager.acceptAppResume(revision) ?: return@withAppConnectionLifecycleLock null
            owner to if (UsbReceiver.isUserStopped(activity)) manager.usbRecheckNow() else null
        } ?: return
        manager.restoreAppForEntry(entry.first, entry.second)
    }
}
