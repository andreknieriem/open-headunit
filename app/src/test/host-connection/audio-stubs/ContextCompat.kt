package androidx.core.content

import android.content.Context
import android.content.Intent

object ContextCompat {
    val serviceStarts = mutableListOf<Intent>()
    fun startForegroundService(context: Context, intent: Intent): Unit {
        serviceStarts.add(intent)
    }
}
