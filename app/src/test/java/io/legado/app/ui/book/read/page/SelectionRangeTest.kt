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

    @Test fun `leaving or changing the edge rearms while a little jitter keeps direction`() {
        assertEquals(1, SelectionEdgeWait.direction(780f, 0f, 800f, 24f, 0))
        assertEquals(1, SelectionEdgeWait.direction(770f, 0f, 800f, 24f, 1))
        assertEquals(0, SelectionEdgeWait.direction(760f, 0f, 800f, 24f, 1))
        assertEquals(-1, SelectionEdgeWait.direction(10f, 0f, 800f, 24f, 1))
        val wait = SelectionEdgeWait()
        wait.update(1, 0)
        wait.update(-1, 500)
        assertEquals(1300L, wait.deadline)
    }
}
