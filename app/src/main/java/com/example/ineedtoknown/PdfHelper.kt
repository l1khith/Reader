package com.example.ineedtoknown

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
 * * CLEAN CODE PRINCIPLES:
 * 1. Single Responsibility: It only handles PDF parsing/rendering. No UI logic.
 * 2. Statelessness: Functions are pure; they take inputs and return outputs without side effects.
 * 3. Error Handling: Catches IO exceptions internally to prevent app crashes.
 */
object PdfHelper {

    private const val TAG = "PdfHelper"

    /**
     * Initializes the PDFBox library.
     * Must be called in MainActivity.onCreate() before any other operations.
     */
    fun init(context: Context) {
        PDFBoxResourceLoader.init(context)
    }

    /**
     * EXTRACT TEXT (The "Smart" Mode)
     * Uses PDFBox to strip text from a specific page and split it into sentences.
     * * @param pageIndex 0-based index of the page to read.
     * @return A list of clean, speakable sentences.
     */
    fun extractTextFromPage(context: Context, uri: Uri, pageIndex: Int): List<String> {
        try {
            // 1. Open the file stream
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // 2. Load the document into PDFBox
                // Note: Loading large docs can be slow, so we do this inside the IO dispatcher
                val document = PDDocument.load(inputStream)

                try {
                    // 3. Configure the Stripper to only look at ONE page
                    // PDFBox uses 1-based indexing for start/endPage
                    val stripper = PDFTextStripper().apply {
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                        sortByPosition = true // Ensures text is read top-to-bottom, left-to-right
                    }

                    // 4. Extract raw text
                    val fullText = stripper.getText(document)

                    // 5. Clean and Split
                    // We split by punctuation (. ! ?) followed by whitespace to get distinct sentences.
                    return fullText
                        .split(Regex("(?<=[.!?])\\s+"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() } // Remove empty garbage strings

                } finally {
                    // ALWAYS close the document to prevent memory leaks
                    document.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text: ${e.message}")
            return listOf("Error reading page. Please try another file.")
        }
        return emptyList()
    }

    /**
     * RENDER IMAGE (The "Visual" Mode)
     * Uses Android's native PdfRenderer to create a high-quality Bitmap of the page.
     * * @return A Bitmap of the page, or null if rendering failed.
     */
    fun renderPageToBitmap(context: Context, uri: Uri, pageIndex: Int): Bitmap? {
        var fileDescriptor: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null
        var currentPage: PdfRenderer.Page? = null

        try {
            // 1. Open File Descriptor
            fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null

            // 2. Create Renderer
            pdfRenderer = PdfRenderer(fileDescriptor)

            // Guard clause: Don't crash if pageIndex is out of bounds
            if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) {
                return null
            }

            // 3. Open Page
            currentPage = pdfRenderer.openPage(pageIndex)

            // 4. Create Bitmap
            // We use the page's actual dimensions.
            // For higher quality zoom, you could multiply width/height by a scale factor (e.g., 2f)
            val bitmap = createBitmap(currentPage.width, currentPage.height)

            // 5. Render
            currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            return bitmap

        } catch (e: Exception) {
            Log.e(TAG, "Error rendering page: ${e.message}")
            return null
        } finally {
            // 6. Cleanup (Crucial for avoiding memory leaks and file locks)
            try {
                currentPage?.close()
                pdfRenderer?.close()
                fileDescriptor?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Helper to get total page count.
     * Useful for checking bounds before navigation.
     */
    fun getPageCount(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }
}