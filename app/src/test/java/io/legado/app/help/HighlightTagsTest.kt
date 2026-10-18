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

    @Test fun tagBackupPreservesNoteScopeAndEveryStyleChannel() {
        val style = base.copy(fillShape = HighlightStyle.FillShape.PILL, italic = true,
            strike = HighlightStyle.Deco(71), box = HighlightStyle.Deco(72),
            emphasis = HighlightStyle.Deco(73), shadow = HighlightStyle.Shadow(),
            fontPath = "fixture.ttf", fontSize = 26f, letterSpacing = 0.2f,
            horizontalPadding = 12f, pillPaddingScale = 1.25f)
        val tag = clue.copy(note = "线索含义\n第二行说明", bookUrl = "book-a",
            style = GSON.toJson(style))
        val restored = GSON.fromJson(GSON.toJson(tag), HighlightTag::class.java)
        assertEquals(tag, restored)
        assertEquals(style, restored.styleObj())
        assertEquals(style, HighlightTags.effectiveStyle(annotation, listOf(restored), listOf(tag.id)))
        assertEquals("Keep note", annotation.note)
        assertEquals(base, annotation.styleObj())
    }

    @Test fun legacyTagBackupDefaultsItsNoteToEmpty() {
        val tag = GSON.fromJson(
            """{"id":"old","name":"伏笔","style":"{}","sortOrder":2}""",
            HighlightTag::class.java)
        assertEquals("", tag.note)
        assertEquals("", tag.bookUrl)
        assertEquals("old", tag.id)
        assertEquals(2, tag.sortOrder)
    }

    @Test fun commonAndBookVisibilityAreIndependentAndHiddenTagsCanStillResolveStyles() {
        val shared = clue.copy(common = true)
        val orders = listOf(io.legado.app.data.entities.HighlightTagOrder("book", shared.id, 0, true))
        assertTrue(HighlightTags.forBook(listOf(shared), "book", orders).isEmpty())
        assertEquals(listOf(shared), HighlightTags.forBook(listOf(shared), "another"))
        val retained = HighlightTags.forBook(listOf(shared), "book", orders, includeHidden = true)
        assertEquals(shared.styleObj(), HighlightTags.effectiveStyle(annotation, retained, listOf(shared.id)))
        assertTrue(shared.common)
    }

    @Test fun libraryMembershipAllowsCharacterLabelsInOnlyTheBooksThatAddedThem() {
        val character = clue.copy(name = "张三", bookUrl = "first", common = false)
        val second = listOf(io.legado.app.data.entities.HighlightTagOrder("second", character.id, 0))
        assertEquals(character.id, HighlightTags.forBook(listOf(character), "first").single().id)
        assertEquals(character.id, HighlightTags.forBook(listOf(character), "second", second).single().id)
        assertTrue(HighlightTags.forBook(listOf(character), "third", second).isEmpty())
    }

    @Test fun sameNamePrefersOwnLabelAndExplicitChoiceWithoutMergingTheirSettings() {
        val common = clue.copy(name = "好看", common = true)
        val own = appearance.copy(name = "好看", bookUrl = "book", common = false, note = "本书说明")
        val available = HighlightTags.forBook(listOf(common, own), "book")
        assertEquals(own.id, HighlightTags.choices(available, "book").single().id)
        assertEquals(common.id, HighlightTags.choices(available, "book", listOf(common.id)).single().id)
        assertEquals("本书说明", own.note)
        assertEquals(2, available.size)
    }
}
