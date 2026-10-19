package io.legado.app.help

import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookmarkTagOrder
import io.legado.app.data.entities.HighlightTag
import io.legado.app.utils.GSON
import com.google.gson.JsonArray

object BookmarkTags {
    fun bookUrl(bookmark: Bookmark): String =
        appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)?.bookUrl.orEmpty()

    fun attached(bookmarks: List<Bookmark>): Map<Long, List<HighlightTag>> {
        val definitions = appDb.highlightTagDao.all().associateBy { it.id }
        val links = appDb.bookmarkTagDao.links().groupBy { it.bookmarkTime }
        return bookmarks.associate { bookmark ->
            bookmark.time to links[bookmark.time].orEmpty().mapNotNull { definitions[it.tagId] }
                .sortedWith(compareBy<HighlightTag> { it.bookmarkSortOrder }.thenBy { it.id })
        }
    }

    /** Keep the original JSON array and bookmark fields; old readers ignore the new tags field. */
    fun exportJson(bookmarks: List<Bookmark>): String {
        val attached = attached(bookmarks)
        return GSON.toJson(JsonArray().apply {
            bookmarks.forEach { bookmark ->
                add(GSON.toJsonTree(bookmark).asJsonObject.apply {
                    add("tags", GSON.toJsonTree(attached[bookmark.time].orEmpty()))
                })
            }
        })
    }

    /** The manager edits one scene. Preserve the other scene's current preferences. */
    fun saveDefinitions(draft: List<HighlightTag>, currentBook: String) {
        val previous = appDb.highlightTagDao.all().associateBy { it.id }
        draft.filter { !it.common && previous[it.id]?.bookmarkCommon == true }.forEach { tag ->
            val owners = appDb.bookmarkTagDao.owners(tag.id).mapNotNull {
                appDb.bookDao.getBook(it.bookName, it.bookAuthor)?.bookUrl
            }.toMutableSet()
            if (currentBook.isNotBlank()) owners.add(currentBook)
            owners.forEach { url ->
                val retained = appDb.bookmarkTagDao.forBook(url, includeHidden = true)
                val oldOrders = appDb.bookmarkTagDao.ordersForBook(url).associateBy { it.tagId }
                appDb.bookmarkTagDao.saveBookLabels(url, retained.mapIndexed { index, label ->
                    BookmarkTagOrder(url, label.id, index, oldOrders[label.id]?.hidden == true)
                })
            }
        }
        appDb.highlightTagDao.replaceDefinitions(draft.map { tag ->
            val old = previous[tag.id]
            tag.copy(common = old?.common ?: false, sortOrder = old?.sortOrder ?: 0,
                bookmarkCommon = tag.common, bookmarkSortOrder = tag.sortOrder)
        })
    }
}
