package com.andrerinas.openheadunit.main

import android.view.KeyEvent

object KeymapAssignPolicy {

    /** Back bound to Do nothing would leave no way out of the projection, so its picker has no such row. */
    fun offersIgnore(physicalKey: Int): Boolean = physicalKey != KeyEvent.KEYCODE_BACK
}
