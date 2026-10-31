package com.andrerinas.openheadunit

import android.content.Context

object App {
    fun provide(context: Context): ProvidedApp = ProvidedApp

    object ProvidedApp {
        val nightOverlayState: Any? = null
    }
}
