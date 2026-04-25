package com.example.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * BookStore: The data access facade for the book library.
 *
 * ARCHITECTURE FIX (Issue #4 — SharedPreferences I/O Bottleneck):
 * Previously, every page turn serialized the ENTIRE book list to a JSON string in SharedPreferences.
 * This has been replaced with Room, which allows updating a SINGLE ROW per save operation,
 * making it O(1) instead of O(N) and fully off the main thread.
 *
 * The public API is intentionally kept compatible with the old BookStore callers.
 * [BookData] is preserved as a UI-layer type alias over [BookEntity] for minimal churn.
 */
typealias BookData = BookEntity

object BookStore {

    private fun dao(context: Context): BookDao =
        AppDatabase.getInstance(context).bookDao()

    /**
     * Save or update a book's reading progress.
     * Runs asynchronously on the IO dispatcher — safe to call from the main thread on every page turn.
     */
    fun saveBookProgress(context: Context, uri: Uri, page: Int, totalPages: Int, title: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val existing = dao(context).getBook(uri.toString())
            val book = BookEntity(
                uriString = uri.toString(),
                title = title ?: existing?.title ?: uri.lastPathSegment ?: "Unknown Document",
                currentPage = page,
                totalPages = totalPages,
                lastReadTimestamp = System.currentTimeMillis()
            )
            dao(context).upsertBook(book)
        }
    }

    /**
     * Returns a [Flow] of all books sorted by most recently read.
     * Collect this in the UI to get live updates whenever the library changes.
     */
    fun getAllBooksFlow(context: Context): Flow<List<BookEntity>> =
        dao(context).getAllBooksFlow()

    /**
     * One-shot synchronous-like read for initial page restoration.
     * Must be called from a coroutine / IO dispatcher.
     */
    suspend fun getBook(context: Context, uriString: String): BookEntity? =
        dao(context).getBook(uriString)

    /**
     * Deletes a book from the library by its URI string.
     * Runs asynchronously on the IO dispatcher.
     */
    fun deleteBook(context: Context, uriString: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dao(context).deleteBook(uriString)
        }
    }
}