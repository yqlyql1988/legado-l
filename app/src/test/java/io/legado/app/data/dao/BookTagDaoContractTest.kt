package io.legado.app.data.dao

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookTagDaoContractTest {

    @Test
    fun `exact bookshelf tag search includes source and user tags`() {
        val source = bookDaoSource().replace("\r\n", "\n")

        assertTrue(source.contains("fun flowShelfTagSearch(tag: String): Flow<List<Book>>"))
        assertTrue(source.contains(".filter { it.hasTag(tag) }"))
        assertTrue(source.contains(".chunked(SHELF_TAG_BOOK_URL_CHUNK_SIZE)"))
        assertTrue(source.contains("AND bookUrl IN (:bookUrls)"))
    }

    @Test
    fun `source replacement keeps user tags`() {
        assertTrue(bookDaoSource().contains("customTag = oldBook.customTag"))
    }

    private fun bookDaoSource(): String {
        val candidates = listOf(
            File("src/main/java/io/legado/app/data/dao/BookDao.kt"),
            File("app/src/main/java/io/legado/app/data/dao/BookDao.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("BookDao.kt not found")
    }
}
