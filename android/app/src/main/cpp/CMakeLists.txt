# WhisperNative — غلاف whisper.cpp للاستدلال على المعالج مباشرة (معمارية ترتيل)
# يُجلب whisper.cpp من GitHub مثبَّت الإصدار عند البناء (CI لديه شبكة) —
# لا نسخ مصادر ضخمة داخل المستودع.
cmake_minimum_required(VERSION 3.22)
project(whisper_jni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

include(FetchContent)

# خيارات whisper.cpp/ggml: مكتبة ساكنة، بلا أمثلة/اختبارات، بلا -march=native
# (بناء متقاطع)، بلا OpenMP (ggml يستخدم خيوطه الخاصة عبر pthreads على أندرويد).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# NEON يُفعَّل تلقائياً على arm64؛ وعلى armeabi-v7a عبر -DANDROID_ARM_NEON=ON من gradle.

FetchContent_Declare(
  whisper
  GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
  GIT_TAG        v1.7.4
  GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(whisper_jni SHARED whisper_jni.c)
target_link_libraries(whisper_jni whisper log)
