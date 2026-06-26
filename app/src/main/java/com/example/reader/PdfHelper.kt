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


    // ── Two independent document handles ─────────────────────────────────────
    //
    // Both handles are opened from the same raw fd BEFORE it is closed.
    // Because we use MAP_SHARED mmap, the OS maps the same physical pages into
    // both address spaces — no extra RAM is consumed for the file data itself.
    // Only Pdfium's internal cross-reference / page-tree structures are
    // duplicated (typically a few hundred KB for a standard book).
    //
    // WHY TWO HANDLES:
    //   With a single handle + single mutex, extractTextFromPage (now an N-char
    //   FPDFText_GetCharBox loop) and renderPageToBitmap compete for the same
    //   lock. A 400-character page extraction can hold the mutex for ~80 ms,
    //   causing visible jank on a simultaneous page swipe.
    //
    //   With two handles + two independent mutexes, render and extract run
    //   fully concurrently. openDocument/closeAll still acquires both mutexes
    //   (in fixed order: renderMutex → ttsMutex) to keep state consistent.

    private var renderDocPtr: Long = 0L   // owned by renderMutex; used by the pager UI
    private var ttsDocPtr:    Long = 0L   // owned by ttsMutex;    used by ReaderService TTS

    private var cachedUri: Uri? = null

    private val renderMutex = Mutex()     // guards renderDocPtr + bitmapPool
    private val ttsMutex    = Mutex()     // guards ttsDocPtr — independent of renderMutex

    // ── Bitmap pool: 3 slots matching the HorizontalPager window ─────────────
    private val bitmapPool: Array<Bitmap?> = arrayOfNulls(3)

    private var targetWidth:  Int = 1080
    private var targetHeight: Int = 1527

    // ─────────────────────────────────────────────────────────────────────────
    // Document lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun openDocument(context: Context, uri: Uri): PdfResult<Int> {
        // Lock order: renderMutex → ttsMutex (always same order to prevent deadlock)
        return renderMutex.withLock {
            ttsMutex.withLock {
                if (cachedUri == uri && renderDocPtr != 0L && ttsDocPtr != 0L) {
                    return@withLock PdfResult.Success(NativePdfEngine.getPageCount(renderDocPtr))
                }

                closeAllUnlocked()
                resolveTargetSize(context)
                prewarmPool()

                runCatching {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: return@withLock PdfResult.Error("ContentResolver returned null for $uri")

                    // Open both handles from the same fd before closing it.
                    // mmap() in loadDocument does not consume the fd — it is safe
                    // to call loadDocument twice on the same fd.
                    val rawFd  = pfd.fd
                    val rPtr   = NativePdfEngine.loadDocument(rawFd)
                    val ttsPtr = NativePdfEngine.loadDocument(rawFd)
                    pfd.close()

                    if (rPtr == 0L || ttsPtr == 0L) {
                        if (rPtr   != 0L) NativePdfEngine.closeDocument(rPtr)
                        if (ttsPtr != 0L) NativePdfEngine.closeDocument(ttsPtr)
                        return@withLock PdfResult.Error("Pdfium failed to load document")
                    }

                    renderDocPtr = rPtr
                    ttsDocPtr    = ttsPtr
                    cachedUri    = uri
                    PdfResult.Success(NativePdfEngine.getPageCount(renderDocPtr))
                }.getOrElse { e ->
                    PdfResult.Error(e.message ?: "Unknown error opening document")
                }
            }
        }
    }

    // Called only while BOTH mutexes are already held.
    private fun closeAllUnlocked() {
        if (renderDocPtr != 0L) {
            NativePdfEngine.closeDocument(renderDocPtr)
            renderDocPtr = 0L
        }
        if (ttsDocPtr != 0L) {
            NativePdfEngine.closeDocument(ttsDocPtr)
            ttsDocPtr = 0L
        }
        cachedUri = null
        recycleBitmapPool()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getPageCount(): Int {
        return renderMutex.withLock {
            if (renderDocPtr == 0L) 0 else NativePdfEngine.getPageCount(renderDocPtr)
        }
    }

    // Uses renderDocPtr — independent of TTS extraction.
    suspend fun renderPageToBitmap(pageIndex: Int): PdfResult<PageRender> {
        return renderMutex.withLock {
            if (renderDocPtr == 0L) return@withLock PdfResult.NotReady

            runCatching {
                val slot   = pageIndex % 3
                val bitmap = ensurePoolBitmap(slot)

                if (NativePdfEngine.renderPage(renderDocPtr, pageIndex, bitmap)) {
                    PdfResult.Success(PageRender(bitmap))
                } else {
                    PdfResult.Error("Pdfium renderPage returned false for page $pageIndex")
                }
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Native render crashed on page $pageIndex")
            }
        }
    }

    // Uses ttsDocPtr — can run concurrently with renderPageToBitmap.
    suspend fun extractTextFromPage(pageIndex: Int): PdfResult<List<String>> {
        return ttsMutex.withLock {
            if (ttsDocPtr == 0L) return@withLock PdfResult.Error("Document not open")

            runCatching {
                val raw = NativePdfEngine.extractText(ttsDocPtr, pageIndex)
                val sentences = raw.split(Regex("(?<=[.!?])\\s+"))
                    .map    { it.trim() }
                    .filter { it.isNotBlank() }
                PdfResult.Success(sentences)
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Text extraction crashed on page $pageIndex")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bitmap pool helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun prewarmPool() {
        for (slot in 0 until 3) ensurePoolBitmap(slot)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Display helpers
    // ─────────────────────────────────────────────────────────────────────────

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