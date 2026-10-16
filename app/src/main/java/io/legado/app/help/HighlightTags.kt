package io.legado.app.help

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.utils.GSON

object HighlightTags {
    fun forBook(
        tags: List<HighlightTag>, bookUrl: String, orders: List<HighlightTagOrder> = emptyList()
    ): List<HighlightTag> {
        val positions = orders.filter { it.bookUrl == bookUrl }.associate { it.tagId to it.sortOrder }
        return tags.filter { it.bookUrl.isEmpty() || it.bookUrl == bookUrl }
            .sortedWith(compareBy<HighlightTag> { positions[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.bookUrl.isNotEmpty() }.thenBy { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, tag -> tag.copy(sortOrder = index) }
    }

    /** Filtering includes secondary labels, and the empty sentinel matches older annotations. */
    fun matchesFilter(selected: String?, ids: Collection<String>): Boolean =
        selected == null || if (selected.isEmpty()) ids.isEmpty() else selected in ids

    fun primary(tags: List<HighlightTag>, selectedIds: Collection<String>): HighlightTag? =
        tags.asSequence().filter { it.id in selectedIds }
            .minWithOrNull(compareBy(HighlightTag::sortOrder, HighlightTag::id))

    // A tag replaces the displayed style only. BookHighlight.style remains the fallback.
    fun effectiveStyle(
        highlight: BookHighlight,
        tags: List<HighlightTag>,
        selectedIds: Collection<String>
    ): HighlightStyle = primary(tags, selectedIds)?.styleObj() ?: highlight.styleObj()

    fun defaults(): List<HighlightTag> = listOf(
        Triple("reading-l-like", "赞", HighlightStyle.Underline(color = 0xFF43864C.toInt(), width = 2f)),
        Triple("reading-l-dislike", "雷", HighlightStyle.Underline(HighlightStyle.Kind.WAVY, 0xFFB84F54.toInt(), 2f)),
        Triple("reading-l-clue", "伏笔", HighlightStyle.Underline(HighlightStyle.Kind.DASHED, 0xFFB07A25.toInt(), 2f)),
        Triple("reading-l-appearance", "外貌", HighlightStyle.Underline(HighlightStyle.Kind.DOUBLE, 0xFF4281B5.toInt(), 2f))
    ).mapIndexed { index, (id, name, underline) ->
        HighlightTag(id, name, GSON.toJson(HighlightStyle(underline = underline)), index)
    }

    fun seedDefaults(db: SupportSQLiteDatabase) {
        defaults().forEach { tag ->
            db.insert("highlight_tags", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
                put("id", tag.id)
                put("name", tag.name)
                put("style", tag.style)
                put("sortOrder", tag.sortOrder)
            })
        }
    }
}
