package io.legado.app.help

import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightTag
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test

class HighlightTagsTest {
    private val base = HighlightStyle(fill = 17, bold = true,
        underline = HighlightStyle.Underline(color = 25))
    private val annotation = BookHighlight(time = 1, note = "Keep note").apply { applyStyle(base) }
    private fun tag(id: String, order: Int, kind: HighlightStyle.Kind) = HighlightTag(
        id, id, GSON.toJson(HighlightStyle(underline = HighlightStyle.Underline(kind, 81))), order
    )
    private val clue = tag("clue", 0, HighlightStyle.Kind.DASHED)
    private val appearance = tag("appearance", 1, HighlightStyle.Kind.DOUBLE)

    @Test fun labelReplacesDisplayInsteadOfMergingBaseChannels() {
        val resolved = HighlightTags.effectiveStyle(annotation, listOf(clue), listOf("clue"))
        assertEquals(clue.styleObj(), resolved)
        assertEquals(0, resolved.fill)
        assertFalse(resolved.bold)
        assertEquals(base, annotation.styleObj())
        assertEquals("Keep note", annotation.note)
    }

    @Test fun globalOrderWinsRegardlessOfSelectionOrder() {
        assertEquals(clue, HighlightTags.primary(listOf(appearance, clue), listOf("appearance", "clue")))
        val reordered = listOf(clue.copy(sortOrder = 1), appearance.copy(sortOrder = 0))
        assertEquals(appearance.styleObj(),
            HighlightTags.effectiveStyle(annotation, reordered, listOf("clue", "appearance")))
    }

    @Test fun removingPrimaryUsesNextAndRemovingAllRestoresExactBase() {
        val tags = listOf(clue, appearance)
        assertEquals(appearance.styleObj(),
            HighlightTags.effectiveStyle(annotation, tags, listOf("appearance")))
        assertEquals(base, HighlightTags.effectiveStyle(annotation, tags, emptyList()))
        assertEquals(base, HighlightTags.effectiveStyle(annotation, emptyList(), listOf("deleted")))
    }

    @Test fun editingBaseWhileTaggedKeepsLabelAppearanceAndNewFallback() {
        val newer = base.copy(fill = 99)
        annotation.applyStyle(newer)
        assertEquals(clue.styleObj(),
            HighlightTags.effectiveStyle(annotation, listOf(clue), listOf("clue")))
        assertEquals(newer, HighlightTags.effectiveStyle(annotation, listOf(clue), emptyList()))
    }

    @Test fun defaultsHaveStableIdsAndIndependentUnderlineStyles() {
        assertEquals(HighlightTags.defaults(), HighlightTags.defaults())
        val tags = HighlightTags.defaults()
        assertEquals(listOf("赞", "雷", "伏笔", "外貌"), tags.map { it.name })
        assertEquals(4, tags.map { it.id }.distinct().size)
        assertTrue(tags.all { it.styleObj().underline != null })
    }
}
