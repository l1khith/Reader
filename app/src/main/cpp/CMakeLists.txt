cmake_minimum_required(VERSION 3.22.1)
project("pdfium_bridge" CXX)

# ─────────────────────────────────────────────────────────────────────────────
# Pre-built libpdfium.so (downloaded from bblanchon/pdfium-binaries)
# Placed by the developer in app/src/main/jniLibs/<ABI>/libpdfium.so
# ─────────────────────────────────────────────────────────────────────────────
add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
    IMPORTED_LOCATION
        "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpdfium.so"
)

# ─────────────────────────────────────────────────────────────────────────────
# Our JNI bridge
# ─────────────────────────────────────────────────────────────────────────────
add_library(pdfium_bridge SHARED
    pdfium_bridge.cpp
)

# Pdfium public headers live in cpp/include/
target_include_directories(pdfium_bridge PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)

# System libraries
find_library(log-lib      log)        # __android_log_print
find_library(jnigraphics  jnigraphics) # AndroidBitmap_lockPixels

target_link_libraries(pdfium_bridge
    pdfium          # pre-built Pdfium
    ${jnigraphics}  # direct Bitmap pixel access
    ${log-lib}
)

# ─────────────────────────────────────────────────────────────────────────────
# 16 KB page-size alignment  (required by Google Play for Android 15+ devices)
# Aligns LOAD segments in libpdfium_bridge.so at 16 384-byte boundaries.
# https://developer.android.com/guide/practices/page-sizes
# ─────────────────────────────────────────────────────────────────────────────
target_link_options(pdfium_bridge PRIVATE
    "LINKER:-z,max-page-size=16384"
)

# Use the shared C++ STL so Pdfium and our bridge share one runtime
set_target_properties(pdfium_bridge PROPERTIES
    ANDROID_STL "c++_shared"
)
