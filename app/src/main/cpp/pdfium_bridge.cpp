/**
 * pdfium_bridge.cpp
 *
 * JNI bridge between Kotlin (NativePdfEngine) and the pre-built Pdfium C library.
 *
 * Design goals:
 *  • Zero-copy rendering  — Kotlin allocates the Bitmap; C++ renders directly
 *    into its pixel buffer via AndroidBitmap_lockPixels + FPDFBitmap_CreateEx.
 *  • mmap-backed loading  — the PDF file is memory-mapped rather than copied
 *    into a heap buffer, so the OS can page it in/out as needed.
 *  • Thread-safe handle   — every call goes through a PdfDocument* that owns
 *    both the FPDF_DOCUMENT and the mmap region.
 *
 * JNI naming convention: Java_<package_underscores>_<ClassName>_<methodName>
 * Package: com.example.reader  →  Java_com_example_reader_NativePdfEngine_*
 */

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>   // std::min

// Pdfium public headers (placed in app/src/main/cpp/include/ by the developer)
#include "fpdfview.h"
#include "fpdf_text.h"

#define LOG_TAG "PdfiumBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Internal document wrapper
// Bundles the Pdfium handle with the mmap region so both can be released in
// closeDocument() without the caller tracking separate raw pointers.
// ─────────────────────────────────────────────────────────────────────────────
struct PdfDocument {
    FPDF_DOCUMENT doc;
    void*         mappedData;   // from mmap()
    size_t        mappedSize;
};

extern "C" {

// ─────────────────────────────────────────────────────────────────────────────
// initEngine
// Must be called once before any other function (e.g. in Application.onCreate).
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_example_reader_NativePdfEngine_initEngine(JNIEnv* /*env*/, jobject /*obj*/) {
    FPDF_InitLibrary();
    LOGI("Pdfium library initialized");
}

// ─────────────────────────────────────────────────────────────────────────────
// loadDocument
// Accepts a raw int file-descriptor from ParcelFileDescriptor.fd.
// Memory-maps the file (the FD can be closed after this returns).
// Returns an opaque jlong (reinterpret_cast<jlong>(PdfDocument*)), or 0 on error.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_example_reader_NativePdfEngine_loadDocument(JNIEnv* /*env*/, jobject /*obj*/, jint fd) {
    struct stat st;
    if (fstat(static_cast<int>(fd), &st) != 0 || st.st_size <= 0) {
        LOGE("loadDocument: fstat failed or file is empty");
        return 0L;
    }
    const size_t fileSize = static_cast<size_t>(st.st_size);

    // MAP_SHARED + PROT_READ: the OS pages the PDF in on demand and can evict
    // cold pages under memory pressure — far cheaper than a full heap copy.
    void* data = mmap(nullptr, fileSize, PROT_READ, MAP_SHARED, static_cast<int>(fd), 0);
    if (data == MAP_FAILED) {
        LOGE("loadDocument: mmap failed");
        return 0L;
    }

    FPDF_DOCUMENT doc = FPDF_LoadMemDocument(data, static_cast<int>(fileSize), nullptr);
    if (!doc) {
        LOGE("loadDocument: FPDF_LoadMemDocument failed, error=%lu", FPDF_GetLastError());
        munmap(data, fileSize);
        return 0L;
    }

    auto* pdfDoc   = new PdfDocument{ doc, data, fileSize };
    LOGI("loadDocument: success — %d pages", FPDF_GetPageCount(doc));
    return reinterpret_cast<jlong>(pdfDoc);
}

// ─────────────────────────────────────────────────────────────────────────────
// getPageCount
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_example_reader_NativePdfEngine_getPageCount(JNIEnv* /*env*/, jobject /*obj*/, jlong docPtr) {
    if (!docPtr) return 0;
    return FPDF_GetPageCount(reinterpret_cast<PdfDocument*>(docPtr)->doc);
}

