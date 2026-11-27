package com.andrerinas.openheadunit.connection.carplaynative.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingStoreTest {

    @Test
    fun theStoreKeepsTheNewestSixteenAndDropsTheOldestFirst() {
        val dropped = ArrayList<String>()
        val store = PairingStore(onSave = null, onDrop = { dropped += it })
        for (i in 0 until 16) store.save("phone-$i", byteArrayOf(i.toByte()))
        store.save("phone-0", byteArrayOf(0))
        store.save("phone-16", byteArrayOf(16))
        assertEquals(listOf("phone-1"), dropped)
        assertNull(store.get("phone-1"))
        assertNotNull(store.get("phone-0"))
        assertNotNull(store.get("phone-16"))
    }

    @Test
    fun restoreAppliesTheSameCap() {
        val store = PairingStore()
        for (i in 0 until 20) store.restore("phone-$i", byteArrayOf(i.toByte()))
        for (i in 0 until 4) assertNull(store.get("phone-$i"))
        for (i in 4 until 20) assertNotNull(store.get("phone-$i"))
    }
}
