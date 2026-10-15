package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

/** A personal review survives removing the book or changing its source. */
@Entity(
    tableName = "book_reviews",
    indices = [Index(value = ["bookName", "bookAuthor"]), Index(value = ["bookUrl"])]
)
data class BookReview(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val bookName: String = "",
    val bookAuthor: String = "",
    val bookUrl: String = "",
    val coverUrl: String? = null,
    /** Half-star units: 1 means 0.5 stars, 10 means 5 stars; null keeps the previous score. */
    val rating: Int? = null,
    val content: String = "",
    /** A calendar date, independent of the device's time zone and last edit time. */
    val reviewDate: String = LocalDate.now().toString(),
    val isFollowUp: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    fun validated(): BookReview = apply {
        require(id.isNotBlank() && bookName.isNotBlank()) { "书评缺少书籍信息" }
        require(content.isNotBlank()) { "请填写文字评价" }
        require(reviewDate == LocalDate.parse(reviewDate).toString()) { "书评日期无效" }
        require(rating == null || rating in 1..10) { "评分应为半星至五星" }
        require(isFollowUp || rating != null) { "请为初评选择评分" }
        require(createdAt >= 0 && updatedAt >= createdAt) { "书评保存时间无效" }
    }
}

val bookReviewOrder: Comparator<BookReview> =
    compareByDescending<BookReview> { it.reviewDate }
        .thenByDescending { it.createdAt }
        .thenBy { it.id }

data class BookReviewArchive(val reviews: List<BookReview>) {
    val latest: BookReview get() = reviews.first()
    val rating: Int? get() = reviews.firstOrNull { it.rating != null }?.rating
}

fun archiveBookReviews(reviews: List<BookReview>): List<BookReviewArchive> =
    reviews.groupBy { it.bookName to it.bookAuthor }
        .values.map { BookReviewArchive(it.sortedWith(bookReviewOrder)) }
        .sortedWith { left, right -> bookReviewOrder.compare(left.latest, right.latest) }

/** IDs make repeated restores idempotent; an older backup cannot overwrite a newer edit. */
fun mergeBookReviews(existing: List<BookReview>, restored: List<BookReview>): List<BookReview> {
    restored.forEach { it.validated() }
    val merged = existing.associateByTo(linkedMapOf()) { it.id }
    restored.forEach { review ->
        val previous = merged[review.id]
        if (previous == null || review.updatedAt > previous.updatedAt) merged[review.id] = review
    }
    return merged.values.toList()
}
