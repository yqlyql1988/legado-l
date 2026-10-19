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
        tags: List<HighlightTag>, bookUrl: String, orders: List<HighlightTagOrder> = emptyList(),
        includeHidden: Boolean = false, includeOwned: Boolean = true
    ): List<HighlightTag> {
        val entries = orders.filter { it.bookUrl == bookUrl }.associateBy { it.tagId }
        val positions = entries.mapValues { it.value.sortOrder }
        return tags.filter { it.common || includeOwned && it.bookUrl == bookUrl || it.id in positions }
            .filter { includeHidden || entries[it.id]?.hidden != true }
            .sortedWith(compareBy<HighlightTag> { positions[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.bookUrl.isNotEmpty() }.thenBy { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, tag -> tag.copy(sortOrder = index) }
    }

    /** Same-name choices prefer a selection, an explicit book choice, then the book's own label. */
    fun choices(
        tags: List<HighlightTag>, bookUrl: String, preferredIds: Collection<String> = emptyList()
    ): List<HighlightTag> {
        val preferred = preferredIds.distinct().withIndex().associate { it.value to it.index }
        val chosen = tags.groupBy { it.name }.values.map { sameName ->
            sameName.minWith(compareBy<HighlightTag> { preferred[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.bookUrl != bookUrl }.thenBy { it.sortOrder }.thenBy { it.id })
        }.mapTo(hashSetOf()) { it.id }
        return tags.filter { it.id in chosen }
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
        val hasCommon = db.query("SELECT * FROM highlight_tags LIMIT 0").use {
            it.getColumnIndex("common") >= 0
        }
        defaults().forEach { tag ->
            db.insert("highlight_tags", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
                put("id", tag.id)
                put("name", tag.name)
                put("style", tag.style)
                put("sortOrder", tag.sortOrder)
                // Migrations before v120 seed the original table without this column.
                if (hasCommon) put("common", if (tag.common) 1 else 0)
            })
        }
    }
}
