package io.legado.app.help

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightTag
import io.legado.app.utils.GSON

object HighlightTags {
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
