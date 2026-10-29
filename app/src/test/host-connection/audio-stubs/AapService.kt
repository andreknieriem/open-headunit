package com.andrerinas.openheadunit.aap
import com.andrerinas.openheadunit.connection.AppExitPolicy

class AapService {
    var stops = 0
    internal var exitRequest: AppExitPolicy.Request? = null
    internal fun stopForAppExit(request: AppExitPolicy.Request) {
        exitRequest = request
        stops++
    }
    companion object {
        var instance: AapService? = null
        const val ACTION_STOP_SERVICE = "stop"
    }
}
