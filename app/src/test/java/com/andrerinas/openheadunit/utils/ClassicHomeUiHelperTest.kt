package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicHomeUiHelperTest {

    @Test
    fun isEnabledReturnsChosenWhenDisconnected() {
        assertFalse(ClassicHomeUiHelper.isEnabled(chosen = false, isConnected = false))
        assertTrue(ClassicHomeUiHelper.isEnabled(chosen = true, isConnected = false))
    }

    @Test
    fun isEnabledReturnsTrueWhenConnectedEvenIfNotChosen() {
        assertTrue(ClassicHomeUiHelper.isEnabled(chosen = false, isConnected = true))
        assertTrue(ClassicHomeUiHelper.isEnabled(chosen = true, isConnected = true))
    }
}
