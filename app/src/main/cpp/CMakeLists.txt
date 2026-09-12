cmake_minimum_required(VERSION 3.22.1)

project("client_core" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Оптимизации компилятора под Snapdragon 8 Gen 2 (SM8550-AC / Cortex-A715 / Cortex-X3)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_compile_options(
        -O3
        -march=armv8.2-a+fp16+dotprod
        -fvisibility=hidden
        -flto
        -ffast-math
        -fno-rtti
        -fexceptions
    )
    # Выравнивание страниц под стандарт Android 15/16 Baklava (16 KB Page Alignment)
    add_link_options(
        -flto
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
    )
endif()

include_directories(
    src/main/cpp
    src/main/cpp/audio
    src/main/cpp/vad
    src/main/cpp/dsp
    src/main/cpp/jni
)

add_library(client_core SHARED
    src/main/cpp/jni/NativeBridge.cpp
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(android-lib android)

target_link_libraries(client_core
    ${log-lib}
    ${aaudio-lib}
    ${android-lib}
)