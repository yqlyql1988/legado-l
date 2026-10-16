package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.help.HighlightTags
import kotlinx.coroutines.flow.Flow

@Dao
abstract class HighlightTagDao {
    @Query("SELECT * FROM highlight_tags ORDER BY sortOrder, id")
    abstract fun all(): List<HighlightTag>

    @Query("SELECT * FROM highlight_tags ORDER BY sortOrder, id")
    abstract fun flowAll(): Flow<List<HighlightTag>>

    @Query("SELECT * FROM highlight_tag_orders ORDER BY bookUrl, sortOrder, tagId")
    abstract fun orders(): List<HighlightTagOrder>

    @Query("SELECT * FROM highlight_tag_orders WHERE bookUrl = :bookUrl ORDER BY sortOrder, tagId")
    abstract fun ordersForBook(bookUrl: String): List<HighlightTagOrder>

    @Query("SELECT * FROM highlight_tag_orders WHERE bookUrl = :bookUrl ORDER BY sortOrder, tagId")
    abstract fun flowOrdersForBook(bookUrl: String): Flow<List<HighlightTagOrder>>

    @Upsert
    abstract fun upsertOrders(orders: List<HighlightTagOrder>)

    @Query("DELETE FROM highlight_tag_orders WHERE bookUrl = :bookUrl")
    abstract fun clearOrder(bookUrl: String)

    @Query("DELETE FROM highlight_tag_orders")
    abstract fun clearOrders()

    @Query("""SELECT EXISTS(SELECT 1 FROM highlight_tags tag, highlights h
        WHERE tag.id = :id AND h.time = :time AND (tag.bookUrl = '' OR tag.bookUrl = h.bookUrl))""")
    abstract fun canAttach(time: Long, id: String): Boolean

    @Transaction
    open fun forBook(bookUrl: String): List<HighlightTag> =
        HighlightTags.forBook(all(), bookUrl, ordersForBook(bookUrl))

    @Transaction
    open fun saveOrder(bookUrl: String, ids: List<String>) {
        require(bookUrl.isNotBlank())
        val available = forBook(bookUrl).mapTo(hashSetOf()) { it.id }
        clearOrder(bookUrl)
        upsertOrders(ids.distinct().filter { it in available }.mapIndexed { index, id ->
            HighlightTagOrder(bookUrl, id, index)
        })
    }

    @Transaction
    open fun saveTag(tag: HighlightTag) {
        require(tag.id.isNotBlank() && tag.name.isNotBlank())
        val tags = all()
        require(tags.none {
            it.id != tag.id && it.name == tag.name &&
                (it.bookUrl == tag.bookUrl || it.bookUrl.isEmpty() || tag.bookUrl.isEmpty())
        })
        // Changing scope would invalidate existing associations; scope is chosen on creation.
        require(tags.firstOrNull { it.id == tag.id }?.bookUrl?.let { it == tag.bookUrl } != false)
        upsertTags(listOf(tag))
    }

    @Transaction
    open fun saveGlobalOrder(ids: List<String>) {
        val globals = all().filter { it.bookUrl.isEmpty() }.associateBy { it.id }
        val ordered = ids.distinct().mapNotNull(globals::get) +
            globals.values.filter { it.id !in ids }
        upsertTags(ordered.mapIndexed { index, tag -> tag.copy(sortOrder = index) })
    }

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
        if (!canAttach(time, id)) return
        if (id in idsForHighlight(time)) deleteLink(time, id)
        else upsertLinks(listOf(HighlightTagLink(time, id)))
    }

    @Transaction
    open fun saveTags(tags: List<HighlightTag>) {
        replaceDefinitions(tags.mapIndexed { index, tag -> tag.copy(sortOrder = index) })
    }

    @Transaction
    open fun replaceDefinitions(tags: List<HighlightTag>) {
        require(tags.all { it.id.isNotBlank() && it.name.isNotBlank() })
        require(tags.map { it.id }.distinct().size == tags.size)
        require(tags.map { it.bookUrl to it.name }.distinct().size == tags.size)
        upsertTags(tags)
        if (tags.isEmpty()) deleteAllTags()
        else {
            val ids = tags.mapTo(hashSetOf()) { it.id }
            deleteTags(all().filter { it.id !in ids })
        }
    }

    @Transaction
    open fun restore(
        tags: List<HighlightTag>, links: List<HighlightTagLink>, times: List<Long>,
        orders: List<HighlightTagOrder>? = null
    ) {
        // v114 backups know only global tags. Keep newer book-only data on that path.
        val preserved = if (orders == null) all().filter { it.bookUrl.isNotEmpty() && it.id !in tags.map { tag -> tag.id } }
            else emptyList()
        val preservedIds = preserved.mapTo(hashSetOf()) { it.id }
        val restoredTags = tags + preserved
        val restoredLinks = links + if (preservedIds.isEmpty()) emptyList()
            else links().filter { it.tagId in preservedIds }
        replaceDefinitions(restoredTags)
        if (orders != null) {
            clearOrders()
            val definitions = restoredTags.associateBy { it.id }
            upsertOrders(orders.filter { order ->
                order.bookUrl.isNotBlank() && definitions[order.tagId]?.let {
                    it.bookUrl.isEmpty() || it.bookUrl == order.bookUrl
                } == true
            }.distinctBy { it.bookUrl to it.tagId })
        }
        // Keep large all-book backups within Android 8's SQLite bind-parameter limit.
        val highlightTimes = hashSetOf<Long>()
        times.distinct().chunked(900).forEach { batch ->
            deleteLinksForHighlights(batch)
            highlightTimes.addAll(existingHighlightTimes(batch))
        }
        val tagIds = restoredTags.mapTo(hashSetOf()) { it.id }
        val owners = allHighlightOwners().associate { it.time to it.bookUrl }
        val definitions = restoredTags.associateBy { it.id }
        upsertLinks(restoredLinks.filter {
            it.tagId in tagIds && it.highlightTime in highlightTimes &&
                definitions[it.tagId]?.let { tag ->
                    tag.bookUrl.isEmpty() || tag.bookUrl == owners[it.highlightTime]
                } == true
        }.distinct())
    }

    data class HighlightOwner(val time: Long, val bookUrl: String)
    @Query("SELECT time, bookUrl FROM highlights")
    abstract fun allHighlightOwners(): List<HighlightOwner>
}
