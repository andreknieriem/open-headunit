package com.andrerinas.openheadunit.connection.self.launchers

import android.widget.Toast
import com.andrerinas.openheadunit.connection.self.SelfLauncher
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherServices
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SelfLauncherV17_4(
    manager: SelfLauncherManager,
    services: SelfLauncherServices) : SelfLauncher(manager, services) {

    override val name = "v17.4+"

    override suspend fun run(): Boolean {
        // Call withContext directly, not on 'service'
        val success = withContext(Dispatchers.IO) {
            commManager.connect("127.0.0.1", 5277, expectedState = services.settingsRestart)
            commManager.isConnected
        }

        if (!success && !commManager.isConnected) {
            AppLog.w("SelfMode: Headunit Server (127.0.0.1:5277) is NOT running.")
            ToastUtils.showToast(
                services.aap,
                "Android Auto 17.4+ detected: Please start 'Headunit Server' in Android Auto Developer Settings!",
                Toast.LENGTH_LONG
            )
            manager.openAaSettings()
            return false
        }

        return true
    }
}
