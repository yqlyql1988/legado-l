package io.legado.app.data

import io.legado.app.data.entities.Book

/** Save the edit and its related identities atomically; leave everything intact on failure. */
internal fun AppDatabase.saveEditedBook(book: Book, syncMetadata: Boolean): Book {
    var original: Book? = null
    runInTransaction {
        val oldBook = checkNotNull(bookDao.getBook(book.bookUrl)) { "Book no longer exists" }
        original = oldBook
        bookDao.update(oldBook.copy().apply { applyEditedMetadata(book) })
        bookHighlightDao.updateBookMetadata(book.bookUrl, book.name, book.author)
        if (syncMetadata && (oldBook.name != book.name || oldBook.author != book.author)) {
            bookmarkDao.updateBookMetadata(oldBook.name, oldBook.author, book.name, book.author)
            readRecordDao.updateBookMetadata(oldBook.name, oldBook.author, book.name, book.author)
        }
    }
    return checkNotNull(original)
}

/** Apply only the fields edited here so active audio/manga/text progress stays current. */
internal fun Book.applyEditedMetadata(edited: Book) {
    name = edited.name
    author = edited.author
    type = edited.type
    customCoverUrl = edited.customCoverUrl
    persistedCoverUrl = edited.persistedCoverUrl
    customIntro = edited.customIntro
}
