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
        // Shared by lifecycle and audio packages; the service delivery is outside these fixtures.
        internal fun entryIntent(context: android.content.Context, owner: Long, usbScan: Any? = null): android.content.Intent =
            android.content.Intent(context, AapService::class.java).putExtra("accepted_run_owner", owner)
        var instance: AapService? = null
        const val ACTION_STOP_SERVICE = "stop"
    }
}
