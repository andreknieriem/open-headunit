package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherHelper
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeStrategy
import org.mockito.kotlin.mock

object WifiLauncherMock {

    fun create(
        mode: WifiLauncherMode,
        helperStrategy: HelperStrategy? = null,
        nativeStrategy: NativeStrategy? = null
    ) : WifiLauncher {
        val manager = mock<WifiLauncherManager>()

        if (mode == WifiLauncherMode.HELPER)
            return WifiLauncherHelper(manager, helperStrategy!!)
        else if (mode == WifiLauncherMode.NATIVE)
            return WifiLauncherNative(manager, nativeStrategy!!)
        else // we can just use factory, if strategy wouldn't default from settings
            return mode.factory(manager)
    }
}
