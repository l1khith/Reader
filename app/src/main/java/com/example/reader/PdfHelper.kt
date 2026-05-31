package com.example.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lightweight wrapper around a pool Bitmap.
 *
 * WHY THIS EXISTS — The Compose State Equality Trap:
 * `produceState` only triggers a UI redraw when `value` changes to a NEW object reference.
 * Because the Bitmap pool reuses the same 3 Bitmap instances, returning the bare Bitmap
 * means Compose sees "same reference as before" and skips the redraw — page 4 would
 * silently display page 1's content.
 *
 * Wrapping in a data class with a monotonic [updateTrigger] guarantees a new object on
 * every render call, forcing Compose to notice the change and redraw.
 */
data class PageRender(
    val bitmap: Bitmap,
    val updateTrigger: Long = System.nanoTime()
)

/**
 * PdfHelper — stateful singleton wrapper around [NativePdfEngine] (Pdfium JNI).
 *
 * ARCHITECTURE OVERVIEW
 * ─────────────────────
 * This class replaces the old dual-engine design (PDFBox for text + Android
 * PdfRenderer for visuals) with a single Pdfium C++ engine.
 *
 * BITMAP POOL
 * ───────────
 * The [HorizontalPager] in ReaderScreen keeps at most 3 pages alive at once
 * (beyondViewportPageCount = 1): page n-1, n, and n+1.
 *
 *   Pager window:  [ page n-1 ]  [ page n ]  [ page n+1 ]
 *   Pool slot:     [  slot 0  ]  [ slot 1 ]  [  slot 2  ]   (pageIndex % 3)
 *
 * Three consecutive page indices always map to three DISTINCT slots, so no
 * Bitmap is ever reused while it is still displayed on-screen.
 *
 * [renderPageToBitmap] passes a pool Bitmap directly to the JNI layer, which
 * renders into its pixel buffer without allocating anything extra in C++ or
 * Kotlin — eliminating the OOM Bitmap churn of the previous design.
 *
 * THREAD SAFETY
 * ─────────────
 * All public functions that touch [docPtr] or the pool are protected by a
 * Kotlin Coroutine [Mutex] via [mutex.withLock].
 *
 * WHY MUTEX OVER @Synchronized:
 * `@Synchronized` physically BLOCKS the underlying OS thread while waiting
 * for the lock. Under heavy paging (10 fast swipes = 10 concurrent render
 * coroutines), this starves Android's Dispatchers.IO thread pool, causing
 * audio stuttering and UI frame drops.
 *
 * `Mutex.withLock` SUSPENDS the coroutine instead of blocking the thread,
 * freeing the IO thread to handle TTS or DB work while it waits. The
 * Pdfium FPDF_Document is still fully serialised — only one caller touches
 * it at a time — but no OS threads are wasted in the queue.
 */
object PdfHelper {

    private const val TAG = "PdfHelper"

    // ── Pdfium document handle (0 = closed) ──────────────────────────────
    private var docPtr: Long = 0L
    private var cachedUri: Uri? = null

    // ── Coroutine Mutex: suspends (not blocks) coroutines waiting for the lock.
    // All Pdfium C++ calls are serialised through this single mutex.
    private val pdfMutex = Mutex()

    // ── Bitmap pool: exactly 3 slots for the pager's 3-page window ───────
    private val bitmapPool: Array<Bitmap?> = arrayOfNulls(3)

    // Target render resolution — derived from the device's real display size.
    // Set once in openDocument() and reused for the lifetime of the session.
    private var targetWidth:  Int = 1080
    private var targetHeight: Int = 1527   // ~A4 aspect ratio default

