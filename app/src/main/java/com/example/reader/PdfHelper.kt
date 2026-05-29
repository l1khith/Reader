package com.example.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/**
 * PdfHelper: A Singleton object responsible for all low-level PDF operations.
 *
 * ARCHITECTURE FIXES:
 * — Issue #2 (PDF Parsing Bottleneck): [cachedDocument] is opened once per session via [openDocument]
 *   and reused across all [extractTextFromPage] calls. Closed via [closeDocument] when the user exits.
 * — Issue #6 (PdfRenderer Reallocation): [cachedRenderer] and [cachedDescriptor] are cached via
 *   [openRenderer] and reused across all [renderPageToBitmap] calls. Closed via [closeRenderer].
 *
 * Lifecycle management is delegated to [ReaderScreen] via DisposableEffect.
 */
object PdfHelper {

    private const val TAG = "PdfHelper"

    // --- Issue #2 fix: Cached PDDocument ---
    private var cachedDocument: PDDocument? = null
    private var cachedDocumentUri: Uri? = null

    // --- Issue #6 fix: Cached PdfRenderer ---
    private var cachedRenderer: PdfRenderer? = null
    private var cachedDescriptor: ParcelFileDescriptor? = null
    private var cachedRendererUri: Uri? = null

    /**
     * Initializes the PDFBox library.
     * Must be called in MainActivity.onCreate() before any other operations.
     */
    fun init(context: Context) {
        PDFBoxResourceLoader.init(context)
    }

    // ─────────────────────────────────────────────────────────────
    // PDDocument lifecycle (Issue #2)
    // ─────────────────────────────────────────────────────────────

    /**
     * Opens the [PDDocument] for the given [uri] and caches it.
     * Call this when the user enters [ReaderScreen].
     */
    fun openDocument(context: Context, uri: Uri) {
        if (cachedDocumentUri == uri && cachedDocument != null) return // Already open
        closeDocument() // Close any previously cached document
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                cachedDocument = PDDocument.load(inputStream)
                cachedDocumentUri = uri
                Log.d(TAG, "PDDocument opened: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open PDDocument: ${e.message}")
        }
    }

    /**
     * Closes and releases the cached [PDDocument].
     * Call this in [ReaderScreen]'s onDispose.
     */
    fun closeDocument() {
        try {
            cachedDocument?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing PDDocument: ${e.message}")
        } finally {
            cachedDocument = null
            cachedDocumentUri = null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PdfRenderer lifecycle (Issue #6)
    // ─────────────────────────────────────────────────────────────

    /**
     * Opens the native [PdfRenderer] for the given [uri] and caches it.
     * Call this when the user enters [ReaderScreen].
     */
    fun openRenderer(context: Context, uri: Uri) {
        if (cachedRendererUri == uri && cachedRenderer != null) return // Already open
        closeRenderer() // Close any previously cached renderer
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
            cachedDescriptor = fd
            cachedRenderer = PdfRenderer(fd)
            cachedRendererUri = uri
            Log.d(TAG, "PdfRenderer opened: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open PdfRenderer: ${e.message}")
        }
    }

    /**
     * Closes and releases the cached [PdfRenderer] and its [ParcelFileDescriptor].
     * Call this in [ReaderScreen]'s onDispose.
     */
    fun closeRenderer() {
        try {
            cachedRenderer?.close()
            cachedDescriptor?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing PdfRenderer: ${e.message}")
        } finally {
            cachedRenderer = null
            cachedDescriptor = null
            cachedRendererUri = null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PDF operations
    // ─────────────────────────────────────────────────────────────

    /**
     * EXTRACT TEXT (The "Smart" Mode)
     * Uses the cached [PDDocument] to strip text from a specific page.
     * Falls back to opening the document fresh if not cached (defensive fallback).
     *
     * @param pageIndex 0-based index of the page to read.
     * @return A list of clean, speakable sentences.
     */
    @Synchronized
    fun extractTextFromPage(context: Context, uri: Uri, pageIndex: Int): List<String> {
        try {
            // Ensure document is cached; use it if available
            val document = cachedDocument?.takeIf { cachedDocumentUri == uri }
                ?: run {
                    Log.w(TAG, "PDDocument not cached; opening fresh for page $pageIndex")
                    context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
                }
                ?: return listOf("Error: could not open document.")

            val stripper = PDFTextStripper().apply {
                startPage = pageIndex + 1 // PDFBox is 1-based
                endPage = pageIndex + 1
                sortByPosition = true
            }

            val fullText = stripper.getText(document)

            return fullText
                .split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text: ${e.message}")
            return listOf("Error reading page. Please try another file.")
        }
    }

    /**
     * RENDER IMAGE (The "Visual" Mode)
     * Uses the cached [PdfRenderer] to create a high-quality Bitmap of the page.
     *
     * @return A Bitmap of the page, or null if rendering failed.
     */
    @Synchronized
    fun renderPageToBitmap(context: Context, uri: Uri, pageIndex: Int): Bitmap? {
        val renderer = cachedRenderer?.takeIf { cachedRendererUri == uri }
            ?: run {
                Log.w(TAG, "PdfRenderer not cached; opening fresh for page $pageIndex")
                openRenderer(context, uri)
                cachedRenderer
            }
            ?: return null

        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null

        var currentPage: PdfRenderer.Page? = null
        return try {
            currentPage = renderer.openPage(pageIndex)

            // Render at 2× the PDF's native point size for crisp text on high-DPI screens
            val scale = 2
            val bitmapWidth  = currentPage.width  * scale
            val bitmapHeight = currentPage.height * scale
            val bitmap = createBitmap(bitmapWidth, bitmapHeight)

            // Fill white: PdfRenderer writes transparent pixels where the PDF background is
            // unset, which appear black on a dark surface without this.
            bitmap.eraseColor(android.graphics.Color.WHITE)

            val matrix = android.graphics.Matrix().apply {
                setScale(scale.toFloat(), scale.toFloat())
            }
            currentPage.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering page: ${e.message}")
            null
        } finally {
            try { currentPage?.close() } catch (e: IOException) { e.printStackTrace() }
        }
    }

    /**
     * Helper to get total page count from the cached renderer, or opens one fresh.
     */
    fun getPageCount(context: Context, uri: Uri): Int {
        cachedRenderer?.takeIf { cachedRendererUri == uri }?.let { return it.pageCount }
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            } ?: 0
        } catch (e: Exception) { 0 }
    }
}