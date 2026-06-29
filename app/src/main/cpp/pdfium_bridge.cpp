#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <sys/mman.h>
#include <sys/stat.h>
#include <vector>
#include <algorithm>   // std::min

#include "fpdfview.h"
#include "fpdf_text.h"

#define LOG_TAG "PdfiumBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct PdfDocument {
    FPDF_DOCUMENT doc;
    void*         mappedData;
    size_t        mappedSize;
    int           pageCount;
};

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_reader_NativePdfEngine_initEngine(JNIEnv* /*env*/, jobject /*obj*/) {
    FPDF_InitLibrary();
    LOGI("Pdfium library initialized");
}

JNIEXPORT jlong JNICALL
Java_com_example_reader_NativePdfEngine_loadDocument(JNIEnv* /*env*/, jobject /*obj*/, jint fd) {
    struct stat st;
    if (fstat(static_cast<int>(fd), &st) != 0 || st.st_size <= 0) {
        LOGE("loadDocument: fstat failed or file is empty");
        return 0L;
    }
    const size_t fileSize = static_cast<size_t>(st.st_size);

    void* data = mmap(nullptr, fileSize, PROT_READ, MAP_SHARED, static_cast<int>(fd), 0);
    if (data == MAP_FAILED) {
        LOGE("loadDocument: mmap failed");
        return 0L;
    }

    // PDF pages are accessed randomly (user jumps from page 1 to 50 to 12).
    // MADV_RANDOM disables the kernel's sequential readahead, preventing it
    // from uselessly prefetching adjacent mmap pages that will never be read.
    madvise(data, fileSize, MADV_RANDOM);

    FPDF_DOCUMENT doc = FPDF_LoadMemDocument(data, static_cast<int>(fileSize), nullptr);
    if (!doc) {
        LOGE("loadDocument: FPDF_LoadMemDocument failed, error=%lu", FPDF_GetLastError());
        munmap(data, fileSize);
        return 0L;
    }

    const int pages = FPDF_GetPageCount(doc);
    auto* pdfDoc = new PdfDocument{ doc, data, fileSize, pages };
    LOGI("loadDocument: success — %d pages", pages);
    return reinterpret_cast<jlong>(pdfDoc);
}

JNIEXPORT jint JNICALL
Java_com_example_reader_NativePdfEngine_getPageCount(JNIEnv* /*env*/, jobject /*obj*/, jlong docPtr) {
    if (!docPtr) return 0;
    return reinterpret_cast<PdfDocument*>(docPtr)->pageCount;
}

