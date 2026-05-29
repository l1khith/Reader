package com.example.reader

import android.graphics.Bitmap

/**
 * NativePdfEngine
 *
 * Kotlin singleton that loads `libpdfium_bridge.so` and exposes type-safe
 * declarations for every JNI function in pdfium_bridge.cpp.
 *
 * Lifecycle:
 *   1. Call [initEngine] once in Application/Activity.onCreate().
 *   2. Use [loadDocument] to open a PDF — returns an opaque Long handle.
 *   3. Call [renderPage] / [extractText] / [getPageCount] with that handle.
 *   4. Call [closeDocument] when done to release native memory and the mmap.
 *
 * Thread-safety: all synchronization is enforced by PdfHelper's @Synchronized
 * wrappers. Do not call these external functions concurrently on the same docPtr.
 */
object NativePdfEngine {

    init {
        System.loadLibrary("pdfium_bridge")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Engine lifecycle
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initializes the Pdfium library (FPDF_InitLibrary).
     * Must be called **once** before any document is loaded.
     */
    external fun initEngine()

    // ─────────────────────────────────────────────────────────────────────
    // Document lifecycle
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Opens a PDF from a raw OS file descriptor (e.g. `ParcelFileDescriptor.fd`).
     *
     * Internally the C++ side memory-maps the file, so the FD can safely be
     * closed **immediately** after this call returns.
     *
     * @param fd raw file descriptor (NOT a ParcelFileDescriptor; use `.fd`)
     * @return opaque Long handle to the native PdfDocument, or **0** on failure.
     */
    external fun loadDocument(fd: Int): Long

    /** Returns the total page count for the given document handle. */
    external fun getPageCount(docPtr: Long): Int

    // ─────────────────────────────────────────────────────────────────────
    // Page operations
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Renders [pageIndex] **directly into** [bitmap]'s pixel buffer.
     *
     * Zero-copy: Pdfium writes into the Bitmap's native memory via
     * `FPDFBitmap_CreateEx` — no intermediate buffer is allocated.
     *
     * Requirements for [bitmap]:
     *  - `Config == ARGB_8888` (matches Pdfium's BGRA on little-endian ARM)
     *  - Must be **mutable** (the usual `Bitmap.createBitmap(…)` default)
     *
     * @return `true` on success.
     */
    external fun renderPage(docPtr: Long, pageIndex: Int, bitmap: Bitmap): Boolean

    /**
     * Extracts all text from [pageIndex] as a UTF-16 Java String.
     * Returns an empty string if the page has no extractable text (e.g. scanned).
     * The caller (PdfHelper) is responsible for sentence tokenisation.
     */
    external fun extractText(docPtr: Long, pageIndex: Int): String

    /**
     * Closes the document and releases all native resources (FPDF_DOCUMENT + mmap).
     * The [docPtr] handle is **invalid** after this call.
     */
    external fun closeDocument(docPtr: Long)
}
