package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.UUID

@Entity(tableName = "highlight_tags")
data class HighlightTag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val style: String = "",
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "''") val bookUrl: String = ""
) {
    fun styleObj(): HighlightStyle =
        (GSON.fromJsonObject<HighlightStyle>(style).getOrNull() ?: HighlightStyle(
            underline = HighlightStyle.Underline()
        )).normalized()
}

@Entity(
    tableName = "highlight_tag_links",
    primaryKeys = ["highlightTime", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(
            entity = BookHighlight::class, parentColumns = ["time"],
            childColumns = ["highlightTime"], onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = HighlightTag::class, parentColumns = ["id"],
            childColumns = ["tagId"], onDelete = ForeignKey.CASCADE
        )
    ]
)
data class HighlightTagLink(val highlightTime: Long, val tagId: String)

/** An optional combined order for one book; definitions and associations keep stable IDs. */
@Entity(
    tableName = "highlight_tag_orders",
    primaryKeys = ["bookUrl", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [ForeignKey(
        entity = HighlightTag::class, parentColumns = ["id"], childColumns = ["tagId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class HighlightTagOrder(val bookUrl: String, val tagId: String, val sortOrder: Int)
