package com.andrerinas.openheadunit.main
 
import com.andrerinas.openheadunit.utils.CarLauncherManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
 
class CarLauncherExitButtonPolicyTest {
 
    @Test
    fun exitButtonShownWhenLauncherModeDisabledAndNotDefault() {
        val shouldShow = CarLauncherManager.shouldShowExitButton(
            isCarLauncherEnabled = false,
            isDefaultLauncher = false
        )
        assertTrue("Exit button should be visible when not in launcher mode", shouldShow)
    }
 
    @Test
    fun exitButtonHiddenWhenCarLauncherEnabled() {
        val shouldShow = CarLauncherManager.shouldShowExitButton(
            isCarLauncherEnabled = true,
            isDefaultLauncher = false
        )
        assertFalse("Exit button should be hidden when Car Launcher mode is enabled", shouldShow)
    }
 
    @Test
    fun exitButtonHiddenWhenIsDefaultLauncher() {
        val shouldShow = CarLauncherManager.shouldShowExitButton(
            isCarLauncherEnabled = false,
            isDefaultLauncher = true
        )
        assertFalse("Exit button should be hidden when OpenHU is resolved as default launcher", shouldShow)
    }
 
    @Test
    fun exitButtonHiddenWhenBothEnabledAndDefault() {
        val shouldShow = CarLauncherManager.shouldShowExitButton(
            isCarLauncherEnabled = true,
            isDefaultLauncher = true
        )
        assertFalse("Exit button should be hidden when both enabled and default launcher", shouldShow)
    }
}
