package com.andrerinas.openheadunit.ssl

import org.junit.Assert.assertEquals
import org.junit.Test

class ConscryptInitializerTest {

    // getProviderName() hands this name to SSLContext.getInstance, so the two must agree.
    @Test
    fun theProviderBuiltDirectlyCarriesTheNameTheTlsSessionAsksFor() {
        assertEquals("Conscrypt", ConscryptInitializer.newProvider().name)
    }
}
