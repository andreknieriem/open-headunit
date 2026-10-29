package com.andrerinas.openheadunit.connection.projection

/** Debug fault: turns one successful bulk read into the failed read a bad transfer gives. */
object UsbReadFaultPolicy {
    /** Only a read that carried data can lose any, so an empty read keeps the fault armed. */
    fun shouldFault(armed: Boolean, bytesRead: Int): Boolean = armed && bytesRead > 0
}
