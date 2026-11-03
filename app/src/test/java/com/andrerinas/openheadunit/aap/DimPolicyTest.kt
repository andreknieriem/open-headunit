package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Test

class DimPolicyTest {
    @Test fun alphaIsThePercentOfTwoFiftyFive() {
        assertEquals(26, DimPolicy.alphaFor(10))
        assertEquals(128, DimPolicy.alphaFor(50))
        assertEquals(230, DimPolicy.alphaFor(90))
    }

    @Test fun outOfRangeValuesClamp() {
        assertEquals(26, DimPolicy.alphaFor(0))
        assertEquals(230, DimPolicy.alphaFor(100))
        assertEquals(26, DimPolicy.alphaFor(-5))
    }
}
