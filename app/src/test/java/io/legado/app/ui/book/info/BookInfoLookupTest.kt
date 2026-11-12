package io.legado.app.ui.book.info

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookInfoLookupTest {

    @Test
    fun `reader opens book info with the exact book url`() {
        val readActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        )
        val mangaActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt"
        )
        val readOpen = readActivity
            .substringAfter("override fun openBookInfoActivity()")
            .substringBefore("override fun")
        val mangaOpen = mangaActivity
            .substringAfter("override fun openBookInfoActivity()")
            .substringBefore("override fun")

        assertTrue(readOpen.contains("putExtra(\"bookUrl\", it.bookUrl)"))
        assertTrue(mangaOpen.contains("putExtra(\"bookUrl\", it.bookUrl)"))
    }

    @Test
    fun `book info lookup prefers the book url over name and author`() {
        val viewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
        )
        val initData = viewModel
            .substringAfter("fun initData(intent: Intent)")
            .substringBefore("fun upBook(intent: Intent)")
        val urlLookup = initData.indexOf("appDb.bookDao.getBook(bookUrl)")
        val nameLookup = initData.indexOf("appDb.bookDao.getBook(name, author)")
        val upBookIntent = viewModel
            .substringAfter("fun upBook(intent: Intent)")
            .substringBefore("private fun upBook(book: Book)")

        assertTrue(urlLookup >= 0)
        assertTrue(nameLookup > urlLookup)
        assertTrue(initData.contains("inBookshelf = !it.isNotShelf"))
        assertTrue(
            upBookIntent.indexOf("appDb.bookDao.getBook(bookUrl)") in
                0 until upBookIntent.indexOf("appDb.bookDao.getBook(name, author)")
        )
    }

    @Test
    fun `shelf button does not default to the delete action`() {
        listOf(
            "src/main/res/layout/activity_book_info.xml",
            "src/main/res/layout-land/activity_book_info.xml",
        ).forEach { path ->
            val layout = readProjectFile(path)
            val shelf = layout
                .substringAfter("android:id=\"@+id/tv_shelf\"")
                .substringBefore("/>")

            assertTrue(path, shelf.contains("android:text=\"@string/add_to_bookshelf\""))
        }
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
