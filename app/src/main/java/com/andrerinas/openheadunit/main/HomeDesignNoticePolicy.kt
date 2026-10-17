package com.andrerinas.openheadunit.main

/**
 * Decides who is asked to choose between the new and the classic home screen.
 *
 * Only users who update into the redesign are asked: they already know the old screen. A fresh
 * install has no habit to keep, so it starts on the new design and never sees the question, not
 * even after a later update. The install and update times come from PackageManager and do not
 * depend on the wall clock, which many head units get wrong.
 */
object HomeDesignNoticePolicy {

    /** Whether the app was updated in place, rather than installed as this version. */
    fun isUpgrade(firstInstallTimeMs: Long, lastUpdateTimeMs: Long): Boolean =
        lastUpdateTimeMs > firstInstallTimeMs
}
