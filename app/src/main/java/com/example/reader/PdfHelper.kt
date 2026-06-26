package com.example.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class PdfResult<out T> {
    data class Success<T>(val value: T) : PdfResult<T>()
    data class Error(val message: String) : PdfResult<Nothing>()
    object NotReady : PdfResult<Nothing>()
}

data class PageRender(
    val bitmap: Bitmap,
    val updateTrigger: Long = System.nanoTime()
)

object PdfHelper {

    private const val TAG = "PdfHelper"

    private var docPtr: Long = 0L
    private var cachedUri: Uri? = null

    private val pdfMutex = Mutex()

    private val bitmapPool: Array<Bitmap?> = arrayOfNulls(3)

    private var targetWidth:  Int = 1080
    private var targetHeight: Int = 1527

    suspend fun openDocument(context: Context, uri: Uri): PdfResult<Int> {
        return pdfMutex.withLock {
            if (cachedUri == uri && docPtr != 0L) {
                return@withLock PdfResult.Success(NativePdfEngine.getPageCount(docPtr))
            }

            closeAll()
            resolveTargetSize(context)
            prewarmPool()

            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Error("ContentResolver returned null for $uri")

                val ptr = NativePdfEngine.loadDocument(pfd.fd)
                pfd.close()

                if (ptr == 0L) {
                    return@withLock PdfResult.Error("Pdfium failed to load document")
                }

                docPtr    = ptr
                cachedUri = uri
                PdfResult.Success(NativePdfEngine.getPageCount(docPtr))
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Unknown error opening document")
            }
        }
    }

    private fun closeAll() {
        if (docPtr != 0L) {
            NativePdfEngine.closeDocument(docPtr)
            docPtr = 0L
        }
        cachedUri = null
        recycleBitmapPool()
    }

    suspend fun getPageCount(context: Context, uri: Uri): Int {
        return pdfMutex.withLock {
            if (docPtr == 0L) 0 else NativePdfEngine.getPageCount(docPtr)
        }
    }

    suspend fun renderPageToBitmap(context: Context, uri: Uri, pageIndex: Int): PdfResult<PageRender> {
        return pdfMutex.withLock {
            if (docPtr == 0L) return@withLock PdfResult.NotReady

            runCatching {
                val slot   = pageIndex % 3
                val bitmap = ensurePoolBitmap(slot)

                if (NativePdfEngine.renderPage(docPtr, pageIndex, bitmap)) {
                    PdfResult.Success(PageRender(bitmap))
                } else {
                    PdfResult.Error("Pdfium renderPage returned false for page $pageIndex")
                }
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Native render crashed on page $pageIndex")
            }
        }
    }

    suspend fun extractTextFromPage(context: Context, uri: Uri, pageIndex: Int): PdfResult<List<String>> {
        return pdfMutex.withLock {
            if (docPtr == 0L) return@withLock PdfResult.Error("Document not open")

            runCatching {
                val raw = NativePdfEngine.extractText(docPtr, pageIndex)
                val sentences = raw.split(Regex("(?<=[.!?])\\s+"))
                    .map    { it.trim() }
                    .filter { it.isNotBlank() }
                PdfResult.Success(sentences)
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Text extraction crashed on page $pageIndex")
            }
        }
    }

    private fun prewarmPool() {
        for (slot in 0 until 3) {
            ensurePoolBitmap(slot)
        }
    }

    private fun ensurePoolBitmap(slot: Int): Bitmap {
        val existing = bitmapPool[slot]
        if (existing != null &&
            existing.width  == targetWidth &&
            existing.height == targetHeight &&
            !existing.isRecycled) {
            return existing
        }
        existing?.recycle()
        val fresh = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bitmapPool[slot] = fresh
        return fresh
    }

    private fun recycleBitmapPool() {
        for (i in bitmapPool.indices) {
            bitmapPool[i]?.recycle()
            bitmapPool[i] = null
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveTargetSize(context: Context) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            targetWidth  = dm.widthPixels
            targetHeight = (dm.heightPixels * 0.75f).toInt()
        } catch (_: Exception) {}
    }
}