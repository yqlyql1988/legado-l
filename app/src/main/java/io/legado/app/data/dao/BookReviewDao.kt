package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.mergeBookReviews
import kotlinx.coroutines.flow.Flow

@Dao
interface BookReviewDao {
    @Query("select * from book_reviews")
    fun all(): List<BookReview>

    @Query("select * from book_reviews")
    fun flowAll(): Flow<List<BookReview>>

    @Query("select * from book_reviews where id = :id")
    fun get(id: String): BookReview?

    @Query("select * from books where bookUrl = :url")
    fun bookForUrl(url: String): Book?

    @Query("""select * from book_reviews where bookName = :name and bookAuthor = :author
        order by reviewDate desc, createdAt desc, id""")
    fun forBook(name: String, author: String): List<BookReview>

    @Query("""select * from book_reviews where bookName = :name and bookAuthor = :author
        order by reviewDate desc, createdAt desc, id""")
    fun flowForBook(name: String, author: String): Flow<List<BookReview>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg reviews: BookReview)

    @Transaction
    fun save(review: BookReview, now: Long = System.currentTimeMillis()): BookReview {
        val previous = get(review.id)
        check(previous == null || previous.updatedAt == review.updatedAt) {
            "这篇书评已在其他操作中更新，请保留文字并重新打开后保存"
        }
        val saved = if (previous == null) {
            val book = bookForUrl(review.bookUrl)
            val name = book?.name ?: review.bookName
            val author = book?.author ?: review.bookAuthor
            review.copy(
                bookName = name,
                bookAuthor = author,
                isFollowUp = forBook(name, author).isNotEmpty(),
                createdAt = now,
                updatedAt = now,
            )
        } else {
            previous.copy(
                rating = review.rating,
                content = review.content,
                reviewDate = review.reviewDate,
                updatedAt = maxOf(now, previous.updatedAt + 1),
            )
        }.validated()
        insert(saved)
        return saved
    }

    @Transaction
    fun restore(reviews: List<BookReview>) {
        insert(*mergeBookReviews(all(), reviews).toTypedArray())
    }

    @Query("""update book_reviews set bookName = :newName, bookAuthor = :newAuthor,
        updatedAt = case when updatedAt >= :now then updatedAt + 1 else :now end
        where bookName = :oldName and bookAuthor = :oldAuthor""")
    fun updateBookMetadata(
        oldName: String, oldAuthor: String, newName: String, newAuthor: String,
        now: Long = System.currentTimeMillis(),
    )
}
