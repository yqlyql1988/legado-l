package io.legado.app.ui.book.toc

/**
 * 把已经按章节顺序排好的列表折叠成连续的章节分组。
 * 返回每组第一条记录 id 对应的组内条数，列表项本身不增加额外的组头行，
 * 组头由条目布局在组首位置显示。
 */
internal fun <T> buildChapterSections(
    items: List<T>,
    chapterKey: (T) -> Any,
    itemId: (T) -> Long,
): Map<Long, Int> {
    if (items.isEmpty()) return emptyMap()
    val sections = LinkedHashMap<Long, Int>()
    var currentKey = chapterKey(items.first())
    var sectionStartId = itemId(items.first())
    var count = 0
    for (item in items) {
        val key = chapterKey(item)
        if (key != currentKey) {
            sections[sectionStartId] = count
            currentKey = key
            sectionStartId = itemId(item)
            count = 0
        }
        count++
    }
    sections[sectionStartId] = count
    return sections
}
