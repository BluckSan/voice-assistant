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
