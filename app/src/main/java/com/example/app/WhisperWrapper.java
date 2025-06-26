// WhisperWrapper.java (in app/src/main/java/com/example/app/)
package com.example.app;

import android.content.Context;
import android.os.AsyncTask; // Still needed if using AsyncTask for Whisper processing
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Callback interface for Whisper transcription results.
 */
interface WhisperTranscriptionCallback {
    void onResult(String result);
    void onError(Exception e);
}

/**
 * A wrapper class to manage the native whisper.cpp context and provide transcription functionality.
 */
public class WhisperWrapper {
    private static final String TAG = "WhisperWrapper";

    // Load the native library compiled by CMake
    static {
        System.loadLibrary("whisper_android");
    }

    // Native methods
    private native long initContext(String modelPath, String language);
    private native String fullTranscribe(long contextPtr, float[] audioData);
    private native void freeContext(long contextPtr);

    private long whisperContextPtr = 0; // Pointer to the native whisper_context
    private final Context appContext;
    private final ExecutorService transcriptionExecutor; // Dedicated executor for transcription

    public WhisperWrapper(Context context) {
        this.appContext = context.getApplicationContext();
        // Use a single-threaded executor for sequential transcription
        this.transcriptionExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Initializes the native Whisper context on a background thread.
     * @param modelPath The absolute path to the ggml model file on device.
     * @param language The language code (e.g., "en", "auto").
     * @param callback The callback for initialization result.
     */
    public void initialize(String modelPath, String language, WhisperTranscriptionCallback callback) {
        transcriptionExecutor.execute(() -> {
            try {
                whisperContextPtr = initContext(modelPath, language);
                if (whisperContextPtr != 0) {
                    Log.d(TAG, "Native Whisper context initialized successfully.");
                    callback.onResult("Initialization successful"); // Use onResult for success
                } else {
                    throw new IllegalStateException("Failed to initialize native Whisper context.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Whisper initialization error: " + e.getMessage(), e);
                callback.onError(new RuntimeException("Whisper initialization failed: " + e.getMessage(), e));
            }
        });
    }

    /**
     * Transcribes audio using the native Whisper context on a background thread.
     * Audio must be 16kHz, 16-bit PCM, mono.
     * @param pcm16bitSamples Audio samples as short array.
     * @param callback Callback for transcription result.
     */
    public void transcribe(short[] pcm16bitSamples, WhisperTranscriptionCallback callback) {
        if (whisperContextPtr == 0) {
            callback.onError(new IllegalStateException("Whisper context not initialized."));
            return;
        }

        transcriptionExecutor.execute(() -> {
            try {
                // Convert short[] (16-bit PCM) to float[] (32-bit float, expected by whisper.cpp)
                float[] pcmf32 = new float[pcm16bitSamples.length];
                for (int i = 0; i < pcm16bitSamples.length; i++) {
                    pcmf32[i] = (float) pcm16bitSamples[i] / 32768.0f; // Normalize to -1.0 to 1.0
                }

                String result = fullTranscribe(whisperContextPtr, pcmf32);
                if (result != null && !result.isEmpty()) {
                    callback.onResult(result);
                } else {
                    callback.onResult(""); // Indicate no text transcribed
                }
            } catch (Exception e) {
                Log.e(TAG, "Whisper transcription error: " + e.getMessage(), e);
                callback.onError(new RuntimeException("Whisper transcription failed: " + e.getMessage(), e));
            }
        });
    }

    /**
     * Frees the native Whisper context resources.
     */
    public void release() {
        transcriptionExecutor.execute(() -> {
            if (whisperContextPtr != 0) {
                freeContext(whisperContextPtr);
                whisperContextPtr = 0;
                Log.d(TAG, "Native Whisper context freed.");
            }
        });
        transcriptionExecutor.shutdown(); // Shut down the executor when releasing
    }
}
