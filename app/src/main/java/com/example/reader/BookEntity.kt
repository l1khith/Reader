package com.example.reader

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * BookEntity: The Room database model representing a single book in the user's library.
 * The [uriString] acts as the natural primary key since each book is uniquely identified by its URI.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    val uriString: String,
    val title: String,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val lastReadTimestamp: Long = System.currentTimeMillis()
) {
    /** Returns reading progress as a float from 0.0 to 1.0. */
    fun getProgress(): Float = if (totalPages > 0) currentPage.toFloat() / totalPages else 0f
}
