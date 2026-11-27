package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeTouch.Point
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayNativeTouchTest {
    private val touch = CarPlayNativeTouch()

    // A 2000x1000 view over a 1000x500 picture, filled: every coordinate halves.
    private fun map(vararg points: Point) =
        touch.contacts(points.toList(), 2000f, 1000f, 1000, 500, Settings.VideoFitMode.FILL)

    @Test fun `one finger keeps slot 0 from down to up and lands in picture pixels`() {
        val down = map(Point(0, 400f, 200f, true)).single()
        assertEquals(0, down.id); assertTrue(down.down); assertEquals(200.0, down.x, 0.0); assertEquals(100.0, down.y, 0.0)
        assertEquals(0, map(Point(0, 600f, 200f, true)).single().id)
        val up = map(Point(0, 600f, 200f, false)).single()
        assertEquals(0, up.id); assertFalse(up.down)
    }

    @Test fun `a pointer id above 1 still takes slot 0 and 1 in order of arrival`() {
        assertEquals(0, map(Point(7, 10f, 10f, true)).single().id)
        val both = map(Point(7, 10f, 10f, true), Point(3, 20f, 20f, true))
        assertEquals(listOf(0, 1), both.map { it.id })
    }

    @Test fun `a third finger is ignored and gets no slot until one is free`() {
        map(Point(0, 1f, 1f, true), Point(1, 2f, 2f, true))
        assertEquals(listOf(0, 1), map(Point(0, 1f, 1f, true), Point(1, 2f, 2f, true), Point(2, 3f, 3f, true)).map { it.id })
        map(Point(0, 1f, 1f, false), Point(1, 2f, 2f, true), Point(2, 3f, 3f, true))
        // Slot 0 is free after its up report, so the third finger takes it on its next event.
        val next = map(Point(1, 2f, 2f, true), Point(2, 3f, 3f, true))
        assertEquals(listOf(1, 0), next.map { it.id })
    }

    @Test fun `a lift of a finger that never went down makes no contact`() {
        assertTrue(map(Point(5, 1f, 1f, false)).isEmpty())
    }

    @Test fun `a finger that lifts frees its slot and the second finger keeps its own`() {
        map(Point(0, 1f, 1f, true), Point(1, 2f, 2f, true))
        val lift = map(Point(0, 1f, 1f, false), Point(1, 2f, 2f, true))
        assertEquals(listOf(0 to false, 1 to true), lift.map { it.id to it.down })
        assertEquals(1, map(Point(1, 4f, 4f, true)).single().id)
    }

    @Test fun `reset frees every slot for a new session`() {
        map(Point(0, 1f, 1f, true), Point(1, 2f, 2f, true))
        touch.reset()
        assertEquals(0, map(Point(9, 1f, 1f, true)).single().id)
    }

    @Test fun `contain mode maps through the same fit rule as the view`() {
        // A 1000x1000 view over a 2000x1000 picture: CONTAIN shows it 1000x500 with 250 px bars, FILL stretches it.
        // A point off the middle tells the two apart: view y 300 is 50 px into the picture, so y 100 of 1000.
        fun at(mode: Settings.VideoFitMode) =
            CarPlayNativeTouch().contacts(listOf(Point(0, 500f, 300f, true)), 1000f, 1000f, 2000, 1000, mode).single()
        val contain = at(Settings.VideoFitMode.CONTAIN)
        assertEquals(1000.0, contain.x, 1.0); assertEquals(100.0, contain.y, 1.0)
        assertEquals(300.0, at(Settings.VideoFitMode.FILL).y, 1.0)
    }
}
