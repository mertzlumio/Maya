// native-lib.cpp (in app/src/main/cpp/)
// This file serves as the JNI bridge between Java/Kotlin and the native whisper.cpp library.

#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <android/log.h> // For Android logging (Log.d, Log.e in Java)

// Include whisper.cpp headers
#include "whisper.h"

// Define a log tag for native logging
#define  LOG_TAG    "WhisperNative"
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global context for whisper (handles model and state)
// IMPORTANT: Use a mutex for thread safety if accessing from multiple threads,
// though for this app's single-threaded audio processing, it might be less critical.
whisper_context * g_ctx = nullptr;

// Function to convert jstring to std::string
std::string jstring2string(JNIEnv *env, jstring jStr) {
    if (!jStr) return "";
    const jclass stringClass = env->GetObjectClass(jStr);
    const jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    const jbyteArray stringJbytes = (jbyteArray) env->CallObjectMethod(jStr, getBytes, env->NewStringUTF("UTF-8"));
    size_t length = (size_t) env->GetArrayLength(stringJbytes);
    jbyte* pBytes = env->GetByteArrayElements(stringJbytes, nullptr);
    std::string ret = std::string((char *)pBytes, length);
    env->ReleaseByteArrayElements(stringJbytes, pBytes, JNI_ABORT);
    env->DeleteLocalRef(stringJbytes);
    env->DeleteLocalRef(stringClass);
    return ret;
}

// JNI function to initialize the Whisper context
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_app_WhisperWrapper_initContext(
    JNIEnv *env,
    jobject /* this */,
    jstring modelPath,
    jstring language
) {
    if (g_ctx != nullptr) {
        LOGD("Whisper context already initialized. Freeing old context.");
        whisper_free(g_ctx); // Free existing context before creating a new one
        g_ctx = nullptr;
    }

    std::string modelPathStr = jstring2string(env, modelPath);
    std::string languageStr = jstring2string(env, language);

    LOGD("Attempting to load model from: %s", modelPathStr.c_str());

    // Initialize whisper.cpp context with correct params struct
    struct whisper_context_params cparams = whisper_context_default_params();
    // Removed: cparams.n_threads = std::thread::hardware_concurrency(); // This member does not exist here
    // You can set other context params here if needed, e.g., no_gpu, etc.

    g_ctx = whisper_init_from_file_with_params(modelPathStr.c_str(), cparams);

    if (g_ctx == nullptr) {
        LOGE("Failed to initialize whisper context from file: %s", modelPathStr.c_str());
        return 0; // Return 0 to indicate failure
    }

    LOGD("Whisper context initialized successfully.");
    return (jlong)g_ctx; // Return pointer to context
}

// JNI function to transcribe audio
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_app_WhisperWrapper_fullTranscribe(
    JNIEnv *env,
    jobject /* this */,
    jlong contextPtr,
    jfloatArray audioData
) {
    whisper_context * ctx = (whisper_context *)contextPtr;
    if (ctx == nullptr) {
        LOGE("Whisper context is null. Cannot transcribe.");
        return env->NewStringUTF("");
    }

    // Convert Java float array to C++ std::vector<float>
    jfloat *audioFloats = env->GetFloatArrayElements(audioData, nullptr);
    jsize audioLength = env->GetArrayLength(audioData);
    std::vector<float> pcmf32(audioFloats, audioFloats + audioLength);
    env->ReleaseFloatArrayElements(audioData, audioFloats, JNI_ABORT); // Release immediately

    LOGD("Starting transcription for %d samples.", audioLength);

    // Run transcription (full processing)
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY); // Or WHISPER_SAMPLING_BEAM_SEARCH

    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_context = false; // Set to false to allow for stateful context if desired. For short phrases, might be true.
    params.n_threads = std::thread::hardware_concurrency(); // Use all available cores for transcription

    // If you explicitly set a language during init, you can set it here too
    // For automatic language detection, set to "auto" or leave blank.
    // params.language = "en"; // Example: force English

    if (whisper_full(ctx, params, pcmf32.data(), pcmf32.size()) != 0) {
        LOGE("Failed to run whisper transcription.");
        return env->NewStringUTF("");
    }

    // Get the transcribed text using the new API
    std::string result = "";
    const int n_segments = whisper_full_n_segments(ctx); // Still use ctx for n_segments
    for (int i = 0; i < n_segments; ++i) {
        // Corrected function: whisper_full_get_segment_text now takes the context and segment index
        const char * text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            result += text;
        }
    }

    LOGD("Transcription complete: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// JNI function to free the Whisper context
extern "C" JNIEXPORT void JNICALL
Java_com_example_app_WhisperWrapper_freeContext(
    JNIEnv *env,
    jobject /* this */,
    jlong contextPtr
) {
    whisper_context * ctx = (whisper_context *)contextPtr;
    if (ctx != nullptr) {
        LOGD("Freeing whisper context.");
        whisper_free(ctx);
        if (ctx == g_ctx) { // Clear global if it's the one we're freeing
            g_ctx = nullptr;
        }
    }
}
