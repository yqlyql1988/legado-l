package io.legado.app.help

import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import org.junit.Assert.*
import org.junit.Test

class HighlightWorkflowTest {
    @Test fun localLabelsAndPriorityStayInsideTheirBook() {
        val global = HighlightTags.defaults()
        val localA = HighlightTag(id = "a", name = "嫌疑人", bookUrl = "a")
        val localB = HighlightTag(id = "b", name = "嫌疑人", bookUrl = "b")
        val definitions = global + localA + localB
        val order = listOf(HighlightTagOrder("a", "a", 0),
            HighlightTagOrder("a", global.last().id, 1))
        val a = HighlightTags.forBook(definitions, "a", order)
        val b = HighlightTags.forBook(definitions, "b", order)
        assertEquals("a", a.first().id)
        assertFalse(a.any { it.id == "b" })
        assertEquals(global.first().id, b.first().id)
        assertFalse(b.any { it.id == "a" })
        assertEquals("a", HighlightTags.primary(a, listOf(global.first().id, "a"))?.id)
        assertEquals(global.last().id, HighlightTags.primary(a, listOf(global.first().id, global.last().id))?.id)
        assertEquals(global, definitions.take(4))
    }

    @Test fun oldLabelJsonDefaultsToSharedAndNewScopesRoundTrip() {
        val old = GSON.fromJsonArray<HighlightTag>("""[{"id":"old","name":"伏笔","style":"{}","sortOrder":2}]""")
            .getOrThrow().single()
        assertEquals("", old.bookUrl)
        val local = old.copy(bookUrl = "book")
        assertEquals(listOf(old, local), GSON.fromJsonArray<HighlightTag>(GSON.toJson(listOf(old, local))).getOrThrow())
    }

    @Test fun filterIncludesSecondaryLabelsAndUntaggedLegacyAnnotations() {
        assertTrue(HighlightTags.matchesFilter("clue", listOf("like", "clue")))
        assertFalse(HighlightTags.matchesFilter("clue", listOf("like")))
        assertTrue(HighlightTags.matchesFilter("", emptyList()))
        assertFalse(HighlightTags.matchesFilter("", listOf("like")))
        assertTrue(HighlightTags.matchesFilter(null, listOf("like")))
    }

    @Test fun customActionsKeepOrderAndRecoverMissingOrDuplicateEntries() {
        assertEquals(listOf("NOTE", "STYLE", "COPY"), HighlightMenuConfig.parse(null).bar)
        assertEquals(HighlightMenuConfig.default(), HighlightMenuConfig.parse("invalid"))
        val config = HighlightMenuConfig.parse("""{"bar":["COPY","NOTE","NOTE","bad"],"more":["DELETE","STYLE"]}""")
        assertEquals(listOf("COPY", "NOTE"), config.bar)
        assertEquals(listOf("DELETE", "STYLE", "CREATE_RULE"), config.more)
        assertEquals(config, HighlightMenuConfig.parse(config.toJson()))
    }

    private val frame = HighlightPopupPosition.Bounds(8, 32, 352, 760)
    private fun place(bounds: HighlightPopupPosition.Bounds, height: Int = 67) =
        HighlightPopupPosition.resolve(frame, bounds, 320, height, 8)

    @Test fun multiLineSelectionUsesEntireBoundsAndMeasuredMenuHeight() {
        val selected = HighlightPopupPosition.Bounds(20, 250, 340, 510)
        assertEquals(175, place(selected).y)
        assertEquals(105, place(selected, height = 137).y)
        assertEquals(20, place(selected).x)
    }

    @Test fun topBottomAndLongSelectionsStayWithinViewport() {
        assertEquals(106, place(HighlightPopupPosition.Bounds(10, 35, 180, 98)).y)
        assertEquals(620, place(HighlightPopupPosition.Bounds(220, 695, 350, 755)).y)
        val long = place(HighlightPopupPosition.Bounds(10, 80, 350, 740))
        assertEquals(frame.top, long.y)
        val nearTop = place(HighlightPopupPosition.Bounds(10, 40, 350, 690))
        assertEquals(693, nearTop.y)
        assertEquals(32, place(HighlightPopupPosition.Bounds(10, 40, 350, 690), 900).y)
    }
}
