// MainActivity.java
package com.example.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedOutputStream; // Not used anymore for Whisper, but kept if you have other uses
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.porcupine.PorcupineManager;

// No longer need BufferedReader import as ProcessBuilder is gone


public class MainActivity extends AppCompatActivity implements TaskAdapter.OnTaskClickListener {

    private static final String TAG = "VoiceTasksApp";
    private static final int REQUEST_CODE_AUDIO_PERMISSION = 1;
    private static final int SAMPLE_RATE = 16000; // Standard for speech recognition
    private static final int RECORDING_DURATION_SECONDS = 5;
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private PorcupineManager porcupineManager;
    private AudioRecord audioRecorder;
    private ExecutorService mainExecutor; // For general background tasks and audio recording

    private TextView statusText;
    private RecyclerView taskRecyclerView;
    private TaskAdapter taskAdapter;
    private List<Task> tasks;

    // Picovoice AccessKey - get your own key from oicvoice
    private static final String ACCESS_KEY = "GET_YOUR_KEY_FROM_PICOVOICE";

    // Whisper model asset name
    private static final String WHISPER_MODEL_ASSET = "ggml-tiny.en-q8_0.bin";
    private static final String TASKS_FILE = "tasks.json";

    // NEW: WhisperWrapper instance
    private WhisperWrapper whisperWrapper;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        taskRecyclerView = findViewById(R.id.taskRecyclerView);
        Button addNewTaskButton = findViewById(R.id.addNewTaskButton);

        tasks = loadTasks(); // Load tasks on startup
        taskAdapter = new TaskAdapter(this, tasks, this);
        taskRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        taskRecyclerView.setAdapter(taskAdapter);

        // Initialize executor for general background tasks and audio recording
        mainExecutor = Executors.newSingleThreadExecutor();

        // Initialize WhisperWrapper
        whisperWrapper = new WhisperWrapper(this);

        // Setup button for manual task adding (for testing, or if user wants to type)
        addNewTaskButton.setOnClickListener(v -> {
            addTask(new Task(UUID.randomUUID().toString(), "Manually added task: " + System.currentTimeMillis(), false));
            Toast.makeText(this, "Manual task added (for testing)", Toast.LENGTH_SHORT).show();
        });

