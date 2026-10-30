package com.andrerinas.openheadunit.connection.self.launchers

import com.andrerinas.openheadunit.connection.self.SelfLauncher
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SelfLauncherV17_4(
    manager: SelfLauncherManager,
    services: SelfLauncherServices) : SelfLauncher(manager, services) {

    override val name = "v17.4+"

    override suspend fun run(): Boolean {
        services.ensureLaunchAllowed()
        // Call withContext directly, not on 'service'
        withContext(Dispatchers.IO) {
            commManager.connect("127.0.0.1", 5277, expectedState = services.settingsRestart,
                ticket = services.operationTicket)
        }
        services.ensureLaunchAllowed()
        // An arrival, not the state now: a session that came and went, or one an Error already
        // hid, still reached the server. No arrival is not yet a failure: connect returns at once
        // when it joins an attempt already forming, which may be this launch's own. The manager
        // decides the outcome, and shows the Headunit Server guidance only for one that stands.
        return services.arrivedSinceLaunch()
    }
}
