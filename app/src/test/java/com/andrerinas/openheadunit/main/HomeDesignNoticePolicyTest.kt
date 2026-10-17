package com.andrerinas.openheadunit.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDesignNoticePolicyTest {

    @Test
    fun `an app updated in place is an upgrade`() {
        assertTrue(HomeDesignNoticePolicy.isUpgrade(firstInstallTimeMs = 1_000L, lastUpdateTimeMs = 5_000L))
    }

    @Test
    fun `a fresh install has the same install and update time`() {
        assertFalse(HomeDesignNoticePolicy.isUpgrade(firstInstallTimeMs = 5_000L, lastUpdateTimeMs = 5_000L))
    }

    @Test
    fun `unknown times count as a fresh install, so nobody is asked by mistake`() {
        assertFalse(HomeDesignNoticePolicy.isUpgrade(firstInstallTimeMs = 0L, lastUpdateTimeMs = 0L))
    }
}