JNIEXPORT jboolean JNICALL
Java_com_example_reader_NativePdfEngine_renderPage(
        JNIEnv* env, jobject /*obj*/,
        jlong docPtr, jint pageIndex, jobject bitmap) {

    if (!docPtr) { LOGE("renderPage: null docPtr"); return JNI_FALSE; }
    auto* pdfDoc = reinterpret_cast<PdfDocument*>(docPtr);

    // ── 1. Bounds-check before touching anything ──────────────────────────
    if (pageIndex < 0 || pageIndex >= pdfDoc->pageCount) {
        LOGE("renderPage: pageIndex %d out of range [0, %d)", pageIndex, pdfDoc->pageCount);
        return JNI_FALSE;
    }

    // ── 2. Load PDF page FIRST — fail fast before locking the Bitmap ──────
    // Original order was: lock → load page → if fail, unlock.
    // If FPDF_LoadPage fails under the original order, the Bitmap pixel
    // buffer stays permanently locked in native memory until GC finalises it.
    FPDF_PAGE page = FPDF_LoadPage(pdfDoc->doc, pageIndex);
    if (!page) {
        LOGE("renderPage: FPDF_LoadPage failed for page %d", pageIndex);
        return JNI_FALSE;
    }

    // ── 3. Compute scale-to-fit transform ────────────────────────────────
    const double pageW  = FPDF_GetPageWidth(page);
    const double pageH  = FPDF_GetPageHeight(page);

    // ── 4. Lock Android Bitmap (only after we know the page is valid) ─────
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("renderPage: AndroidBitmap_getInfo failed");
        FPDF_ClosePage(page);
        return JNI_FALSE;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("renderPage: AndroidBitmap_lockPixels failed");
        FPDF_ClosePage(page);
        return JNI_FALSE;
    }

    const float scaleX = static_cast<float>(info.width)  / static_cast<float>(pageW);
    const float scaleY = static_cast<float>(info.height) / static_cast<float>(pageH);
    const float scale  = std::min(scaleX, scaleY);

    const int renderW = static_cast<int>(pageW * scale);
    const int renderH = static_cast<int>(pageH * scale);
    const int offsetX = (static_cast<int>(info.width)  - renderW) / 2;
    const int offsetY = (static_cast<int>(info.height) - renderH) / 2;

    // ── 5. Wrap the Android pixel buffer — zero allocation ────────────────
    // FPDFBitmap_BGRA matches Android ARGB_8888 on little-endian ARM exactly.
    FPDF_BITMAP fpdfBitmap = FPDFBitmap_CreateEx(
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        FPDFBitmap_BGRA,
        pixels,
        static_cast<int>(info.stride)
    );

    // ── 6. White canvas so letterbox padding isn't transparent (= black) ──
    FPDFBitmap_FillRect(fpdfBitmap, 0, 0,
                        static_cast<int>(info.width),
                        static_cast<int>(info.height),
                        0xFFFFFFFF);

    // ── 7. Render ─────────────────────────────────────────────────────────
    FPDF_RenderPageBitmap(
        fpdfBitmap, page,
        offsetX, offsetY, renderW, renderH,
        0,
        FPDF_ANNOT | FPDF_LCD_TEXT
    );

    // ── 8. Cleanup ────────────────────────────────────────────────────────
    FPDFBitmap_Destroy(fpdfBitmap);   // releases wrapper, NOT pixels
    FPDF_ClosePage(page);
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_reader_NativePdfEngine_extractText(
        JNIEnv* env, jobject /*obj*/, jlong docPtr, jint pageIndex) {

    if (!docPtr) return env->NewStringUTF("");
    auto* pdfDoc = reinterpret_cast<PdfDocument*>(docPtr);

    if (pageIndex < 0 || pageIndex >= pdfDoc->pageCount) {
        LOGE("extractText: pageIndex %d out of range [0, %d)", pageIndex, pdfDoc->pageCount);
        return env->NewStringUTF("");
    }

    FPDF_PAGE page = FPDF_LoadPage(pdfDoc->doc, pageIndex);
    if (!page) return env->NewStringUTF("");

    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    if (!textPage) {
        FPDF_ClosePage(page);
        return env->NewStringUTF("");
    }

    // ── Geometry filter — Strategy 1 ─────────────────────────────────────
    // PDF user-space coordinate system: Y=0 at page bottom, Y=pageHeight at top.
    // We exclude the top 8% (running header) and bottom 8% (page number / footer).
    //
    //  pageHeight ──┐
    //               │  [topExclusion = 0.92 × pageHeight]   ← header zone above this
    //               │         SAFE BODY TEXT ZONE
    //               │  [bottomExclusion = 0.08 × pageHeight] ← footer zone below this
    //  0 ───────────┘
    //
    // A character is safe if and only if:
    //   charTop    < topExclusion     (its top edge is below the header line)
    //   charBottom > bottomExclusion  (its bottom edge is above the footer line)
    //
    // FPDFText_GetCharBox signature from fpdf_text.h (confirmed):
    //   FPDFText_GetCharBox(text_page, index, &left, &right, &bottom, &top)
    // ─────────────────────────────────────────────────────────────────────

    const double pageHeight      = FPDF_GetPageHeight(page);
    const double bottomExclusion = pageHeight * 0.08;
    const double topExclusion    = pageHeight * 0.92;

    const int charCount = FPDFText_CountChars(textPage);

    // Reserve at charCount capacity; surrogate pairs can push past it but are rare.
    std::vector<jchar> buf;
    buf.reserve(static_cast<size_t>(charCount));

    for (int i = 0; i < charCount; ++i) {
        double left = 0, right = 0, bottom = 0, top = 0;

        // If the bounding box query fails (generated whitespace/newline chars
        // often have no box), include the character unconditionally so we
        // don't silently drop spaces inside sentences.
        const bool hasBox = FPDFText_GetCharBox(textPage, i, &left, &right, &bottom, &top)
                            != 0;

        if (hasBox) {
            // Reject if the character touches the header or footer exclusion zones.
            if (top >= topExclusion || bottom <= bottomExclusion) {
                continue;
            }
        }

        const unsigned int code = FPDFText_GetUnicode(textPage, i);
        if (code == 0) continue;   // unmapped / null — skip

        if (code < 0x10000u) {
            buf.push_back(static_cast<jchar>(code));
        } else {
            // Encode as a UTF-16 surrogate pair (chars outside the BMP).
            const unsigned int cp = code - 0x10000u;
            buf.push_back(static_cast<jchar>(0xD800u | (cp >> 10)));
            buf.push_back(static_cast<jchar>(0xDC00u | (cp & 0x3FFu)));
        }
    }

    FPDFText_ClosePage(textPage);
    FPDF_ClosePage(page);

    if (buf.empty()) return env->NewStringUTF("");
    return env->NewString(buf.data(), static_cast<jsize>(buf.size()));
}

JNIEXPORT void JNICALL
Java_com_example_reader_NativePdfEngine_closeDocument(
        JNIEnv* /*env*/, jobject /*obj*/, jlong docPtr) {

    if (!docPtr) return;
    auto* pdfDoc = reinterpret_cast<PdfDocument*>(docPtr);
    FPDF_CloseDocument(pdfDoc->doc);

    // Signal the OS to release the physical pages backing this mmap immediately
    // rather than waiting for the kernel's own page-cache eviction policy.
    // Without this, a 50 MB PDF's pages can sit in RAM for seconds after close,
    // blocking the next document's allocation.
    madvise(pdfDoc->mappedData, pdfDoc->mappedSize, MADV_DONTNEED);
    munmap(pdfDoc->mappedData, pdfDoc->mappedSize);
    delete pdfDoc;
    LOGI("closeDocument: released");
}

} // extern "C"
