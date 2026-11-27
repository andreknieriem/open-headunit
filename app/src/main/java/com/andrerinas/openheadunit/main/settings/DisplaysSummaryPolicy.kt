package com.andrerinas.openheadunit.main.settings

import com.andrerinas.openheadunit.R

/** The one line the Displays entry shows in the main list. */
object DisplaysSummaryPolicy {

    /** [textResId] null means [args] first item is the text as it is. */
    data class Summary(val textResId: Int?, val args: List<String>)

    /** [projectionLabel] is blank where the projection cannot move to another display. */
    fun summarize(projectionLabel: String, secondScreenOn: Boolean, outputLabel: String): Summary {
        val output = if (secondScreenOn) outputLabel else ""
        return when {
            projectionLabel.isNotBlank() && output.isNotBlank() ->
                Summary(R.string.displays_value_both, listOf(projectionLabel, output))
            projectionLabel.isNotBlank() -> Summary(null, listOf(projectionLabel))
            output.isNotBlank() -> Summary(R.string.displays_value_second, listOf(output))
            else -> Summary(R.string.displays_value_second_off, emptyList())
        }
    }
}
