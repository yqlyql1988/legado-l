package io.legado.app.help.book

/**
 * 自然阅读进度使用的压缩编码。
 *
 * 已自然翻完的章节保存为连续区间，例如 `0-12,15-20`；正在阅读的章节保存章节序号、
 * 章内千分比和正文字符位置，例如 `13:630:12345`。两者都复用书籍已有的 `readConfig`，
 * 不新增数据库表、字段或版本，也不为整本书预建记录。
 */
internal object NaturalProgressRanges {

    fun parse(encoded: String): MutableList<IntRange> {
        if (encoded.isBlank()) return mutableListOf()
        val ranges = encoded.split(',').mapNotNullTo(mutableListOf()) { token ->
            val separator = token.indexOf('-')
            if (separator <= 0 || separator == token.lastIndex) return@mapNotNullTo null
            val start = token.substring(0, separator).toIntOrNull() ?: return@mapNotNullTo null
            val end = token.substring(separator + 1).toIntOrNull() ?: return@mapNotNullTo null
            if (start < 0 || end < start) null else start..end
        }
        if (ranges.size > 1) ranges.sortBy { it.first }
        return ranges
    }

    fun encode(ranges: List<IntRange>): String = ranges.joinToString(",") {
        "${it.first}-${it.last}"
    }

    fun contains(ranges: List<IntRange>, chapterIndex: Int): Boolean {
        return ranges.any { chapterIndex in it }
    }

    fun add(ranges: MutableList<IntRange>, chapterIndex: Int) {
        if (chapterIndex < 0) return
        val all = ArrayList<IntRange>(ranges.size + 1).apply {
            addAll(ranges)
            add(chapterIndex..chapterIndex)
        }
        all.sortBy { it.first }
        ranges.clear()
        for (range in all) {
            if (ranges.isEmpty() || ranges.last().last < range.first - 1) {
                ranges += range
            } else {
                val previous = ranges.removeAt(ranges.lastIndex)
                ranges += previous.first..maxOf(previous.last, range.last)
            }
        }
    }

    fun remove(ranges: MutableList<IntRange>, chapterIndex: Int) {
        if (chapterIndex < 0 || ranges.isEmpty()) return
        val retained = ArrayList<IntRange>(ranges.size + 1)
        for (range in ranges) {
            when {
                chapterIndex < range.first || chapterIndex > range.last -> retained += range
                range.first == range.last -> Unit
                chapterIndex == range.first -> retained += (range.first + 1)..range.last
                chapterIndex == range.last -> retained += range.first..(range.last - 1)
                else -> {
                    retained += range.first..(chapterIndex - 1)
                    retained += (chapterIndex + 1)..range.last
                }
            }
        }
        ranges.clear()
        ranges += retained
    }
}

/** 尚未读完的章节：章内千分比和自然阅读位置。 */
internal data class NaturalChapterReading(
    val perMille: Int,
    val position: Int,
)

internal object NaturalProgressChapters {

    fun parse(encoded: String): MutableMap<Int, NaturalChapterReading> {
        if (encoded.isBlank()) return mutableMapOf()
        val chapters = HashMap<Int, NaturalChapterReading>()
        encoded.split(',').forEach { token ->
            val parts = token.split(':')
            if (parts.size != 3) return@forEach
            val index = parts[0].toIntOrNull() ?: return@forEach
            val perMille = parts[1].toIntOrNull() ?: return@forEach
            val position = parts[2].toIntOrNull() ?: return@forEach
            if (index < 0 || perMille !in 1..999 || position < 0) return@forEach
            chapters[index] = NaturalChapterReading(perMille, position)
        }
        return chapters
    }

    fun encode(chapters: Map<Int, NaturalChapterReading>): String {
        if (chapters.isEmpty()) return ""
        return chapters.toSortedMap().entries.joinToString(",") {
            "${it.key}:${it.value.perMille}:${it.value.position}"
        }
    }
}

/** 目录显示用的只读快照，由书籍配置解析一次后复用。 */
internal class NaturalProgressSnapshot(
    private val completedRanges: List<IntRange>,
    private val readingChapters: Map<Int, NaturalChapterReading>,
) {

    fun chapterProgress(chapterIndex: Int): Float {
        if (NaturalProgressRanges.contains(completedRanges, chapterIndex)) return 1f
        return readingChapters[chapterIndex]?.perMille?.div(1000f) ?: 0f
    }

    fun chapterPosition(chapterIndex: Int): Int? =
        readingChapters[chapterIndex]?.position
}
