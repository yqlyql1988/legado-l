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

    @Query("SELECT * FROM highlight_tag_orders ORDER BY bookUrl, sortOrder, tagId")
    abstract fun flowOrders(): Flow<List<HighlightTagOrder>>

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
        WHERE tag.id = :id AND h.time = :time AND (tag.common = 1 OR tag.bookUrl = h.bookUrl
        OR EXISTS(SELECT 1 FROM highlight_tag_orders o WHERE o.tagId = tag.id AND o.bookUrl = h.bookUrl))
        AND NOT EXISTS(SELECT 1 FROM highlight_tag_orders o
            WHERE o.tagId = tag.id AND o.bookUrl = h.bookUrl AND o.hidden = 1))""")
    abstract fun canAttach(time: Long, id: String): Boolean

    @Transaction
    open fun forBook(bookUrl: String, includeHidden: Boolean = false): List<HighlightTag> =
        HighlightTags.forBook(all(), bookUrl, ordersForBook(bookUrl), includeHidden)

    @Transaction
    open fun saveOrder(bookUrl: String, ids: List<String>) {
        require(bookUrl.isNotBlank())
        val available = forBook(bookUrl).mapTo(hashSetOf()) { it.id }
        val hidden = ordersForBook(bookUrl).filter { it.hidden }
        clearOrder(bookUrl)
        upsertOrders(ids.distinct().filter { it in available }.mapIndexed { index, id ->
            HighlightTagOrder(bookUrl, id, index)
        } + hidden)
    }

    @Transaction
    open fun saveBookLabels(bookUrl: String, entries: List<HighlightTagOrder>) {
        require(bookUrl.isNotBlank() && entries.all { it.bookUrl == bookUrl })
        val ids = all().mapTo(hashSetOf()) { it.id }
        clearOrder(bookUrl)
        upsertOrders(entries.filter { it.tagId in ids }.distinctBy { it.tagId })
    }

    @Transaction
    open fun saveTag(tag: HighlightTag) {
        require(tag.id.isNotBlank() && tag.name.isNotBlank())
        val tags = all()
        require(tags.none {
            it.id != tag.id && it.name == tag.name &&
                it.bookUrl == tag.bookUrl
        })
        saveDefinitions(tags.filter { it.id != tag.id } + tag)
    }

    @Transaction
    open fun saveGlobalOrder(ids: List<String>) {
        val globals = all().filter { it.common }.associateBy { it.id }
        val ordered = ids.distinct().mapNotNull(globals::get) +
            globals.values.filter { it.id !in ids }
        upsertTags(ordered.mapIndexed { index, tag -> tag.copy(sortOrder = index) })
    }

    @Query("SELECT * FROM highlight_tag_links ORDER BY highlightTime, tagId")
    abstract fun links(): List<HighlightTagLink>

    @Query("SELECT * FROM highlight_tag_links ORDER BY highlightTime, tagId")
    abstract fun flowLinks(): Flow<List<HighlightTagLink>>

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
        if (id in idsForHighlight(time)) deleteLink(time, id)
        else if (canAttach(time, id)) upsertLinks(listOf(HighlightTagLink(time, id)))
    }

    @Transaction
    open fun saveTags(tags: List<HighlightTag>) {
        replaceDefinitions(tags.mapIndexed { index, tag -> tag.copy(sortOrder = index) })
    }

    /** Unstarring changes availability; stable definitions and existing associations stay intact. */
    @Transaction
    open fun saveDefinitions(tags: List<HighlightTag>, currentBook: String = "") {
        val previous = all().associateBy { it.id }
        val unstarred = tags.filter { !it.common && previous[it.id]?.common == true }
            .mapTo(hashSetOf()) { it.id }
        if (unstarred.isEmpty()) {
            replaceDefinitions(tags)
            return
        }
        val owners = allHighlightOwners().associate { it.time to it.bookUrl }
        val oldOrders = orders()
        if (unstarred.isNotEmpty()) {
            val books = links().filter { it.tagId in unstarred }
                .mapNotNullTo(hashSetOf()) { owners[it.highlightTime] }
            oldOrders.filter { it.tagId in unstarred }.mapTo(books) { it.bookUrl }
            if (currentBook.isNotBlank()) books.add(currentBook)
            val before = tags.map { if (it.id in unstarred) it.copy(common = true) else it }
            books.filter { it.isNotBlank() }.forEach { owner ->
                val retained = HighlightTags.forBook(before, owner, oldOrders, includeHidden = true)
                val hidden = oldOrders.filter { it.bookUrl == owner && it.hidden }.mapTo(hashSetOf()) { it.tagId }
                clearOrder(owner)
                upsertOrders(retained.mapIndexed { index, tag -> HighlightTagOrder(owner, tag.id, index, tag.id in hidden) })
            }
        }
        replaceDefinitions(tags)
    }

    /** An explicit same-name choice changes only this book's associations. */
    @Transaction
    open fun useLibraryLabel(bookUrl: String, from: String, to: String) {
        require(bookUrl.isNotBlank() && from != to)
        val definitions = all().associateBy { it.id }
        // The manager confirms the same-name switch when chosen. Either label may
        // subsequently be renamed in that draft before its changes are saved.
        require(from in definitions && to in definitions)
        val affected = linksForBook(bookUrl).filter { it.tagId == from }
        upsertLinks(affected.map { it.copy(tagId = to) })
        affected.forEach { deleteLink(it.highlightTime, from) }
    }

    data class Usage(val books: Int, val highlights: Int)
    @Query("""SELECT count(DISTINCT h.bookUrl) AS books, count(*) AS highlights
        FROM highlight_tag_links l INNER JOIN highlights h ON h.time = l.highlightTime WHERE l.tagId = :id""")
    abstract fun usage(id: String): Usage

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
        val restoredTags = tags.map { it.copy(note = it.note.orEmpty()) } + preserved
        val restoredLinks = links + if (preservedIds.isEmpty()) emptyList()
            else links().filter { it.tagId in preservedIds }
        replaceDefinitions(restoredTags)
        if (orders != null) {
            clearOrders()
            val definitions = restoredTags.associateBy { it.id }
            upsertOrders(orders.filter { order ->
                order.bookUrl.isNotBlank() && order.tagId in definitions
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
        val retained = orders().mapTo(hashSetOf()) { it.bookUrl to it.tagId }
        upsertLinks(restoredLinks.filter {
            it.tagId in tagIds && it.highlightTime in highlightTimes &&
                definitions[it.tagId]?.let { tag ->
                    tag.common || tag.bookUrl == owners[it.highlightTime] ||
                        owners[it.highlightTime] to tag.id in retained
                } == true
        }.distinct())
    }

    data class HighlightOwner(val time: Long, val bookUrl: String)
    @Query("SELECT time, bookUrl FROM highlights")
    abstract fun allHighlightOwners(): List<HighlightOwner>
}
