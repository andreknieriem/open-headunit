package com.andrerinas.openheadunit.utils

import android.os.Build

/**
 * libsu declares minSdk 19. Below it, two of its classes catch ReflectiveOperationException, which
 * API 17 and 18 lack, so the first root check throws VerifyError past any catch (Exception).
 */
object LibsuAvailability {
    const val MIN_SDK = 19

    fun usable(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= MIN_SDK
}
