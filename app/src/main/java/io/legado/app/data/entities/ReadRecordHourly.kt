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
)

data class ReadRecordHourlyShow(
    val bookName: String,
    val author: String,
    val date: String,
    val hour: Int,
    val readTime: Long,
)
