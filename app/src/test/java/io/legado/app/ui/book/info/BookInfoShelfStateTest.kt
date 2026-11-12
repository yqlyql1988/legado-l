package io.legado.app.ui.book.info

import io.legado.app.data.entities.Book
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookInfoShelfStateTest {

    @Test
    fun `same book is recognized by url after metadata refresh`() {
        val readerBook = Book(bookUrl = "url-a", name = "旧书名", author = "旧作者")
        val book = Book(bookUrl = "url-a", name = "新书名", author = "新作者")

        assertTrue(isSameReaderBook(readerBook, book))
    }

    @Test
    fun `same book is recognized by name and author after source change`() {
        val readerBook = Book(bookUrl = "url-a", name = "书名", author = "作者")
        val book = Book(bookUrl = "url-b", name = "书名", author = "作者")

        assertTrue(isSameReaderBook(readerBook, book))
    }

    @Test
    fun `different books are not treated as the reader book`() {
        val readerBook = Book(bookUrl = "url-a", name = "书名", author = "作者")
        val otherBook = Book(bookUrl = "url-b", name = "另一本", author = "作者")
        val sameNameDifferentAuthor = Book(bookUrl = "url-c", name = "书名", author = "")

        assertFalse(isSameReaderBook(readerBook, otherBook))
        assertFalse(isSameReaderBook(readerBook, sameNameDifferentAuthor))
    }

    @Test
    fun `empty reader state never matches`() {
        assertFalse(isSameReaderBook(null, Book(bookUrl = "url-a", name = "书名")))
        assertFalse(isSameReaderBook(Book(), Book()))
    }

    @Test
    fun `adding to bookshelf marks the active reader sessions`() {
        val viewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
        )
        val addToBookshelf = viewModel
            .substringAfter("fun addToBookshelf(")
            .substringBefore("fun getBook(")
        val markReader = viewModel
            .substringAfter("private fun markReaderInBookshelf(book: Book) {")
            .substringBefore("fun getBook(")

        assertTrue(addToBookshelf.contains("markReaderInBookshelf(book)"))
        assertTrue(markReader.contains("if (isSameReaderBook(ReadBook.book, book))"))
        assertTrue(markReader.contains("ReadBook.inBookshelf = true"))
        assertTrue(markReader.contains("if (isSameReaderBook(AudioPlay.book, book))"))
        assertTrue(markReader.contains("AudioPlay.inBookshelf = true"))
        assertTrue(markReader.contains("if (isSameReaderBook(ReadManga.book, book))"))
        assertTrue(markReader.contains("ReadManga.inBookshelf = true"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
