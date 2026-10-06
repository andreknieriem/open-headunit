package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.projection.ProjectionConnection

interface AapSsl {
    fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit?
    fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit?
    fun postHandshakeReset()
    fun performHandshake(connection: ProjectionConnection): Boolean
    fun release()
    fun setControlRecordListener(listener: (() -> Unit)?) {}
    fun drainControlRecords(): List<ByteArray> = emptyList()
}