    // ─────────────────────────────────────────────────────────────────────
    // Document lifecycle
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Opens the PDF at [uri] using Pdfium.
     *
     * Must be called from the IO dispatcher (heavy mmap work inside JNI).
     * Replaces the old openDocument() + openRenderer() dual-call.
     *
     * Uses [pdfMutex] to suspend (not block) if another coroutine is already
     * inside a Pdfium call.
     */
    suspend fun openDocument(context: Context, uri: Uri) {
        pdfMutex.withLock {
            if (cachedUri == uri && docPtr != 0L) return@withLock   // already open

            closeAll()
            resolveTargetSize(context)

            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: run {
                    Log.e(TAG, "openDocument: contentResolver returned null for $uri")
                    return@withLock
                }
                // Pass the raw int fd to JNI — the C++ layer memory-maps the file,
                // so the ParcelFileDescriptor can be closed immediately afterward.
                val rawFd = pfd.fd
                docPtr = NativePdfEngine.loadDocument(rawFd)
                pfd.close()   // safe; the mmap outlives the fd

                if (docPtr == 0L) {
                    Log.e(TAG, "openDocument: Pdfium failed to load $uri")
                    return@withLock
                }
                cachedUri = uri
                Log.d(TAG, "openDocument: success — ${NativePdfEngine.getPageCount(docPtr)} pages")
            } catch (e: Exception) {
                Log.e(TAG, "openDocument: ${e.message}")
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

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /** Returns the total page count, or 0 if no document is open. */
    suspend fun getPageCount(context: Context, uri: Uri): Int {
        return pdfMutex.withLock {
            if (docPtr == 0L) 0 else NativePdfEngine.getPageCount(docPtr)
        }
    }

    /**
     * Renders [pageIndex] into a pool Bitmap and returns a [PageRender] wrapper.
     *
     * The wrapper guarantees a new object reference on every call, which is required
     * to satisfy Compose's structural equality check inside `produceState`.
     * See [PageRender] for the full explanation.
     *
     * Pool slot selection: `pageIndex % 3`.
     * The pager's 3-page window guarantees no slot conflict — see class KDoc.
     *
     * Suspends (not blocks) on the Mutex if another Pdfium call is in flight.
     */
    suspend fun renderPageToBitmap(context: Context, uri: Uri, pageIndex: Int): PageRender? {
        return pdfMutex.withLock {
            if (docPtr == 0L) {
                Log.w(TAG, "renderPageToBitmap: no document open")
                return@withLock null
            }

            val slot   = pageIndex % 3
            val bitmap = ensurePoolBitmap(slot)

            if (NativePdfEngine.renderPage(docPtr, pageIndex, bitmap)) {
                PageRender(bitmap)   // new wrapper = new reference = Compose redraws✓
            } else {
                Log.e(TAG, "renderPage failed for page $pageIndex")
                null
            }
        }
    }

    /**
     * Extracts text from [pageIndex] via Pdfium and tokenises it into sentences.
     * Replaces the old PDFBox-based implementation — same return type.
     *
     * Suspends (not blocks) on the Mutex if another Pdfium call is in flight.
     */
    suspend fun extractTextFromPage(context: Context, uri: Uri, pageIndex: Int): List<String> {
        return pdfMutex.withLock {
            if (docPtr == 0L) return@withLock listOf("Error: document not open.")
            try {
                val raw = NativePdfEngine.extractText(docPtr, pageIndex)
                raw.split(Regex("(?<=[.!?])\\s+"))
                   .map    { it.trim() }
                   .filter { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "extractTextFromPage error: ${e.message}")
                listOf("Error reading page.")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bitmap pool helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the pool Bitmap at [slot], creating or recreating it if the
     * dimensions changed (e.g. after an orientation change).
     */
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
        Log.d(TAG, "Pool slot $slot: allocated ${targetWidth}×${targetHeight} bitmap")
        return fresh
    }

    private fun recycleBitmapPool() {
        for (i in bitmapPool.indices) {
            bitmapPool[i]?.recycle()
            bitmapPool[i] = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Display helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Sets [targetWidth] / [targetHeight] from the device's real display metrics.
     * Height is capped at 75% of the screen height to match the Pager's
     * `fillMaxHeight(0.75f)` modifier, avoiding over-allocation.
     */
    @Suppress("DEPRECATION")
    private fun resolveTargetSize(context: Context) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            targetWidth  = dm.widthPixels
            targetHeight = (dm.heightPixels * 0.75f).toInt()
            Log.d(TAG, "Pool target size: ${targetWidth}×${targetHeight}")
        } catch (e: Exception) {
            Log.w(TAG, "resolveTargetSize: using defaults — ${e.message}")
        }
    }
}