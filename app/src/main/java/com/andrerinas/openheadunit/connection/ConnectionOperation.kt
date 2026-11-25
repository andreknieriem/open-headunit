package com.andrerinas.openheadunit.connection

/**
 * The input that authorizes a connection precondition, before a ConnectionRequest exists.
 * A continuation keeps this exact operation, including the original Save when there is one.
 * Stop revokes [ticket] permanently; Home Exit or newer input can withdraw [save] in the same
 * Stop epoch. Neither a callback nor an effect entry may replace either part with current state.
 *
 * [CommManager.isCurrent] checks both under its lifecycle lock. A producer changing shared
 * ownership checks it inside withAppConnectionLifecycleLock before its own admission monitor;
 * SDK and blocking I/O calls follow outside monitors. Admission cannot retract I/O already begun.
 */
class ConnectionOperation internal constructor(
    val ticket: Long,
    val save: CommManager.ConnectionState.Disconnected?,
)
