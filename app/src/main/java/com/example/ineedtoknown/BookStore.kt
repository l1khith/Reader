package com.example.ineedtoknown

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class BookData(
    val uriString: String,
    val title: String,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val lastReadTimestamp: Long = System.currentTimeMillis()
) {
    // Helper to get real progress percentage (0.0 to 1.0)
    fun getProgress(): Float {
        return if (totalPages > 0) currentPage.toFloat() / totalPages else 0f
    }
}

object BookStore {
    private const val PREFS_NAME = "smart_reader_prefs"
    private const val KEY_BOOKS = "saved_books"
    private val gson = Gson()

    // Save or Update a book's progress
    fun saveBookProgress(context: Context, uri: Uri, page: Int, totalPages: Int, title: String? = null) {
        val books = getAllBooks(context).toMutableList()
        val index = books.indexOfFirst { it.uriString == uri.toString() }

        val timestamp = System.currentTimeMillis()

        if (index != -1) {
            // Update existing book
            val existing = books[index]
            books[index] = existing.copy(
                currentPage = page,
                totalPages = totalPages,
                lastReadTimestamp = timestamp,
                // Only update title if we passed a new valid one, otherwise keep old
                title = title ?: existing.title
            )
        } else {
            // Add new book
            val newTitle = title ?: uri.lastPathSegment ?: "Unknown Document"
            books.add(BookData(uri.toString(), newTitle, page, totalPages, timestamp))
        }

        saveList(context, books)
    }

    // Get all books, sorted by most recently read
    fun getAllBooks(context: Context): List<BookData> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        val type = object : TypeToken<List<BookData>>() {}.type

        // Sort by time (newest first)
        val list: List<BookData> = gson.fromJson(json, type)
        return list.sortedByDescending { it.lastReadTimestamp }
    }

    private fun saveList(context: Context, list: List<BookData>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BOOKS, gson.toJson(list)).apply()
    }
}