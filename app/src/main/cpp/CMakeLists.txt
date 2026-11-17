# Builds the single shared library that ships in app/src/main/jniLibs/<abi>/:
#   libhushyar_llama.so  =  llama.cpp (v1.0.0) + llama_jni.cpp
#
# The prebuilt .so is committed so that normal Gradle builds (and CI) need no
# NDK and no 40 MB llama.cpp checkout. Rebuild it only when you bump upstream.
#
#   LLAMA_CPP_DIR=/path/to/llama.cpp scripts/build-llama-android.sh

cmake_minimum_required(VERSION 3.22)
project(hushyar_llama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "Pass -DLLAMA_CPP_DIR=/path/to/llama.cpp (see scripts/build-llama-android.sh)")
endif()

set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)  # static llama/ggml -> one .so
set(LLAMA_BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)

# llama.cpp v1.0.0 compiles its llamafile sgemm kernels for every arch, but the 32-bit ARM path
# uses NEON FP16 intrinsics (vld1q_f16) without the __ARM_FEATURE_FP16 guard — upstream FIXME at
# ggml-cpu/llamafile/sgemm.cpp:309 — so an armeabi-v7a build dies with "use of undeclared
# identifier 'vld1q_f16'". Turning the llamafile kernels off for 32-bit ARM only leaves ggml's own
# ARM kernels in place and costs nothing on 64-bit builds.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

add_library(hushyar_llama SHARED llama_jni.cpp)
target_link_libraries(hushyar_llama PRIVATE llama ${CMAKE_DL_LIBS} log)
set_target_properties(hushyar_llama PROPERTIES OUTPUT_NAME "hushyar_llama")
