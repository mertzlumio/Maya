# CMakeLists.txt (in app/src/main/cpp/)
cmake_minimum_required(VERSION 3.18.1) # Minimum CMake version

# Set the project name
project(whisper-android)

# Disable implicit Android library search paths to avoid conflicts if needed
# set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
# set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)

# Path to the root of the whisper.cpp repository
# IMPORTANT: Adjust this path based on where you cloned whisper.cpp.
# Using absolute path for robustness given previous issues.
set(WHISPER_ROOT_DIR "/home/chiro/Moose_Chamber/Spotify_Wrapper/whisper.cpp")

# Corrected check for whisper.h in its proper 'include' subdirectory
if(NOT EXISTS "${WHISPER_ROOT_DIR}/include/whisper.h")
    message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_ROOT_DIR} (or whisper.h not in its include subdirectory). Please ensure whisper.cpp is cloned correctly or adjust WHISPER_ROOT_DIR.")
endif()

# Include the whisper.cpp project
add_subdirectory(${WHISPER_ROOT_DIR} whisper_src)

# Define the native shared library for your Android app
# This is the library that your Java code will load via System.loadLibrary("whisper_android")
add_library(
    whisper_android
    SHARED
    native-lib.cpp # Your JNI source file
)

# Link your native library with the whisper.cpp library
target_link_libraries(
    whisper_android
    whisper # This is the library target defined by whisper.cpp's CMake
    android # Link against Android's native API
    log     # Link against the Android logging library (for __android_log_print)
    m       # Link against math library
)

# Include directories for your JNI code (native-lib.cpp needs whisper.h)
target_include_directories(
    whisper_android
    PRIVATE
    ${CMAKE_SOURCE_DIR} # For native-lib.cpp
    ${WHISPER_ROOT_DIR}/include # NEW: Point to the actual include directory of whisper.cpp
)

# Add Android specific build flags (copied from whisper.cpp's examples)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-limit-debug-info -fPIE")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fno-limit-debug-info -fPIE")

# Specific flags for whisper.cpp's JNI bridge:
# D_GLIBCXX_USE_CXX11_ABI=0 is crucial for compatibility between NDK-built C++ and Java
target_compile_definitions(whisper_android PUBLIC -D_GLIBCXX_USE_CXX11_ABI=0)
target_compile_options(whisper_android PUBLIC -s -O3)
