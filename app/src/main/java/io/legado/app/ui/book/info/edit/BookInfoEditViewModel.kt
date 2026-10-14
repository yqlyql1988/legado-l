package io.legado.app.ui.book.info.edit

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.readRecordMetadataRoutes
import io.legado.app.data.saveEditedBook
import io.legado.app.help.book.BookHelp
import io.legado.app.help.globalExecutor
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import kotlinx.coroutines.asCoroutineDispatcher

class BookInfoEditViewModel(application: Application) : BaseViewModel(application) {
    var book: Book? = null
    val bookData = MutableLiveData<Book>()
    var isSaving = false
        private set

    fun loadBook(bookUrl: String) {
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                bookData.postValue(it)
            }
        }
    }

    fun saveBook(book: Book, syncMetadata: Boolean, success: (() -> Unit)?) {
        if (isSaving) return
        isSaving = true
        execute(context = globalExecutor.asCoroutineDispatcher()) {
            val oldBook = appDb.saveEditedBook(book, syncMetadata)
            readRecordMetadataRoutes.renamed(
                book.bookUrl, oldBook.name, oldBook.author, book.name, book.author, syncMetadata,
            )
            ReadBook.updateEditedMetadata(book)
            ReadManga.updateEditedMetadata(book)
            AudioPlay.updateEditedMetadata(book)
            readRecordMetadataRoutes.finishRename(book.bookUrl, book.name, book.author)
            BookHelp.updateCacheFolder(oldBook, book)
            this@BookInfoEditViewModel.book = book
        }.onSuccess {
            isSaving = false
            success?.invoke()
        }.onError {
            isSaving = false
            if (it is SQLiteConstraintException) {
                AppLog.put("书籍信息保存失败，存在相同书名作者书籍\n$it", it, true)
            } else {
                AppLog.put("书籍信息保存失败\n$it", it, true)
            }
        }
    }
}
