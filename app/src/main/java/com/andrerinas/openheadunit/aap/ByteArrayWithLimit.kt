package com.andrerinas.openheadunit.aap

/**
 * A byte array plus how much of it is meaningful.
 *
 * [data] is mutable so the SSL layer can hand out one reused buffer per session instead of one
 * allocation per message - see `AapSslContext.plaintextBuffer`. **Read [limit], never `data.size`:**
 * the array is at least as long as the payload and usually longer.
 */
class ByteArrayWithLimit(var data: ByteArray, var limit: Int)
