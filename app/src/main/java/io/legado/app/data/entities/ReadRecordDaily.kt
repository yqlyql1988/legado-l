package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** A device owns a cumulative counter for each book and local calendar day. */
@Entity(
    tableName = "readRecordDaily",
    primaryKeys = ["deviceId", "bookName", "author", "date"],
    indices = [Index(value = ["date"])],
)
data class ReadRecordDaily(
    val deviceId: String = "",
    val bookName: String = "",
    val author: String = "",
    val date: String = "",
    @ColumnInfo(defaultValue = "0")
    val readTime: Long = 0L,
)

@Entity(tableName = "readStatisticsState", primaryKeys = ["deviceId"])
data class ReadStatisticsState(
    val deviceId: String = "",
    val startedAt: Long = 0L,
)

/** Known historical time; absent when an old backup's dated coverage is unknown. */
@Entity(tableName = "readRecordHistory", primaryKeys = ["deviceId", "bookName", "author"])
data class ReadRecordHistory(
    val deviceId: String = "",
    val bookName: String = "",
    val author: String = "",
    val readTime: Long = 0L,
)

data class ReadRecordDailyShow(
    val bookName: String,
    val author: String,
    val date: String,
    val readTime: Long,
)
