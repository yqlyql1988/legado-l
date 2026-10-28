package io.legado.app.model

import io.legado.app.ui.book.read.page.entities.TextChapter

/** A completed or partially laid out text chapter used by the reading counter. */
internal class TextReadChapterSnapshot(
    val index: Int,
    val content: String,
    val titleLength: Int,
) {
    private val effectiveWordPrefix by lazy {
        IntArray(content.length + 1).also { prefix ->
            content.forEachIndexed { index, char ->
                prefix[index + 1] = prefix[index] +
                    if (isEffectiveWordChar(char)) 1 else 0
            }
        }
    }

    fun wordsBetween(start: Int, end: Int): Long {
        val bodyStart = titleLength.coerceAtLeast(0)
        val from = start.coerceIn(bodyStart, content.length)
        val to = end.coerceIn(from, content.length)
        return (effectiveWordPrefix[to] - effectiveWordPrefix[from]).toLong()
    }

    private fun isEffectiveWordChar(char: Char): Boolean =
        !char.isWhitespace() && char !in '\u200B'..'\u200F' && char != '\uFEFF'
}

/**
 * Confirms forward movement after the reader has spent enough time at the
 * current position. Unconfirmed movement is discarded when the reader moves
 * backward, which keeps quick preview gestures out of reading statistics.
 */
internal class TextReadProgressTracker {

    companion object {
        const val wordConfirmationMillis = 1_500L
    }

    private data class Position(val chapterIndex: Int, val chapterPos: Int)

    private var previous: Position? = null
    private var previousChapter: TextReadChapterSnapshot? = null
    private var pendingWords = 0L
    private var seeked = false

    fun reset() {
        previous = null
        previousChapter = null
        pendingWords = 0L
        seeked = false
    }

    fun start(chapterIndex: Int, chapterPos: Int, chapter: TextReadChapterSnapshot?) {
        previous = Position(chapterIndex, chapterPos.coerceAtLeast(0))
        previousChapter = chapter
        pendingWords = 0L
        seeked = false
    }

    fun markSeek() {
        pendingWords = 0L
        seeked = true
    }

    fun update(
        chapterIndex: Int,
        chapterPos: Int,
        chapter: TextReadChapterSnapshot?,
        elapsed: Long = wordConfirmationMillis,
    ): Long {
        val current = Position(chapterIndex, chapterPos.coerceAtLeast(0))
        val old = previous
        val oldChapter = previousChapter
        previous = current
        previousChapter = chapter
        val ignored = seeked
        seeked = false
        if (old == null || ignored) return 0L

        when {
            current.chapterIndex == old.chapterIndex &&
                current.chapterPos == old.chapterPos -> Unit

            current.chapterIndex == old.chapterIndex &&
                current.chapterPos > old.chapterPos -> {
                pendingWords += (chapter ?: oldChapter)
                    ?.wordsBetween(old.chapterPos, current.chapterPos) ?: 0L
            }

            current.chapterIndex == old.chapterIndex + 1 &&
                oldChapter != null && chapter != null -> {
                pendingWords += oldChapter.wordsBetween(old.chapterPos, oldChapter.content.length)
                pendingWords += chapter.wordsBetween(0, current.chapterPos)
            }

            else -> {
                // Backward movement or a non-sequential jump invalidates the
                // unconfirmed range but establishes a new reading baseline.
                pendingWords = 0L
            }
        }

        if (elapsed >= wordConfirmationMillis) {
            return pendingWords.also { pendingWords = 0L }
        }
        return 0L
    }
}

/** Avoid rebuilding a whole chapter string on every page checkpoint. */
internal class TextReadChapterSnapshotCache {

    private var source: TextChapter? = null
    private var pageCount = -1
    private var completed = false
    private var titleLength = -1
    private var snapshot: TextReadChapterSnapshot? = null

    fun snapshot(chapter: TextChapter?): TextReadChapterSnapshot? {
        chapter ?: return null
        if (chapter !== source || chapter.pages.size != pageCount ||
            chapter.isCompleted != completed || chapter.layoutTitleLength != titleLength
        ) {
            source = chapter
            pageCount = chapter.pages.size
            completed = chapter.isCompleted
            titleLength = chapter.layoutTitleLength
            snapshot = TextReadChapterSnapshot(
                chapter.chapter.index,
                chapter.getContent(),
                chapter.layoutTitleLength,
            )
        }
        return snapshot
    }
}
