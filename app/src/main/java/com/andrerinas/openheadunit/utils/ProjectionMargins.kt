package com.andrerinas.openheadunit.utils

/** GAL's legacy totals and UiConfig must place the same pixel at each edge. */
internal object ProjectionMargins {
    // Preserve OHU's crop origin: an odd spare pixel belongs on the right and bottom.
    // Subtraction avoids overflowing a total when rounding its upper half.
    fun left(total: Int) = total / 2
    fun right(total: Int) = total - left(total)
    fun top(total: Int) = total / 2
    fun bottom(total: Int) = total - top(total)
}
