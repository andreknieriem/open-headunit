package com.andrerinas.openheadunit.connection.carkey.fyt

import com.andrerinas.openheadunit.input.InputAction
import org.junit.Assert.assertEquals
import org.junit.Test

class FytKeyTranslationPolicyTest {
    @Test fun allSevenRawValuesTranslate() {
        val expected = mapOf(547 to 23, 550 to 21, 551 to 22, 574 to 87, 575 to 88, 587 to 88, 588 to 87)
        for ((raw, key) in expected) {
            assertEquals(raw.toString(), key, FytKeyTranslationPolicy.sendCode(raw, emptyMap()))
        }
    }

    @Test fun anUnknownValueStaysRaw() {
        assertEquals(576, FytKeyTranslationPolicy.sendCode(576, emptyMap()))
    }

    @Test fun aMappedRawValueStaysRaw() {
        assertEquals(547, FytKeyTranslationPolicy.sendCode(547, mapOf(547 to InputAction.AaKey(66))))
    }

    @Test fun aRawValueBoundToNothingTranslates() {
        assertEquals(23, FytKeyTranslationPolicy.sendCode(547, mapOf(547 to InputAction.None)))
    }

    @Test fun anIgnoredRawValueStaysRaw() {
        assertEquals(547, FytKeyTranslationPolicy.sendCode(547, mapOf(547 to InputAction.Ignore)))
    }
}
