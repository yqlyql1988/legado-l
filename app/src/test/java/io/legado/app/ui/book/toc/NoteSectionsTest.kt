package io.legado.app.ui.book.toc

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteSectionsTest {

    private data class Row(val chapter: Int, val id: Long)

    @Test
    fun `empty list has no sections`() {
        assertEquals(
            emptyMap<Long, Int>(),
            buildChapterSections(emptyList<Row>(), { it.chapter }, { it.id })
        )
    }

    @Test
    fun `contiguous chapters become sections`() {
        val rows = listOf(
            Row(2, 10),
            Row(2, 11),
            Row(3, 12),
            Row(2, 13),
        )

        assertEquals(
            mapOf(10L to 2, 12L to 1, 13L to 1),
            buildChapterSections(rows, { it.chapter }, { it.id })
        )
    }

    @Test
    fun `section starts keep the counts of the filtered list`() {
        val rows = listOf(Row(5, 1), Row(5, 2), Row(5, 3))

        assertEquals(
            mapOf(1L to 3),
            buildChapterSections(rows, { it.chapter }, { it.id })
        )
    }

    @Test
    fun `different chapter keys under one index stay separate`() {
        data class Note(val index: Int, val name: String, val id: Long)
        val notes = listOf(
            Note(1, "第1章 起点", 1),
            Note(1, "第1章 起点", 2),
            Note(1, "第1章 旧题", 3),
        )

        assertEquals(
            mapOf(1L to 2, 3L to 1),
            buildChapterSections(notes, { it.index to it.name }, { it.id })
        )
    }
}