// ─────────────────────────────────────────────────────────────────────────────
// renderPage
//
// Zero-copy rendering pipeline:
//   Kotlin Bitmap (ARGB_8888, mutable)
//       │  AndroidBitmap_lockPixels  →  void* pixels
//       │  FPDFBitmap_CreateEx       →  wraps pixels (no alloc)
//       │  FPDFBitmap_FillRect       →  white background
//       │  FPDF_RenderPageBitmap     →  Pdfium draws into pixels
//       │  FPDFBitmap_Destroy        →  releases wrapper (NOT pixels)
//       │  AndroidBitmap_unlockPixels
//       └─ returns JNI_TRUE
//
// The Kotlin side reuses the same Bitmap object on every navigation event
// (Bitmap pool in PdfHelper) — this function never allocates a Bitmap.
//
// Android ARGB_8888 on little-endian ARM stores bytes as B,G,R,A in memory
// which matches Pdfium's FPDFBitmap_BGRA format exactly.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_example_reader_NativePdfEngine_renderPage(
        JNIEnv* env, jobject /*obj*/,
        jlong docPtr, jint pageIndex, jobject bitmap) {

    if (!docPtr) { LOGE("renderPage: null docPtr"); return JNI_FALSE; }
    auto* pdfDoc = reinterpret_cast<PdfDocument*>(docPtr);

    // ── 1. Lock Android Bitmap ────────────────────────────────────────────
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("renderPage: AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("renderPage: AndroidBitmap_lockPixels failed");
        return JNI_FALSE;
    }

    // ── 2. Open PDF page ──────────────────────────────────────────────────
    FPDF_PAGE page = FPDF_LoadPage(pdfDoc->doc, static_cast<int>(pageIndex));
    if (!page) {
        LOGE("renderPage: FPDF_LoadPage failed for page %d", pageIndex);
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }

    // ── 3. Scale-to-fit transform (preserves aspect ratio, centres content) 
    const double pageW = FPDF_GetPageWidth(page);
    const double pageH = FPDF_GetPageHeight(page);
    const float  scaleX = static_cast<float>(info.width)  / static_cast<float>(pageW);
    const float  scaleY = static_cast<float>(info.height) / static_cast<float>(pageH);
    const float  scale  = std::min(scaleX, scaleY);

    const int renderW  = static_cast<int>(pageW * scale);
    const int renderH  = static_cast<int>(pageH * scale);
    const int offsetX  = (static_cast<int>(info.width)  - renderW) / 2;
    const int offsetY  = (static_cast<int>(info.height) - renderH) / 2;

    // ── 4. Create a Pdfium bitmap that wraps the Android pixel buffer ─────
    FPDF_BITMAP fpdfBitmap = FPDFBitmap_CreateEx(
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        FPDFBitmap_BGRA,   // matches Android ARGB_8888 on little-endian ARM
        pixels,
        static_cast<int>(info.stride)
    );

    // ── 5. White canvas (unset PDF backgrounds would otherwise be transparent
    //       = black on a dark app surface)
    FPDFBitmap_FillRect(fpdfBitmap, 0, 0,
                        static_cast<int>(info.width),
                        static_cast<int>(info.height),
                        0xFFFFFFFF);

    // ── 6. Render ─────────────────────────────────────────────────────────
    FPDF_RenderPageBitmap(
        fpdfBitmap, page,
        offsetX, offsetY, renderW, renderH,
        0,                          // rotation (0 = none)
        FPDF_ANNOT | FPDF_LCD_TEXT  // render annotations + ClearType-style text
    );

    // ── 7. Cleanup (FPDFBitmap_Destroy does NOT free `pixels` with CreateEx) 
    FPDFBitmap_Destroy(fpdfBitmap);
    FPDF_ClosePage(page);
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

// ─────────────────────────────────────────────────────────────────────────────
// extractText
// Uses the Pdfium text engine to pull all characters from a page.
// Returns a Java String (UTF-16) which Kotlin splits into sentences.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_example_reader_NativePdfEngine_extractText(
        JNIEnv* env, jobject /*obj*/, jlong docPtr, jint pageIndex) {

    if (!docPtr) return env->NewStringUTF("");
    auto* pdfDoc = reinterpret_cast<PdfDocument*>(docPtr);

    FPDF_PAGE page = FPDF_LoadPage(pdfDoc->doc, static_cast<int>(pageIndex));
    if (!page) return env->NewStringUTF("");

    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    if (!textPage) {
        FPDF_ClosePage(page);
        return env->NewStringUTF("");
    }

    const int charCount = FPDFText_CountChars(textPage);
    jstring result;
    if (charCount <= 0) {
        result = env->NewStringUTF("");
    } else {
        // FPDFText_GetText produces UTF-16LE in unsigned short[]
        std::vector<unsigned short> buf(charCount + 1, 0);
        FPDFText_GetText(textPage, 0, charCount, buf.data());
        // JNI NewString takes UTF-16 directly — no conversion needed
        result = env->NewString(buf.data(), charCount);
    }

    FPDFText_ClosePage(textPage);
    FPDF_ClosePage(page);
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// closeDocument
// Frees the Pdfium document handle and unmaps the backing file.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_example_reader_NativePdfEngine_closeDocument(
        JNIEnv* /*env*/, jobject /*obj*/, jlong docPtr) {

    if (!docPtr) return;
    auto* pdfDoc = reinterpret_cast<PdfDocument*>(docPtr);
    FPDF_CloseDocument(pdfDoc->doc);
    munmap(pdfDoc->mappedData, pdfDoc->mappedSize);
    delete pdfDoc;
    LOGI("closeDocument: released");
}

} // extern "C"
