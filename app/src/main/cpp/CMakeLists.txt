cmake_minimum_required(VERSION 3.22.1)
set(CMAKE_POLICY_VERSION_MINIMUM "3.5" CACHE STRING "" FORCE)
project(firewisp_slimt_jni CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WITH_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(BUILD_JNI OFF CACHE BOOL "" FORCE)
set(SLIMT_PACKAGE OFF CACHE BOOL "" FORCE)
set(USE_BUILTIN_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64" OR ANDROID_ABI STREQUAL "arm64-v8a")
    set(USE_NEON ON CACHE BOOL "" FORCE)
else()
    set(USE_NEON OFF CACHE BOOL "" FORCE)
endif()

get_filename_component(SLIMT_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/slimt" ABSOLUTE)
set(SLIMT_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/slimt_build")

# slimt/slimt/CMakeLists.txt references ${CMAKE_SOURCE_DIR} and ${CMAKE_BINARY_DIR};
# propagate the real slimt root & build dir so headers (including generated Export.hh) resolve.
include_directories("${SLIMT_ROOT_DIR}" "${SLIMT_BINARY_DIR}")
add_subdirectory("${SLIMT_ROOT_DIR}" "${SLIMT_BINARY_DIR}" EXCLUDE_FROM_ALL)

find_library(ANDROID_LOG_LIB log)

add_library(slimt_jni SHARED slimt_jni.cpp)
target_include_directories(slimt_jni PRIVATE "${SLIMT_ROOT_DIR}" "${SLIMT_BINARY_DIR}")
target_compile_options(slimt_jni PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)
target_link_options(slimt_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
)
target_link_libraries(slimt_jni PRIVATE slimt-static ${ANDROID_LOG_LIB})
