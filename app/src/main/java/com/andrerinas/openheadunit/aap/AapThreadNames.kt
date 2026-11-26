package com.andrerinas.openheadunit.aap

/** Worker thread names. The kernel keeps 15 characters, so each name must differ inside them. */
internal object AapThreadNames {
    const val VIDEO = "Aap::Video"
    const val SEND = "Aap::Send"
    const val POLL = "Aap::Poll"
}
