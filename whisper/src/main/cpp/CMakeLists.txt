cmake_minimum_required(VERSION 3.22)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp is fetched by scripts/fetch-deps.sh (pinned tag) into third_party/
set(WHISPER_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp)
if (NOT EXISTS ${WHISPER_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp not found in ${WHISPER_SRC}. Run scripts/fetch-deps.sh")
endif()

# Static, CPU-only build. ggml symbols are hidden inside libwhisper_jni.so
# so they never clash with the separate copy of ggml inside libllama_jni.so.
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL             OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2             OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS    OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Cortex-A55/A75 and newer (practically every arm64 phone since 2018)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${WHISPER_SRC} whisper-build EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper)
if (ANDROID)
    target_link_libraries(whisper_jni PRIVATE android log)
    target_link_options(whisper_jni PRIVATE
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384
        -Wl,--gc-sections)
endif()
target_compile_options(whisper_jni PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