        // Request permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_AUDIO_PERMISSION);
        } else {
            initializeWhisperAndPorcupine(); // Initialize both
        }
    }

    /**
     * Copies the Whisper model from assets to internal storage and initializes WhisperWrapper,
     * then starts Porcupine.
     */
    private void initializeWhisperAndPorcupine() {
        mainExecutor.execute(() -> { // Run on background thread
            try {
                runOnUiThread(() -> statusText.setText("Initializing Whisper model..."));

                // Get model file path from assets
                File modelFile = new File(getFilesDir(), WHISPER_MODEL_ASSET);
                // Always copy the model if it doesn't exist, or if you want to ensure latest version
                if (!modelFile.exists()) {
                    copyAssetFromAssets(WHISPER_MODEL_ASSET, WHISPER_MODEL_ASSET);
                }

                // Initialize WhisperWrapper
                whisperWrapper.initialize(modelFile.getAbsolutePath(), "en", new WhisperTranscriptionCallback() {
                    @Override
                    public void onResult(String result) {
                        // Whisper initialization successful
                        runOnUiThread(MainActivity.this::startPorcupine); // Proceed to start Porcupine on UI thread
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Error initializing Whisper SDK: " + e.getMessage(), e);
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Error initializing Whisper: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            statusText.setText("Error initializing Whisper.");
                        });
                    }
                });

            } catch (IOException e) {
                Log.e(TAG, "Error copying Whisper model asset: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Error copying Whisper model: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Copies an asset file from the APK to the app's internal files directory.
     * Needed for Whisper model.
     * @param assetFileName The name of the file in the assets folder.
     * @param destFileName The desired name of the file in the internal storage.
     * @throws IOException If there's an error during file operations.
     */
    private void copyAssetFromAssets(String assetFileName, String destFileName) throws IOException {
        File destFile = new File(getFilesDir(), destFileName);
        // Delete existing file to ensure a fresh copy
        if (destFile.exists()) {
            destFile.delete();
        }

        try (InputStream is = getAssets().open(assetFileName);
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            Log.d(TAG, "Asset copied: " + assetFileName + " to " + destFile.getAbsolutePath());
        }
    }


    /**
     * Initializes and starts the Porcupine wake word engine.
     * This method should only be called after microphone permission has been granted
     * and Whisper SDK is initialized.
     */
    private void startPorcupine() {
        try {
            statusText.setText("Initializing Porcupine...");

            porcupineManager = new PorcupineManager.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPaths(new String[]{"Hey-maya_en_android_v3_0_0.ppn"})
                    .setModelPath("porcupine_params.pv")
                    .build(this, keywordIndex -> {
                        // Wake word detected callback
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Wake Word Detected!", Toast.LENGTH_SHORT).show();
                            statusText.setText("Wake Word Detected! Recording audio...");
                            startRecordingAndTranscribing(); // Start recording after wake word
                        });
                    });

            porcupineManager.start();
            statusText.setText("Listening for wake word...");
        } catch (PorcupineException e) {
            Log.e(TAG, "Error starting Porcupine: " + e.getMessage(), e);
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Error starting Porcupine: " + e.getMessage(), Toast.LENGTH_LONG).show();
                statusText.setText("Error: " + e.getMessage());
            });
        }
    }

    /**
     * Starts recording audio for a fixed duration and then transcribes it using Whisper SDK.
     */
    private void startRecordingAndTranscribing() {
        // Ensure Porcupine is stopped during recording to avoid conflicts
        if (porcupineManager != null) {
            try {
                porcupineManager.stop();
            } catch (PorcupineException e) {
                Log.e(TAG, "Failed to stop Porcupine during recording: " + e.getMessage());
            }
        }

        mainExecutor.execute(() -> { // Use mainExecutor for audio recording and transcription trigger
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "AudioRecord.getMinBufferSize returned invalid value: " + bufferSize);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Recording setup error.", Toast.LENGTH_LONG).show();
                    statusText.setText("Recording setup error.");
                });
                restartPorcupineSafely();
                return;
            }

            audioRecorder = new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);

            if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed.");
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "AudioRecord init failed.", Toast.LENGTH_LONG).show();
                    statusText.setText("Audio record init error.");
                });
                restartPorcupineSafely();
                return;
            }

            // Create a buffer to hold all recorded audio samples
            // Short array because ENCODING_PCM_16BIT means 2 bytes per sample, which fits in 'short'
            int numSamplesToRecord = SAMPLE_RATE * RECORDING_DURATION_SECONDS;
            short[] audioBuffer = new short[numSamplesToRecord]; // Total samples
            int samplesRead = 0;

            audioRecorder.startRecording();
            runOnUiThread(() -> statusText.setText("Recording... (" + RECORDING_DURATION_SECONDS + "s)"));

            long startTime = System.currentTimeMillis();
            long endTime = startTime + (RECORDING_DURATION_SECONDS * 1000);

            // Read audio data until duration is met or buffer is full
            while (System.currentTimeMillis() < endTime && samplesRead < numSamplesToRecord) {
                int result = audioRecorder.read(audioBuffer, samplesRead, Math.min(bufferSize / 2, numSamplesToRecord - samplesRead));
                if (result > 0) {
                    samplesRead += result;
                } else if (result == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord.read: ERROR_INVALID_OPERATION");
                    break;
                } else if (result == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord.read: ERROR_BAD_VALUE");
                    break;
                }
            }

            audioRecorder.stop();
            audioRecorder.release();
            audioRecorder = null;

            // Trim audioBuffer to actual samples read
            final short[] finalAudioSamples = new short[samplesRead];
            System.arraycopy(audioBuffer, 0, finalAudioSamples, 0, samplesRead);

            runOnUiThread(() -> statusText.setText("Recording finished. Transcribing..."));
            Log.d(TAG, "Recording finished. Samples recorded: " + samplesRead);

            // NEW: Transcribe using WhisperWrapper (calls native code)
            if (whisperWrapper != null) {
                whisperWrapper.transcribe(finalAudioSamples, new WhisperTranscriptionCallback() {
                    @Override
                    public void onResult(String result) {
                        runOnUiThread(() -> {
                            if (result != null && !result.isEmpty()) {
                                addTask(new Task(UUID.randomUUID().toString(), result, false));
                                Toast.makeText(MainActivity.this, "Transcribed: " + result, Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(MainActivity.this, "Transcription failed or no text detected.", Toast.LENGTH_SHORT).show();
                            }
                            statusText.setText("Listening for wake word...");
                            restartPorcupineSafely();
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        runOnUiThread(() -> {
                            Log.e(TAG, "Whisper transcription error: " + e.getMessage(), e);
                            Toast.makeText(MainActivity.this, "Transcription error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            statusText.setText("Transcription error.");
                            restartPorcupineSafely();
                        });
                    }
                });
            } else {
                Log.e(TAG, "WhisperWrapper not initialized.");
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Whisper Wrapper not ready.", Toast.LENGTH_LONG).show();
                    statusText.setText("Whisper not ready.");
                    restartPorcupineSafely();
                });
            }
        });
    }

    /**
     * Helper to restart Porcupine. It will attempt to start the PorcupineManager
     * if it's initialized.
     */
    private void restartPorcupineSafely() {
        if (porcupineManager != null) {
            try {
                porcupineManager.start();
                runOnUiThread(() -> statusText.setText("Listening for wake word..."));
            } catch (PorcupineException e) {
                Log.e(TAG, "Failed to restart Porcupine: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to restart Porcupine: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                runOnUiThread(() -> statusText.setText("Error restarting Porcupine."));
            }
        }
    }


    /** Task Management (UNCHANGED) **/

    private List<Task> loadTasks() {
        File file = new File(getFilesDir(), TASKS_FILE);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file))) {
            Gson gson = new Gson();
            Type listType = new TypeToken<ArrayList<Task>>(){}.getType();
            List<Task> loadedTasks = gson.fromJson(reader, listType);
            return loadedTasks != null ? loadedTasks : new ArrayList<>();
        } catch (IOException e) {
            Log.e(TAG, "Error loading tasks: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private void saveTasks() {
        mainExecutor.execute(() -> { // Save on background thread
            File file = new File(getFilesDir(), TASKS_FILE);
            try (OutputStream os = new FileOutputStream(file)) {
                Gson gson = new Gson();
                String json = gson.toJson(tasks);
                os.write(json.getBytes());
                Log.d(TAG, "Tasks saved successfully.");
            } catch (IOException e) {
                Log.e(TAG, "Error saving tasks: " + e.getMessage(), e);
            }
        });
    }

    private void addTask(Task task) {
        tasks.add(0, task); // Add to the top
        taskAdapter.notifyItemInserted(0);
        taskRecyclerView.scrollToPosition(0);
        saveTasks();
    }

    @Override
    public void onTaskClick(int position) {
        Task task = tasks.get(position);
        task.setFinished(!task.isFinished()); // Toggle finished state
        taskAdapter.notifyItemChanged(position);
        saveTasks(); // Save changes

        // Optional: Implement a "bin" or delayed deletion logic here
        if (task.isFinished()) {
            Toast.makeText(this, "Task '" + task.getText() + "' marked as finished!", Toast.LENGTH_SHORT).show();
            // Example: remove after a short delay or move to a 'bin' list
            // handler.postDelayed(() -> removeTask(task.getId()), 5000);
        } else {
            Toast.makeText(this, "Task '" + task.getText() + "' marked as active.", Toast.LENGTH_SHORT).show();
        }
    }

    // Optional: Method to truly remove a task
    private void removeTask(String taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(taskId)) {
                tasks.remove(i);
                taskAdapter.notifyItemRemoved(i);
                saveTasks();
                break;
            }
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_AUDIO_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeWhisperAndPorcupine();
        } else {
            Toast.makeText(this, "Microphone permission is required to use this app's features.", Toast.LENGTH_LONG).show();
            statusText.setText("Permission denied. App functionality limited.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (porcupineManager != null) {
            try {
                porcupineManager.stop();
                statusText.setText("Stopped listening (app paused).");
            } catch (PorcupineException e) {
                Log.e(TAG, "Error stopping Porcupine on pause: " + e.getMessage(), e);
            }
        }
        if (audioRecorder != null) {
            try {
                audioRecorder.stop();
                audioRecorder.release();
            }
             catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping/releasing audio recorder on pause: " + e.getMessage(), e);
            }
            audioRecorder = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            restartPorcupineSafely();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (porcupineManager != null) {
            try {
                porcupineManager.stop();
                porcupineManager.delete();
            } catch (PorcupineException e) {
                Log.e(TAG, "Error deleting Porcupine on destroy: " + e.getMessage(), e);
            }
            porcupineManager = null;
        }
        if (whisperWrapper != null) { // NEW: Release WhisperWrapper resources
            whisperWrapper.release();
        }
        if (mainExecutor != null) { // Changed from audioProcessingExecutor to mainExecutor
            mainExecutor.shutdownNow(); // Shut down the executor
        }
        // No more tempWavFile cleanup needed as we're not creating temp WAV files for CLI
    }
}
