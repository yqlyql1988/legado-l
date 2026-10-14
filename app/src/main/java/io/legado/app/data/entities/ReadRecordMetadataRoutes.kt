package io.legado.app.data.entities

/**
 * Capture an account before queueing a reading interval. A committed rename updates pending
 * intervals too, even when they reach the executor after the rename. Tickets distinguish
 * repeated uses of the same name, so returning to an old name cannot create a routing cycle.
 */
internal class ReadRecordMetadataRoutes {
    data class Identity(val name: String, val author: String)
    class Ticket internal constructor(internal var identity: Identity)

    private class BookRoutes {
        val captures = mutableMapOf<Identity, Ticket>()
        val tickets = mutableListOf<Ticket>()
        fun create(identity: Identity): Ticket = Ticket(identity).also {
            captures[identity] = it
            tickets += it
        }
    }

    private val books = mutableMapOf<String, BookRoutes>()

    @Synchronized
    fun capture(bookUrl: String, name: String, author: String): Ticket {
        val routes = books.getOrPut(bookUrl) { BookRoutes() }
        val identity = Identity(name, author)
        return routes.captures[identity] ?: routes.create(identity)
    }

    @Synchronized
    fun renamed(
        bookUrl: String,
        oldName: String,
        oldAuthor: String,
        newName: String,
        newAuthor: String,
        sync: Boolean,
    ) {
        val old = Identity(oldName, oldAuthor)
        val new = Identity(newName, newAuthor)
        if (old == new) return
        val routes = books.getOrPut(bookUrl) { BookRoutes() }
        if (old !in routes.captures) routes.create(old)
        if (sync) {
            routes.tickets.filter { it.identity == old }.forEach { it.identity = new }
        }
        routes.create(new)
    }

    @Synchronized
    fun resolve(ticket: Ticket): Identity = ticket.identity

    /** Readers have switched identities; future reuse of an old name starts a fresh account. */
    @Synchronized
    fun finishRename(bookUrl: String, name: String, author: String) {
        val current = Identity(name, author)
        books[bookUrl]?.captures?.keys?.removeAll { it != current }
    }
}

internal val readRecordMetadataRoutes = ReadRecordMetadataRoutes()
