# Нативная часть рисования: stable-diffusion.cpp (ggml, CPU) + JNI-обёртка sdjni.cpp.
# Собирается только для arm64-v8a (см. app/build.gradle.kts).
cmake_minimum_required(VERSION 3.22.1)

# Даже в debug-сборке APK нужен оптимизированный код: без -O3 генерация медленнее в 10+ раз.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(sdjni C CXX)

set(SDCPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/stable-diffusion.cpp)
if(NOT EXISTS ${SDCPP_DIR}/CMakeLists.txt OR NOT EXISTS ${SDCPP_DIR}/ggml/CMakeLists.txt)
    message(FATAL_ERROR
        "Нет исходников stable-diffusion.cpp в ${SDCPP_DIR}.\n"
        "Выполни: git submodule update --init --recursive\n"
        "или (если проект скачан архивом): ./scripts/fetch_sdcpp.sh")
endif()

# --- настройки stable-diffusion.cpp / ggml ---
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # свой пул потоков ggml, без libomp.so
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)    # быстрые sgemm-ядра для ARM
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
# Dimensity 8400 (Cortex-A725, ARMv9.2): dotprod + i8mm + fp16 заметно ускоряют q8_0.
# На процессорах без i8mm библиотеку не загружаем (проверка в StableDiffusionNative.kt).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

# убираем неиспользуемый код и данные
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections")

add_subdirectory(${SDCPP_DIR} sdcpp EXCLUDE_FROM_ALL)

# Только словарь CLIP вместо всех словарей (см. vocab_clip_only.cpp): .so меньше примерно на 100 МБ
get_target_property(SD_SOURCES stable-diffusion SOURCES)
list(FILTER SD_SOURCES EXCLUDE REGEX "src/tokenizers/vocab/vocab\\.cpp$")
list(FILTER SD_SOURCES EXCLUDE REGEX "src/tokenizers/vocab/.*\\.hpp$")
set_property(TARGET stable-diffusion PROPERTY SOURCES ${SD_SOURCES} ${CMAKE_CURRENT_SOURCE_DIR}/vocab_clip_only.cpp)
target_include_directories(stable-diffusion PRIVATE ${SDCPP_DIR}/src/tokenizers/vocab)

add_library(sdjni SHARED sdjni.cpp)
target_include_directories(sdjni PRIVATE ${SDCPP_DIR}/include)
target_link_libraries(sdjni PRIVATE stable-diffusion log android)
target_link_options(sdjni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)   # Android 15+: страницы 16 КБ

# ---------------- whisper.cpp (распознавание смешанной RU/EN речи) и llama.cpp (чат-нейросеть) ----------------
# У каждого свой ggml другой версии, поэтому собираем их отдельными CMake-проектами в статические
# библиотеки и прячем символы внутри своей .so (--exclude-libs,ALL) — конфликтов с ggml из sd.cpp нет.
include(ExternalProject)

set(EXT_COMMON_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DBUILD_SHARED_LIBS=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    "-DCMAKE_C_FLAGS=-ffunction-sections -fdata-sections"
    "-DCMAKE_CXX_FLAGS=-ffunction-sections -fdata-sections"
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=ON
    -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF
    -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm+fp16)

set(WHISPER_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/whisper-install)
ExternalProject_Add(whisper_ext
    SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
    BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/whisper-build
    INSTALL_DIR ${WHISPER_PREFIX}
    CMAKE_ARGS ${EXT_COMMON_ARGS} -DCMAKE_INSTALL_PREFIX=${WHISPER_PREFIX} -DCMAKE_INSTALL_LIBDIR=lib
        -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_SDL2=OFF
    BUILD_BYPRODUCTS ${WHISPER_PREFIX}/lib/libwhisper.a ${WHISPER_PREFIX}/lib/libggml.a
        ${WHISPER_PREFIX}/lib/libggml-cpu.a ${WHISPER_PREFIX}/lib/libggml-base.a)

set(LLAMA_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/llama-install)
ExternalProject_Add(llama_ext
    SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp
    BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/llama-build
    INSTALL_DIR ${LLAMA_PREFIX}
    CMAKE_ARGS ${EXT_COMMON_ARGS} -DCMAKE_INSTALL_PREFIX=${LLAMA_PREFIX} -DCMAKE_INSTALL_LIBDIR=lib
        -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=OFF
        -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_HTML=OFF -DLLAMA_BUILD_IS_DEV=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF
    BUILD_BYPRODUCTS ${LLAMA_PREFIX}/lib/libllama.a ${LLAMA_PREFIX}/lib/libggml.a
        ${LLAMA_PREFIX}/lib/libggml-cpu.a ${LLAMA_PREFIX}/lib/libggml-base.a)

add_library(whisperjni SHARED whisperjni.cpp)
add_dependencies(whisperjni whisper_ext)
target_include_directories(whisperjni PRIVATE ${WHISPER_PREFIX}/include)
target_link_libraries(whisperjni PRIVATE
    ${WHISPER_PREFIX}/lib/libwhisper.a ${WHISPER_PREFIX}/lib/libggml.a
    ${WHISPER_PREFIX}/lib/libggml-cpu.a ${WHISPER_PREFIX}/lib/libggml-base.a log android m)
target_link_options(whisperjni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)

add_library(llamajni SHARED llamajni.cpp)
add_dependencies(llamajni llama_ext)
target_include_directories(llamajni PRIVATE ${LLAMA_PREFIX}/include)
target_link_libraries(llamajni PRIVATE
    ${LLAMA_PREFIX}/lib/libllama.a ${LLAMA_PREFIX}/lib/libggml.a
    ${LLAMA_PREFIX}/lib/libggml-cpu.a ${LLAMA_PREFIX}/lib/libggml-base.a log android m)
target_link_options(llamajni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
