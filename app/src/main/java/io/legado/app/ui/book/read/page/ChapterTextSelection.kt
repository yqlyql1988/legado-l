package io.legado.app.ui.book.read.page

import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn

internal class ChapterTextSelection(
    val chapter: TextChapter,
    val range: SelectionRange,
) {
    val paintedPages: MutableSet<TextPage> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    fun clearPaint(keep: List<TextPage> = emptyList()) {
        val iterator = paintedPages.iterator()
        while (iterator.hasNext()) {
            val page = iterator.next()
            if (keep.any { it === page }) continue
            page.lines.forEach { line ->
                line.columns.forEach { if (it is TextBaseColumn) it.selected = false }
            }
            iterator.remove()
        }
    }

    fun selectedText(): String = buildString {
        chapter.pages.forEach { page ->
            page.lines.forEach { line ->
                var position = line.chapterPosition
                val lastText = line.columns.indexOfLast { it is TextBaseColumn }
                line.columns.forEachIndexed { index, column ->
                    val end = position + column.positionLength
                    if (column is TextBaseColumn && end > position &&
                        range.contains(SelectionSpan(position, end))
                    ) {
                        append(column.charData)
                        if (line.isParagraphEnd && index == lastText && end < range.end) append('\n')
                    }
                    position = end
                }
            }
        }
    }

    fun endpoint(end: Boolean): Pair<TextPage, TextPos>? {
        val position = if (end) range.end - 1 else range.start
        val page = chapter.getPageByReadPos(position) ?: return null
        page.lines.forEachIndexed { lineIndex, line ->
            var offset = line.chapterPosition
            line.columns.forEachIndexed { columnIndex, column ->
                val next = offset + column.positionLength
                if (position in offset until next && column is TextBaseColumn) {
                    return page to TextPos(0, lineIndex, columnIndex)
                }
                offset = next
            }
        }
        return null
    }

    companion object {
        fun span(line: TextLine, columnIndex: Int): SelectionSpan? {
            val column = line.columns.getOrNull(columnIndex) as? TextBaseColumn ?: return null
            if (column.positionLength <= 0) return null
            val start = line.chapterPosition +
                line.columns.take(columnIndex).sumOf { it.positionLength }
            return SelectionSpan(start, start + column.positionLength)
        }
    }
}
