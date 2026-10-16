package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import kotlinx.coroutines.flow.Flow

@Dao
abstract class HighlightTagDao {
    @Query("SELECT * FROM highlight_tags ORDER BY sortOrder, id")
    abstract fun all(): List<HighlightTag>

    @Query("SELECT * FROM highlight_tags ORDER BY sortOrder, id")
    abstract fun flowAll(): Flow<List<HighlightTag>>

    @Query("SELECT * FROM highlight_tag_links ORDER BY highlightTime, tagId")
    abstract fun links(): List<HighlightTagLink>

    @Query("""SELECT link.* FROM highlight_tag_links link
        INNER JOIN highlights ON highlights.time = link.highlightTime WHERE highlights.bookUrl = :bookUrl""")
    abstract fun linksForBook(bookUrl: String): List<HighlightTagLink>

    @Query("""SELECT link.* FROM highlight_tag_links link
        INNER JOIN highlights ON highlights.time = link.highlightTime WHERE highlights.bookUrl = :bookUrl""")
    abstract fun flowLinksForBook(bookUrl: String): Flow<List<HighlightTagLink>>

    @Query("SELECT tagId FROM highlight_tag_links WHERE highlightTime = :time")
    abstract fun idsForHighlight(time: Long): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM highlights WHERE time = :time)")
    abstract fun highlightExists(time: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM highlight_tags WHERE id = :id)")
    abstract fun tagExists(id: String): Boolean

    @Upsert
    abstract fun upsertTags(tags: List<HighlightTag>)

    @Upsert
    abstract fun upsertLinks(links: List<HighlightTagLink>)

    @Delete
    abstract fun deleteTags(tags: List<HighlightTag>)

    @Query("DELETE FROM highlight_tags")
    abstract fun deleteAllTags()

    @Query("DELETE FROM highlight_tag_links WHERE highlightTime IN (:times)")
    abstract fun deleteLinksForHighlights(times: List<Long>)

    @Query("SELECT time FROM highlights WHERE time IN (:times)")
    abstract fun existingHighlightTimes(times: List<Long>): List<Long>

    @Query("DELETE FROM highlight_tag_links WHERE highlightTime = :time AND tagId = :id")
    abstract fun deleteLink(time: Long, id: String)

    @Transaction
    open fun toggle(time: Long, id: String) {
        // A popup may outlive a tag deletion or deletion of its annotation.
        if (!highlightExists(time) || !tagExists(id)) return
        if (id in idsForHighlight(time)) deleteLink(time, id)
        else upsertLinks(listOf(HighlightTagLink(time, id)))
    }

    @Transaction
    open fun saveTags(tags: List<HighlightTag>) {
        require(tags.all { it.id.isNotBlank() && it.name.isNotBlank() })
        require(tags.map { it.id }.distinct().size == tags.size)
        require(tags.map { it.name }.distinct().size == tags.size)
        upsertTags(tags.mapIndexed { index, tag -> tag.copy(sortOrder = index) })
        if (tags.isEmpty()) deleteAllTags()
        else {
            val ids = tags.mapTo(hashSetOf()) { it.id }
            deleteTags(all().filter { it.id !in ids })
        }
    }

    @Transaction
    open fun restore(tags: List<HighlightTag>, links: List<HighlightTagLink>, times: List<Long>) {
        saveTags(tags)
        // Keep large all-book backups within Android 8's SQLite bind-parameter limit.
        val highlightTimes = hashSetOf<Long>()
        times.distinct().chunked(900).forEach { batch ->
            deleteLinksForHighlights(batch)
            highlightTimes.addAll(existingHighlightTimes(batch))
        }
        val tagIds = tags.mapTo(hashSetOf()) { it.id }
        upsertLinks(links.filter {
            it.tagId in tagIds && it.highlightTime in highlightTimes
        }.distinct())
    }
}
