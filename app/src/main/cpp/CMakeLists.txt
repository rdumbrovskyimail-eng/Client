cmake_minimum_required(VERSION 3.22.1)

project("client_core" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

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

# Common native toolchain contract for every ABI built by this target.
# In particular, AAudioEngine.cpp uses C++ exceptions on all supported ABIs.
target_compile_options(client_core PRIVATE
    -O3
    -fvisibility=hidden
    -fno-math-errno
    -fno-trapping-math
    -fno-rtti
    -fexceptions
    -flto
    -fno-omit-frame-pointer
)

# Keep ELF LOAD segments explicitly 16-KiB aligned for every ABI.
target_link_options(client_core PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
    -flto
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(android-lib android)

target_link_libraries(client_core
    ${log-lib}
    ${aaudio-lib}
    ${android-lib}
)