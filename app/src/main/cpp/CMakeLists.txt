cmake_minimum_required(VERSION 3.22.1)
project("jarvis-native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Hide internal C/C++ symbols across static libraries to prevent duplicate symbol collisions
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# Optimize for both 32-bit (armeabi-v7a) and 64-bit (arm64-v8a) on Samsung Galaxy A10 (Exynos 7884)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-O3 -DNDEBUG -ffast-math -funroll-loops -fvisibility=hidden)
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    add_compile_options(-O3 -DNDEBUG -ffast-math -funroll-loops -mfpu=neon -fvisibility=hidden)
endif()

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

add_library(jarvis-native SHARED
    native-lib.cpp
)

find_library(log-lib log)
find_library(android-lib android)

set(JARVIS_LINK_LIBS ${log-lib} ${android-lib})

# ==============================================================================
# 1. LLAMA.CPP + SHARED GGML BACKEND
# ==============================================================================
if (EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(STATUS "[JARVIS-NDK] Found llama.cpp at ${LLAMA_DIR} — compiling GGUF Brain + shared ggml")

    set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static llama/ggml libs" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable llama tests" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable llama examples" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable llama server" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "Disable CURL for 100% offline build" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on Android NDK" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile on Android" FORCE)

    add_subdirectory("${LLAMA_DIR}" build-llama EXCLUDE_FROM_ALL)

    target_compile_definitions(jarvis-native PRIVATE HAVE_LLAMA_CPP=1)
    target_include_directories(jarvis-native PRIVATE
        "${LLAMA_DIR}"
        "${LLAMA_DIR}/include"
        "${LLAMA_DIR}/ggml/include"
        "${LLAMA_DIR}/common"
    )

    if (TARGET ggml)
        list(APPEND JARVIS_LINK_LIBS llama ggml)
    else()
        list(APPEND JARVIS_LINK_LIBS llama)
    endif()
else()
    message(WARNING "[JARVIS-NDK] llama.cpp/CMakeLists.txt not yet uploaded. Using JNI C++ fallback for Llama.")
    target_compile_definitions(jarvis-native PRIVATE HAVE_LLAMA_CPP=0)
endif()

# ==============================================================================
# 2. WHISPER.CPP (SHARING GGML OBJECTS TO PREVENT DUPLICATE SYMBOL CRASHES)
# ==============================================================================
set(WHISPER_SRC_SIZE 0)
if (EXISTS "${WHISPER_DIR}/src/whisper.cpp")
    file(SIZE "${WHISPER_DIR}/src/whisper.cpp" WHISPER_SRC_SIZE)
endif()

if (WHISPER_SRC_SIZE GREATER 1024)
    message(STATUS "[JARVIS-NDK] Found real whisper.cpp (${WHISPER_SRC_SIZE} bytes) at ${WHISPER_DIR} — compiling Whisper Ear")

    if (TARGET ggml AND EXISTS "${WHISPER_DIR}/src/whisper.cpp")
        # Reuse the already-compiled ggml targets from llama.cpp so CMake and lld never collide on duplicate ggml symbols
        message(STATUS "[JARVIS-NDK] Sharing compiled ggml targets between llama.cpp and whisper.cpp")
        add_library(whisper-isolated STATIC
            "${WHISPER_DIR}/src/whisper.cpp"
        )
        target_compile_definitions(whisper-isolated PRIVATE
            WHISPER_VERSION="1.7.4"
            _GNU_SOURCE
        )
        target_compile_options(whisper-isolated PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_include_directories(whisper-isolated PUBLIC
            "${WHISPER_DIR}/include"
            "${WHISPER_DIR}/src"
            "${LLAMA_DIR}/ggml/include"
            "${LLAMA_DIR}/ggml/src"
        )
        target_link_libraries(whisper-isolated PRIVATE ggml)
        if (TARGET ggml-base)
            target_link_libraries(whisper-isolated PRIVATE ggml-base)
        endif()
        if (TARGET ggml-cpu)
            target_link_libraries(whisper-isolated PRIVATE ggml-cpu)
        endif()

        target_compile_definitions(jarvis-native PRIVATE HAVE_WHISPER_CPP=1)
        target_include_directories(jarvis-native PRIVATE
            "${WHISPER_DIR}/include"
            "${WHISPER_DIR}/src"
        )
        list(APPEND JARVIS_LINK_LIBS whisper-isolated)
    elseif (EXISTS "${WHISPER_DIR}/CMakeLists.txt")
        set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static whisper libs" FORCE)
        set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Disable whisper tests" FORCE)
        set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Disable whisper examples" FORCE)
        set(WHISPER_BUILD_SERVER OFF CACHE BOOL "Disable whisper server" FORCE)
        set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on Android NDK" FORCE)

        add_subdirectory("${WHISPER_DIR}" build-whisper EXCLUDE_FROM_ALL)

        target_compile_definitions(jarvis-native PRIVATE HAVE_WHISPER_CPP=1)
        target_include_directories(jarvis-native PRIVATE
            "${WHISPER_DIR}"
            "${WHISPER_DIR}/include"
            "${WHISPER_DIR}/ggml/include"
        )
        list(APPEND JARVIS_LINK_LIBS whisper)
    else()
        target_compile_definitions(jarvis-native PRIVATE HAVE_WHISPER_CPP=0)
    endif()
else()
    message(WARNING "[JARVIS-NDK] whisper.cpp/src/whisper.cpp is a placeholder (${WHISPER_SRC_SIZE} bytes <= 1024). Using JNI C++ fallback until real whisper.cpp is uploaded.")
    target_compile_definitions(jarvis-native PRIVATE HAVE_WHISPER_CPP=0)
endif()

target_link_options(jarvis-native PRIVATE "-Wl,--exclude-libs,ALL")
target_link_libraries(jarvis-native PRIVATE ${JARVIS_LINK_LIBS})
