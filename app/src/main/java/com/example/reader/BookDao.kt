package com.example.reader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * BookDao: Data Access Object defining all database operations for the books table.
 * Uses REPLACE strategy on insert so that saving progress always upserts without needing a separate UPDATE query.
 */
@Dao
interface BookDao {

    /**
     * Upserts a book record. If a book with the same [uriString] already exists, it is replaced.
     * This is the critical fix: instead of rewriting the entire JSON list, we touch only one row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity)

    /**
     * Returns all books ordered by last read time, newest first.
     * Exposed as a Flow so the UI reacts to changes automatically without polling.
     */
    @Query("SELECT * FROM books ORDER BY lastReadTimestamp DESC")
    fun getAllBooksFlow(): Flow<List<BookEntity>>

    /**
     * One-shot synchronous read — used for initial page restoration in ReaderScreen.
     */
    @Query("SELECT * FROM books WHERE uriString = :uriString LIMIT 1")
    suspend fun getBook(uriString: String): BookEntity?

    /** Deletes a book by its URI string. */
    @Query("DELETE FROM books WHERE uriString = :uriString")
    suspend fun deleteBook(uriString: String)
}
