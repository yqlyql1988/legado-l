package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Repeated reader visits update the same device/book/day/hour counter. */
@Entity(
    tableName = "readRecordHourly",
    primaryKeys = ["deviceId", "bookName", "author", "date", "hour"],
    indices = [Index(value = ["date"])],
)
data class ReadRecordHourly(
    val deviceId: String = "",
    val bookName: String = "",
    val author: String = "",
    val date: String = "",
    val hour: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val readTime: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val deletedTime: Long = 0L,
    /** Matches the owning day's last reset; pre-reset hour details cannot reappear. */
    @ColumnInfo(defaultValue = "0")
    val resetTime: Long = 0L,
    /** Effective text characters counted for this device, book, day and hour. */
    @ColumnInfo(defaultValue = "0")
    val readWords: Long = 0L,
    /** Removed portion of the cumulative word counter. */
    @ColumnInfo(defaultValue = "0")
    val deletedWords: Long = 0L,
)

data class ReadRecordHourlyShow(
    val bookName: String,
    val author: String,
    val date: String,
    val hour: Int,
    val readTime: Long,
    val readWords: Long = 0L,
)
