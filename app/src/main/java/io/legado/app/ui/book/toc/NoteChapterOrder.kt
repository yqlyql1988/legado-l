package io.legado.app.ui.book.toc

import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.help.ReadingNoteChapter

/**
 * 按当前目录解析笔记所属章节序号。目录更新、换源或章节改名后原记录可能匹配不到，
 * 此时回退到保存的章节序号，保证列表仍按章排列，而不是被统一排到末尾。
 */
internal fun resolvedChapterIndex(
    index: ReadingNoteChapter.ChapterIndex?,
    location: ReadingNoteLocation,
    fallback: Int,
): Int = index?.resolve(location)?.first?.index ?: fallback
