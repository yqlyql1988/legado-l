package io.legado.app.data.entities

import androidx.room.Entity

/**
 * Schema compatibility for installations of 3.26101813.
 * The withdrawn feature has no UI, DAO or runtime writes. Keep its raw table at
 * version 119 so an in-place update neither downgrades nor rebuilds user data.
 */
@Entity(tableName = "book_reading_marks", primaryKeys = ["bookName", "bookAuthor"])
data class LegacyReadingMark(
    val bookName: String,
    val bookAuthor: String,
    val status: String,
    val date: String,
    val position: String?,
    val reason: String,
    val skipped: String,
    val updatedAt: Long,
)
