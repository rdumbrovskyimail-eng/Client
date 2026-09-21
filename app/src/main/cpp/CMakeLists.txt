cmake_minimum_required(VERSION 3.22.1)

project("client_core" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Generic arm64-v8a build: do not emit CPU-specific instructions that the
# manifest/ABI would otherwise advertise as universally supported.
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_compile_options(
        -O3
        -fvisibility=hidden
        -fno-math-errno
        -fno-trapping-math
        -fassociative-math
        -fno-rtti
        -fexceptions
        -flto
        -fno-omit-frame-pointer # Источник 58: сохранение указателя кадра для информативных краш-логов
    )
    # AUD-061:
    # Explicit 16 KB ELF segment alignment. NDK r28+ also defaults to
    # 16 KB-compatible alignment, but keep the flags explicit so this
    # target remains correct if the toolchain is changed later.
    add_link_options(
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
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