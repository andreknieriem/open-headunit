package com.andrerinas.openheadunit.utils

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager

/**
 * Some head unit ROMs report API 17+ but lack Activity.isDestroyed, which Glide.with(activity) also
 * calls. Try it once, and on NoSuchMethodError use the lifecycle and Glide's app scope from then on.
 */
object ActivityState {

    @Volatile
    internal var hasIsDestroyed = true

    fun isGone(activity: Activity): Boolean = isGone(
        activity.isFinishing,
        Build.VERSION.SDK_INT,
        { activity.isDestroyed },
        { (activity as? LifecycleOwner)?.lifecycle?.currentState },
    )

    /** Glide scoped to the activity, or to the app where the activity scope cannot be checked. */
    fun glide(activity: FragmentActivity): RequestManager =
        orFallback({ Glide.with(activity) }) { Glide.with(activity.applicationContext) }

    /** Clears a load on destroy; app-scoped loads from [glide] are cleared by nothing else. */
    fun release(activity: Activity, view: View?) {
        if (view != null) Glide.with(activity.applicationContext).clear(view)
    }

    internal fun isGone(
        finishing: Boolean,
        sdkInt: Int,
        isDestroyed: () -> Boolean,
        lifecycleState: () -> Lifecycle.State?,
    ): Boolean {
        if (finishing) return true
        val fromLifecycle = { lifecycleState() == Lifecycle.State.DESTROYED }
        if (sdkInt < Build.VERSION_CODES.JELLY_BEAN_MR1) return fromLifecycle()
        return orFallback(isDestroyed, fromLifecycle)
    }

    internal fun <T> orFallback(call: () -> T, fallback: () -> T): T {
        if (hasIsDestroyed) {
            try {
                return call()
            } catch (e: NoSuchMethodError) {
                hasIsDestroyed = false
                AppLog.w("ActivityState: this ROM has no Activity.isDestroyed, using the lifecycle and Glide's app scope")
            }
        }
        return fallback()
    }
}
