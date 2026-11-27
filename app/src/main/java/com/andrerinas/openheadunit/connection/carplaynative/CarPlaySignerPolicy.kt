package com.andrerinas.openheadunit.connection.carplaynative

/** Which signer native CarPlay would use: a confirmed chip, else a reachable Link, else the imported file, else none. */
object CarPlaySignerPolicy {

    enum class Choice(val label: String) { CHIP("chip"), LINK("link"), FILE("file"), NONE("none") }

    fun choose(chipConfirmed: Boolean, linkReachable: Boolean, identityFileValid: Boolean): Choice = when {
        chipConfirmed -> Choice.CHIP
        linkReachable -> Choice.LINK
        identityFileValid -> Choice.FILE
        else -> Choice.NONE
    }
}
