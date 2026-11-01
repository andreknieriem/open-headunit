package com.andrerinas.openheadunit.connection

/**
 * One owed USB scan: a scan that could not run when it was asked for and is asked again later,
 * by whoever holds it. [CommManager.usbRecheck] mints it under transportLifecycleLock with the
 * Stop ticket of the input it came from and the current withdrawal epoch; a user exit, Stop or a
 * PROJECTION_UNRAISED end advances that epoch. [CommManager.isCurrent] is its one validity
 * check: wherever the scan waits (a live attempt, the arbiter, a delayed give-back, the launcher's
 * queued scan, the settings hold) it is withdrawn by construction, and the eventual scan and USB
 * admission run only while it is still current. Each object is handed on, never copied, so one
 * scan is asked at most once.
 */
class UsbRecheck internal constructor(val ticket: Long, internal val epoch: Long)
