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
# libopus para o microfone (dev.223). O audio que chega e decodificado pelo
# MediaCodec, e por isso a Opus da lib ficava desligada; o microfone precisa do
# codificador dela, o mesmo do cliente de desktop. Nao ha pacote da libopus
# para o NDK, entao ela e compilada aqui, da versao oficial com hash fixo, e
# entregue ao FindOpus da lib pelas duas variaveis que ele procura. A lib
# inclui <opus/opus.h>, dai a copia dos cabecalhos para uma pasta "opus".
include(FetchContent)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(opus
        URL https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
        URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1)
FetchContent_MakeAvailable(opus)
set_target_properties(opus PROPERTIES POSITION_INDEPENDENT_CODE ON)
file(COPY "${opus_SOURCE_DIR}/include/" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/opus-inc/opus")
set(Opus_INCLUDE_DIRS "${CMAKE_CURRENT_BINARY_DIR}/opus-inc" CACHE PATH "" FORCE)
set(Opus_LIBRARIES opus CACHE STRING "" FORCE)

add_subdirectory("${CHIAKI_NG_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/chiaki-ng")

# Loader OpenXR distribuido como AAR do Khronos, exposto via prefab.
find_package(OpenXR REQUIRED CONFIG)

add_library(p5m-vr SHARED
        pilha_nativa.cpp
        p5m_jni.cpp
        spatializer.cpp
        profundidade_neural.cpp
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
