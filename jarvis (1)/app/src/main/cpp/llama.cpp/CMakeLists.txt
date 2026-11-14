cmake_minimum_required(VERSION 3.14...3.28)
project("llama.cpp" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_INSTALL_VERSION "0.0.1")
set(LLAMA_BUILD_NUMBER 1)
set(LLAMA_BUILD_COMMIT "offline-a10")

# Helper macro expected by src/CMakeLists.txt
function(llama_add_compile_flags)
    if (NOT MSVC)
        add_compile_options(-Wno-unused-function -Wno-cast-qual)
    endif()
endfunction()

if (NOT TARGET ggml)
    add_subdirectory(ggml)
endif()

add_subdirectory(src)
