cmake_minimum_required(VERSION 3.22.1)

project("client_core" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Оптимизации компилятора под Snapdragon 8 Gen 2 (SM8550-AC / Cortex-X3 / A715)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_compile_options(
        -O3
        -march=armv8.2-a+fp16+dotprod
        -fvisibility=hidden
        -fno-math-errno
        -fno-trapping-math
        -fassociative-math
        -fno-rtti
        -fexceptions
        -flto
    )
    # Выравнивание страниц под стандарт Android 15/16 Baklava (16 KB Page Alignment)
    add_link_options(
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
        -flto
    )
endif()

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/audio
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

add_library(client_core SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/NativeBridge.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/audio/AAudioEngine.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp/FastFft.cpp
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(android-lib android)

target_link_libraries(client_core
    ${log-lib}
    ${aaudio-lib}
    ${android-lib}
)