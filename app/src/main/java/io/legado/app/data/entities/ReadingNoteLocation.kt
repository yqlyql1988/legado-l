package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/** Optional location history. The original bookmark/highlight backup records stay unchanged. */
@Entity(
    tableName = "reading_note_locations",
    primaryKeys = ["kind", "noteTime", "bookUrl"],
    indices = [Index(value = ["bookUrl"])]
)
data class ReadingNoteLocation(
    val kind: String = HIGHLIGHT,
    val noteTime: Long = 0,
    val bookUrl: String = "",
    val chapterUrl: String = "",
    val chapterIndex: Int = -1,
    val chapterName: String = "",
    val chapterPos: Int = 0,
    val chapterPosEnd: Int = 0,
    val layoutTitleLength: Int = -1,
    val quote: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val verified: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val BOOKMARK = "bookmark"
        const val HIGHLIGHT = "highlight"
    }
}

/** Only an explicit source change establishes that two source URLs describe the same work. */
@Entity(tableName = "reading_note_sources")
data class ReadingNoteSource(
    @androidx.room.PrimaryKey val bookUrl: String = "",
    val workId: String = "",
    val activeBookUrl: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
