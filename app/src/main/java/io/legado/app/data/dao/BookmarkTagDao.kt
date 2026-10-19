package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.legado.app.data.entities.BookmarkTagLink
import io.legado.app.data.entities.BookmarkTagOrder
import io.legado.app.data.entities.HighlightTag
import io.legado.app.help.HighlightTags
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BookmarkTagDao {
    @Query("SELECT * FROM bookmark_tag_links ORDER BY bookmarkTime, tagId")
    abstract fun links(): List<BookmarkTagLink>
    @Query("SELECT * FROM bookmark_tag_links ORDER BY bookmarkTime, tagId")
    abstract fun flowLinks(): Flow<List<BookmarkTagLink>>
    @Query("SELECT * FROM bookmark_tag_orders ORDER BY bookUrl, sortOrder, tagId")
    abstract fun orders(): List<BookmarkTagOrder>
    @Query("SELECT * FROM bookmark_tag_orders ORDER BY bookUrl, sortOrder, tagId")
    abstract fun flowOrders(): Flow<List<BookmarkTagOrder>>
    @Query("SELECT * FROM bookmark_tag_orders WHERE bookUrl = :bookUrl ORDER BY sortOrder, tagId")
    abstract fun ordersForBook(bookUrl: String): List<BookmarkTagOrder>
    @Query("SELECT * FROM highlight_tags ORDER BY bookmarkSortOrder, id")
    abstract fun definitions(): List<HighlightTag>
    @Query("SELECT tagId FROM bookmark_tag_links WHERE bookmarkTime = :time")
    abstract fun idsForBookmark(time: Long): List<String>
    @Query("DELETE FROM bookmark_tag_links WHERE bookmarkTime = :time")
    abstract fun clearLinks(time: Long)
    @Query("DELETE FROM bookmark_tag_orders")
    abstract fun clearOrders()
    @Query("DELETE FROM bookmark_tag_orders WHERE bookUrl = :bookUrl")
    abstract fun clearOrder(bookUrl: String)
    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE time = :time)")
    abstract fun exists(time: Long): Boolean
    @Query("SELECT count(*) FROM bookmark_tag_links WHERE tagId = :id")
    abstract fun usage(id: String): Int
    @Upsert
    abstract fun upsertLinks(links: List<BookmarkTagLink>)
    @Upsert
    abstract fun upsertOrders(orders: List<BookmarkTagOrder>)

    @Query("""SELECT l.* FROM bookmark_tag_links l INNER JOIN bookmarks b ON b.time = l.bookmarkTime
        INNER JOIN books ON books.name = b.bookName AND books.author = b.bookAuthor
        WHERE books.bookUrl = :bookUrl AND l.tagId = :id""")
    abstract fun linksForBookLabel(bookUrl: String, id: String): List<BookmarkTagLink>
    @Query("DELETE FROM bookmark_tag_links WHERE bookmarkTime = :time AND tagId = :id")
    abstract fun deleteLink(time: Long, id: String)

    @Transaction
    open fun useLibraryLabel(bookUrl: String, from: String, to: String) {
        val definitions = definitions().mapTo(hashSetOf()) { it.id }
        require(from != to && from in definitions && to in definitions)
        val affected = linksForBookLabel(bookUrl, from)
        upsertLinks(affected.map { it.copy(tagId = to) })
        affected.forEach { deleteLink(it.bookmarkTime, from) }
    }

    @Transaction
    open fun forBook(bookUrl: String, includeHidden: Boolean = false): List<HighlightTag> =
        HighlightTags.forBook(definitions().map { it.forBookmark() }, bookUrl,
            ordersForBook(bookUrl).map { it.asHighlightOrder() }, includeHidden, includeOwned = false)

    /** Keep already attached labels even when they are no longer common or visible. */
    @Transaction
    open fun saveLinks(time: Long, bookUrl: String, ids: Collection<String>) {
        if (!exists(time)) return
        val valid = definitions().mapTo(hashSetOf()) { it.id }
        clearLinks(time)
        upsertLinks(ids.distinct().filter { it in valid }
            .map { BookmarkTagLink(time, it) })
    }

    @Transaction
    open fun saveBookLabels(bookUrl: String, entries: List<BookmarkTagOrder>) {
        require(bookUrl.isNotBlank() && entries.all { it.bookUrl == bookUrl })
        val ids = definitions().mapTo(hashSetOf()) { it.id }
        clearOrder(bookUrl)
        upsertOrders(entries.filter { it.tagId in ids }.distinctBy { it.tagId })
    }

    @Query("""SELECT DISTINCT b.bookName, b.bookAuthor FROM bookmarks b
        INNER JOIN bookmark_tag_links l ON b.time = l.bookmarkTime WHERE l.tagId = :id""")
    abstract fun owners(id: String): List<Owner>
    data class Owner(val bookName: String, val bookAuthor: String)

    @Transaction
    open fun restore(links: List<BookmarkTagLink>, times: List<Long>,
                     orders: List<BookmarkTagOrder>?) {
        val definitions = definitions().mapTo(hashSetOf()) { it.id }
        val restoredTimes = times.toHashSet()
        times.distinct().forEach { clearLinks(it) }
        upsertLinks(links.filter {
            it.bookmarkTime in restoredTimes && it.tagId in definitions && exists(it.bookmarkTime)
        }.distinct())
        if (orders != null) {
            clearOrders()
            upsertOrders(orders.filter { it.bookUrl.isNotBlank() && it.tagId in definitions }
                .distinctBy { it.bookUrl to it.tagId })
        }
    }
}
