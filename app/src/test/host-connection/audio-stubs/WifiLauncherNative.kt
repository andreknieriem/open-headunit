package com.andrerinas.openheadunit.connection.wifi.modes

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy

/** Network creation is outside the observer test; retain the selected production strategy. */
class WifiLauncherNative(val strategy: NativeStrategy)
