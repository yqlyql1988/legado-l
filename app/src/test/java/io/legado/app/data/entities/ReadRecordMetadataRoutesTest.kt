package io.legado.app.data.entities

import io.legado.app.help.config.BookMetadataSync
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRecordMetadataRoutesTest {
    private val routes = ReadRecordMetadataRoutes()
    private fun capture(name: String, author: String = "Author", url: String = "book") =
        routes.capture(url, name, author)
    private fun rename(old: String, new: String, sync: Boolean = true) =
        routes.renamed("book", old, "Author", new, "Author", sync)
    private fun assertIdentity(ticket: ReadRecordMetadataRoutes.Ticket, name: String, author: String = "Author") =
        assertEquals(ReadRecordMetadataRoutes.Identity(name, author), routes.resolve(ticket))

    @Test
    fun `pending intervals follow consecutive synced renames`() {
        val original = capture("A")
        rename("A", "B")
        val intermediate = capture("B")
        rename("B", "C")
        assertIdentity(original, "C")
        assertIdentity(intermediate, "C")
        assertIdentity(capture("C"), "C")
    }

    @Test
    fun `capture in the gap before the active reader updates still follows the rename`() {
        rename("A", "B")
        assertIdentity(capture("A"), "B")
    }

    @Test
    fun `reusing an old name after readers update keeps pending writes and new accounts separate`() {
        val pending = capture("A")
        rename("A", "B")
        routes.finishRename("book", "B", "Author")
        assertIdentity(pending, "B")
        assertIdentity(capture("A"), "A")
        rename("B", "C")
        assertIdentity(pending, "C")
    }

    @Test
    fun `returning to an earlier name cannot create a cycle`() {
        val original = capture("A")
        rename("A", "B")
        val intermediate = capture("B")
        rename("B", "A")
        val returned = capture("A")
        rename("A", "C")
        listOf(original, intermediate, returned).forEach { assertIdentity(it, "C") }
    }

    @Test
    fun `nonsynced edits keep pending history on its previous identity`() {
        val original = capture("A")
        rename("A", "B")
        val intermediate = capture("B")
        rename("B", "A", sync = false)
        val returned = capture("A")
        assertIdentity(original, "B")
        assertIdentity(intermediate, "B")
        assertIdentity(returned, "A")
        rename("A", "C")
        assertIdentity(original, "B")
        assertIdentity(intermediate, "B")
        assertIdentity(returned, "C")
    }

    @Test
    fun `identical titles in other books or with other authors keep their identity`() {
        val original = capture("A")
        val anotherUrl = capture("A", url = "another-book")
        val anotherAuthor = capture("A", author = "Another")
        routes.renamed("book", "A", "Author", "B", "New author", true)
        assertIdentity(original, "B", "New author")
        assertIdentity(anotherUrl, "A")
        assertIdentity(anotherAuthor, "A", "Another")
    }

    @Test
    fun `missing or invalid settings ask and saved choices retain their behavior`() {
        assertEquals(BookMetadataSync.ASK, BookMetadataSync.fromPreference(null))
        assertEquals(BookMetadataSync.ASK, BookMetadataSync.fromPreference("invalid"))
        assertEquals(BookMetadataSync.ASK, BookMetadataSync.fromPreference("ask"))
        assertEquals(BookMetadataSync.SYNC, BookMetadataSync.fromPreference("sync"))
        assertEquals(BookMetadataSync.NEVER, BookMetadataSync.fromPreference("never"))
    }
}
