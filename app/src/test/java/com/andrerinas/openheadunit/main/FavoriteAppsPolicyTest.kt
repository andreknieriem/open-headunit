package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteAppsPolicyTest {

    private val empty = listOf("", "", "", "")

    @Test
    fun `nothing stored decodes to four empty slots`() {
        assertEquals(empty, FavoriteAppsPolicy.decode(""))
    }

    @Test
    fun `a short or overlong value still decodes to four slots`() {
        assertEquals(listOf("a", "b", "", ""), FavoriteAppsPolicy.decode("a,b"))
        assertEquals(listOf("a", "b", "c", "d"), FavoriteAppsPolicy.decode("a,b,c,d,e"))
    }

    @Test
    fun `encode and decode keep empty slots in place`() {
        val slots = listOf("", "maps", "", "music")
        assertEquals(slots, FavoriteAppsPolicy.decode(FavoriteAppsPolicy.encode(slots)))
    }

    @Test
    fun `placing an app already in another slot moves it`() {
        val slots = listOf("maps", "music", "", "")
        assertEquals(listOf("", "music", "maps", ""), FavoriteAppsPolicy.place(slots, 2, "maps"))
    }

    @Test
    fun `adding fills the first free slot`() {
        val slots = listOf("maps", "", "", "music")
        assertEquals(listOf("maps", "radio", "", "music"), FavoriteAppsPolicy.add(slots, "radio"))
    }

    @Test
    fun `adding an app that is already a favorite changes nothing`() {
        val slots = listOf("maps", "", "", "")
        assertEquals(slots, FavoriteAppsPolicy.add(slots, "maps"))
    }

    @Test
    fun `adding to a full row is refused`() {
        assertNull(FavoriteAppsPolicy.add(listOf("a", "b", "c", "d"), "e"))
    }

    @Test
    fun `removing empties only that slot`() {
        assertEquals(listOf("a", "", "c", "d"), FavoriteAppsPolicy.remove(listOf("a", "b", "c", "d"), 1))
    }
}
