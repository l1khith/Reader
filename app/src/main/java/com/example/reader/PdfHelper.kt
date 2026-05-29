package com.example.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

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
 * All public functions that touch [docPtr] or the pool are @Synchronized.
 * Pdfium's FPDF_Document is not thread-safe, so we serialize all calls.
 */
object PdfHelper {

    private const val TAG = "PdfHelper"

    // ── Pdfium document handle (0 = closed) ──────────────────────────────
    private var docPtr: Long = 0L
    private var cachedUri: Uri? = null

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
     */
    @Synchronized
    fun openDocument(context: Context, uri: Uri) {
        if (cachedUri == uri && docPtr != 0L) return   // already open

        closeAll()
        resolveTargetSize(context)

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: run {
                Log.e(TAG, "openDocument: contentResolver returned null for $uri")
                return
            }
            // Pass the raw int fd to JNI — the C++ layer memory-maps the file,
            // so the ParcelFileDescriptor can be closed immediately afterward.
            val rawFd = pfd.fd
            docPtr = NativePdfEngine.loadDocument(rawFd)
            pfd.close()   // safe; the mmap outlives the fd

            if (docPtr == 0L) {
                Log.e(TAG, "openDocument: Pdfium failed to load $uri")
                return
            }
            cachedUri = uri
            Log.d(TAG, "openDocument: success — ${NativePdfEngine.getPageCount(docPtr)} pages")
        } catch (e: Exception) {
            Log.e(TAG, "openDocument: ${e.message}")
        }
    }

    /**
     * No-op — kept so [ReaderScreen]'s LaunchedEffect needs no changes.
     * Pdfium handles both rendering and text extraction in one engine.
     */
    fun openRenderer(context: Context, uri: Uri) = Unit

    fun closeDocument() = closeAll()
    fun closeRenderer() = Unit   // no-op — single engine

    private fun closeAll() {
        if (docPtr != 0L) {
            NativePdfEngine.closeDocument(docPtr)
            docPtr = 0L
        }
        cachedUri = null
        recycleBitmapPool()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API  (surface identical to the old PdfHelper — ReaderScreen
    //              requires zero changes)
    // ─────────────────────────────────────────────────────────────────────

    /** Returns the total page count, or 0 if no document is open. */
    @Synchronized
    fun getPageCount(context: Context, uri: Uri): Int {
        if (docPtr == 0L) return 0
        return NativePdfEngine.getPageCount(docPtr)
    }

    /**
     * Renders [pageIndex] into a pool Bitmap and returns it.
     *
     * Pool slot selection: `pageIndex % 3`.
     * The pager's 3-page window guarantees no slot conflict — see class KDoc.
     *
     * The C++ layer renders directly into the Bitmap's pixel buffer (zero copy).
     * The returned Bitmap is the pool Bitmap itself; the caller must NOT recycle it.
     */
    @Synchronized
    fun renderPageToBitmap(context: Context, uri: Uri, pageIndex: Int): Bitmap? {
        if (docPtr == 0L) {
            Log.w(TAG, "renderPageToBitmap: no document open")
            return null
        }

        val slot   = pageIndex % 3
        val bitmap = ensurePoolBitmap(slot)

        return if (NativePdfEngine.renderPage(docPtr, pageIndex, bitmap)) {
            bitmap
        } else {
            Log.e(TAG, "renderPage failed for page $pageIndex")
            null
        }
    }

    /**
     * Extracts text from [pageIndex] via Pdfium and tokenises it into sentences.
     * Replaces the old PDFBox-based implementation — same return type.
     */
    @Synchronized
    fun extractTextFromPage(context: Context, uri: Uri, pageIndex: Int): List<String> {
        if (docPtr == 0L) return listOf("Error: document not open.")
        return try {
            val raw = NativePdfEngine.extractText(docPtr, pageIndex)
            raw.split(Regex("(?<=[.!?])\\s+"))
               .map    { it.trim() }
               .filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "extractTextFromPage error: ${e.message}")
            listOf("Error reading page.")
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