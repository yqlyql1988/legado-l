package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.BookReadingMark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookReadingMarkDao {
    @Query("select * from book_reading_marks where bookName = :name and bookAuthor = :author")
    fun get(name: String, author: String): BookReadingMark?

    @Query("select * from book_reading_marks where bookName = :name and bookAuthor = :author")
    fun flowForBook(name: String, author: String): Flow<BookReadingMark?>

    @Query("select * from book_reading_marks")
    fun all(): List<BookReadingMark>

    @Query("select * from book_reading_marks")
    fun flowAll(): Flow<List<BookReadingMark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(mark: BookReadingMark)

    @Transaction
    fun save(mark: BookReadingMark, now: Long = System.currentTimeMillis()): BookReadingMark {
        val previous = get(mark.bookName, mark.bookAuthor)
        check((previous?.updatedAt ?: 0) == mark.updatedAt) {
            "阅读标记已更新，请重新打开后修改"
        }
        // An empty, dated row is a tombstone: old backups cannot resurrect cleared marks.
        val saved = mark.copy(updatedAt = maxOf(now, mark.updatedAt + 1)).validated()
        insert(saved)
        return saved
    }

    @Transaction
    fun restore(marks: List<BookReadingMark>) {
        marks.forEach { it.validated() }
        marks.forEach { mark ->
            if (mark.updatedAt > (get(mark.bookName, mark.bookAuthor)?.updatedAt ?: -1)) insert(mark)
        }
    }

    @Transaction
    fun rename(oldName: String, oldAuthor: String, name: String, author: String) {
        val old = get(oldName, oldAuthor) ?: return
        val target = get(name, author)
        val latest = if (target != null && target.updatedAt > old.updatedAt) target else old
        val moved = latest.copy(
            bookName = name,
            bookAuthor = author,
            skipped = (old.skipped + target?.skipped.orEmpty()).distinctBy {
                listOf(it.bookUrl, it.url, it.title, it.index.toString())
            },
            updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1, (target?.updatedAt ?: 0) + 1),
        )
        insert(moved.validated())
        insert(BookReadingMark(oldName, oldAuthor, updatedAt = moved.updatedAt))
    }
}
