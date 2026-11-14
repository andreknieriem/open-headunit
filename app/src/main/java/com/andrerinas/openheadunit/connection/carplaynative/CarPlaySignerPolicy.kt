package com.andrerinas.openheadunit.connection.carplaynative

/** Which signer native CarPlay would use: a confirmed chip, else the user's imported file, else none. */
object CarPlaySignerPolicy {

    enum class Choice(val label: String) { CHIP("chip"), FILE("file"), NONE("none") }

    fun choose(chipConfirmed: Boolean, identityFileValid: Boolean): Choice = when {
        chipConfirmed -> Choice.CHIP
        identityFileValid -> Choice.FILE
        else -> Choice.NONE
    }
}
