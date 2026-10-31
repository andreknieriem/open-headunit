package com.andrerinas.openheadunit.app

import android.app.admin.DeviceAdminReceiver

/** Holds the force-lock grant that lets the app turn the screen off. It declares no other policy. */
class ScreenOffAdminReceiver : DeviceAdminReceiver()
