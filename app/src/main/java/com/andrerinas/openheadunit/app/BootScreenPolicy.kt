package com.andrerinas.openheadunit.app

/**
 * Whether a boot or a wake brings the app's screen to the front.
 *
 * "Start on boot" always starts the service. With "Wait in the background" on, it stops there: the
 * service keeps listening, and the screen only shows up once a phone connects and the projection
 * opens. That is how the app behaved up to 2.0.2, before the boot start also raised the home screen.
 */
object BootScreenPolicy {

    fun opensScreen(startOnBoot: Boolean, waitInBackground: Boolean): Boolean =
        startOnBoot && !waitInBackground
}
