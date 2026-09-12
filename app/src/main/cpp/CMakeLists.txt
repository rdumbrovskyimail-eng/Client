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
        -ffast-math
        -fno-rtti
        -fexceptions
    )
    # Выравнивание страниц под стандарт Android 15/16 Baklava (16 KB Page Alignment)
    add_link_options(
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
    )
endif()

# Пути инклюдов относительно директории CMakeLists.txt
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/audio
    ${CMAKE_CURRENT_SOURCE_DIR}/vad
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

# Точный путь к файлу реализации JNI
add_library(client_core SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/NativeBridge.cpp
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(android-lib android)

target_link_libraries(client_core
    ${log-lib}
    ${aaudio-lib}
    ${android-lib}
)