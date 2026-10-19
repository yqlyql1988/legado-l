package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "bookmark_tag_links",
    primaryKeys = ["bookmarkTime", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = Bookmark::class, parentColumns = ["time"],
            childColumns = ["bookmarkTime"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = HighlightTag::class, parentColumns = ["id"],
            childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class BookmarkTagLink(val bookmarkTime: Long, val tagId: String)

@Entity(
    tableName = "bookmark_tag_orders",
    primaryKeys = ["bookUrl", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [ForeignKey(entity = HighlightTag::class, parentColumns = ["id"],
        childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)]
)
data class BookmarkTagOrder(
    val bookUrl: String, val tagId: String, val sortOrder: Int,
    @androidx.room.ColumnInfo(defaultValue = "0") val hidden: Boolean = false
) {
    fun asHighlightOrder() = HighlightTagOrder(bookUrl, tagId, sortOrder, hidden)
}
