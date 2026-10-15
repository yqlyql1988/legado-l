package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Permanent UUID marker prevents old backups and stale drafts from restoring deleted text. */
@Entity(tableName = "book_review_deletions")
data class BookReviewDeletion(@PrimaryKey val id: String)
