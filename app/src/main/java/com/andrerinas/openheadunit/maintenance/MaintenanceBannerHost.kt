package com.andrerinas.openheadunit.maintenance

/**
 * Where AapService hands a banner to the projection screen. The screen sets [show] while it is in
 * front and clears it when it leaves, so the service only reads items while Android Auto is
 * actually showing. Main thread only, on both sides.
 */
object MaintenanceBannerHost {

    /** Shows the banner for an item, in miles or not; returns false when the screen cannot now. */
    var show: ((entry: MaintenanceReminderPolicy.Entry, miles: Boolean) -> Boolean)? = null
}
