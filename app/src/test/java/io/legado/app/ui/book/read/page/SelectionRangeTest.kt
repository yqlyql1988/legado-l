package io.legado.app.ui.book.read.page

import org.junit.Assert.*
import org.junit.Test

class SelectionRangeTest {
    @Test fun `dragging either end preserves the other chapter anchor across many pages`() {
        val range = SelectionRange(SelectionSpan(20, 21), SelectionSpan(24, 25))
        range.begin(false)
        range.move(SelectionSpan(12000, 12002))
        assertEquals(20, range.start)
        assertEquals(12002, range.end)
        assertFalse(range.reversed)
        range.begin(true)
        range.move(SelectionSpan(3, 5))
        assertEquals(3, range.start)
        assertEquals(12002, range.end)
        assertTrue(range.contains(SelectionSpan(24, 25)))
    }

    @Test fun `crossed handles can reverse repeatedly without moving the fixed character`() {
        val range = SelectionRange(SelectionSpan(10, 12), SelectionSpan(30, 31))
        range.begin(false)
        range.move(SelectionSpan(3, 5))
        assertEquals(3, range.start)
        assertEquals(12, range.end)
        assertTrue(range.reversed)
        range.move(SelectionSpan(40, 42))
        assertEquals(10, range.start)
        assertEquals(42, range.end)
        assertFalse(range.reversed)
        range.begin(true)
        range.move(SelectionSpan(50, 51))
        assertEquals(40, range.start)
        assertEquals(51, range.end)
        assertTrue(range.reversed)
    }

    @Test fun `span containment keeps whole surrogate pairs and excludes the following character`() {
        val range = SelectionRange(SelectionSpan(10, 12), SelectionSpan(15, 17))
        assertTrue(range.contains(SelectionSpan(10, 12)))
        assertTrue(range.contains(SelectionSpan(15, 17)))
        assertFalse(range.contains(SelectionSpan(9, 11)))
        assertFalse(range.contains(SelectionSpan(17, 18)))
    }

    @Test fun `edge deadlines are longer and movement inside the same edge does not postpone them`() {
        val wait = SelectionEdgeWait()
        wait.update(1, 100)
        assertEquals(900L, wait.deadline)
        wait.update(1, 600)
        assertEquals(900L, wait.deadline)
        wait.repeat(900)
        assertEquals(1900L, wait.deadline)
        wait.update(0, 1000)
        assertEquals(0L, wait.deadline)
        wait.update(-1, 1100)
        assertEquals(1900L, wait.deadline)
        wait.reset()
        assertEquals(0, wait.direction)
        assertEquals(0L, wait.deadline)
    }

    private fun direction(x: Float, y: Float) =
        SelectionEdgeWait.direction(x, y, 20f, 40f, 380f, 800f, 48f, 24f)

    @Test fun `only the top left and bottom right text corners turn pages`() {
        assertEquals(-1, direction(20f, 40f))
        assertEquals(-1, direction(68f, 64f))
        assertEquals(1, direction(380f, 800f))
        assertEquals(1, direction(332f, 776f))
        for ((x, y) in listOf(200f to 40f, 200f to 800f, 380f to 40f,
            20f to 800f, 20f to 400f, 380f to 400f, 200f to 400f)
        ) assertEquals("No page turn at ($x, $y)", 0, direction(x, y))
    }

    @Test fun `leaving either corner axis cancels even just beyond its boundary`() {
        for ((x, y) in listOf(68.1f to 40f, 20f to 64.1f, 331.9f to 800f,
            380f to 775.9f, 19.9f to 40f, 20f to 39.9f,
            380.1f to 800f, 380f to 800.1f)
        ) assertEquals("Outside the corner at ($x, $y)", 0, direction(x, y))
    }

    @Test fun `leaving and reentering or changing a corner restarts the full dwell`() {
        val wait = SelectionEdgeWait()
        wait.update(direction(350f, 790f), 0)
        wait.update(direction(360f, 780f), 500)
        assertEquals(800L, wait.deadline)
        wait.update(direction(200f, 790f), 600)
        assertEquals(0, wait.direction)
        assertEquals(0L, wait.deadline)
        wait.update(direction(350f, 790f), 700)
        assertEquals(1500L, wait.deadline)
        wait.update(direction(30f, 50f), 900)
        assertEquals(-1, wait.direction)
        assertEquals(1700L, wait.deadline)
    }

    @Test fun `corners follow offset landscape content and never extend into margins`() {
        fun landscape(x: Float, y: Float) =
            SelectionEdgeWait.direction(x, y, 120f, 80f, 1080f, 560f, 48f, 24f)
        assertEquals(-1, landscape(150f, 100f))
        assertEquals(1, landscape(1050f, 540f))
        assertEquals(0, landscape(50f, 100f))
        assertEquals(0, landscape(1150f, 540f))
        assertEquals(0, landscape(600f, 550f))
        assertEquals(0, landscape(1050f, 570f))
        assertEquals(0, landscape(150f, 70f))
    }

    @Test fun `empty content never triggers and tiny content keeps opposite corners separate`() {
        assertEquals(0, SelectionEdgeWait.direction(0f, 0f, 0f, 0f, 0f, 0f, 48f, 24f))
        assertEquals(-1, SelectionEdgeWait.direction(1f, 1f, 0f, 0f, 40f, 20f, 48f, 24f))
        assertEquals(1, SelectionEdgeWait.direction(39f, 19f, 0f, 0f, 40f, 20f, 48f, 24f))
        assertEquals(0, SelectionEdgeWait.direction(39f, 1f, 0f, 0f, 40f, 20f, 48f, 24f))
    }
}
