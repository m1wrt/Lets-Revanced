# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

project("lets")

set(CMAKE_BUILD_TYPE Release)

# Habilitar C++20 para llama.cpp
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimizaciones para Android
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENMP OFF)
    set(GGML_USE_CPU ON)
    # Forzar optimización agresiva (sin native que falla en NDK)
    add_compile_options(-O3 -march=armv8-a+simd)
endif()

# Forzar optimización si no está definida
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Integración de llama.cpp vía FetchContent
include(FetchContent)
FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        master
        GIT_SHALLOW    ON
)
# Solo compilar lo necesario para la inferencia
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_XNNPACK ON CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        android
        log)
