cmake_minimum_required(VERSION 3.22)
project(p5m)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libchiaki + chiaki-jni vem do submodulo, com as mesmas flags que o projeto
# Android do chiaki-ng usa. Nao tocamos em nada la dentro.
set(CHIAKI_NG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/chiaki-ng")
get_filename_component(CHIAKI_NG_DIR "${CHIAKI_NG_DIR}" ABSOLUTE)
if(NOT EXISTS "${CHIAKI_NG_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Submodulo chiaki-ng ausente em ${CHIAKI_NG_DIR}. Rode: git submodule update --init --recursive")
endif()
add_subdirectory("${CHIAKI_NG_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/chiaki-ng")

# Loader OpenXR distribuido como AAR do Khronos, exposto via prefab.
find_package(OpenXR REQUIRED CONFIG)

add_library(p5m-vr SHARED
        pilha_nativa.cpp
        p5m_jni.cpp
        spatializer.cpp
        tone_mapper.cpp
        xr_session.cpp)

target_include_directories(p5m-vr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(ANDROID_LIB_LOG log)
find_library(ANDROID_LIB_ANDROID android)
find_library(ANDROID_LIB_EGL EGL)
find_library(ANDROID_LIB_GLESV3 GLESv3)
# Painel de ajuda: o texto e desenhado com Canvas do lado Java e chega aqui
# como android.graphics.Bitmap.
find_library(ANDROID_LIB_JNIGRAPHICS jnigraphics)

target_link_libraries(p5m-vr
        OpenXR::openxr_loader
        "${ANDROID_LIB_LOG}"
        "${ANDROID_LIB_ANDROID}"
        "${ANDROID_LIB_EGL}"
        "${ANDROID_LIB_GLESV3}"
        "${ANDROID_LIB_JNIGRAPHICS}")
