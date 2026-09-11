package com.mike.lets.textEntry;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class LlamaCppClient {
    private static final String TAG = "LlamaCppClient";
    private long nativePtr = 0;
    private boolean isInitializing = false;
    private final java.util.concurrent.ExecutorService llmExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    static {
        System.loadLibrary("lets");
    }

    public interface LLMCallback {
        void onSuccess(String prediction);
        void onError(String error);
    }

    public void initialize(Context context, String modelName, LLMCallback callback) {
        synchronized (this) {
            if (nativePtr != 0) {
                callback.onSuccess("Model already loaded");
                return;
            }
            if (isInitializing) {
                callback.onSuccess("Model is already loading...");
                return;
            }
            isInitializing = true;
        }
        
        llmExecutor.execute(() -> {
            try {
                String modelPath = copyModelFromAssets(context, modelName);
                if (modelPath != null) {
                    long ptr = nativeInit(modelPath);
                    synchronized (this) {
                        nativePtr = ptr;
                        isInitializing = false;
                    }
                    if (nativePtr != 0) {
                        callback.onSuccess("Model loaded");
                    } else {
                        callback.onError("Failed to initialize native Llama");
                    }
                } else {
                    synchronized (this) {
                        isInitializing = false;
                    }
                    callback.onError("Model file not found in assets");
                }
            } catch (Exception e) {
                synchronized (this) {
                    isInitializing = false;
                }
                callback.onError(e.getMessage());
            }
        });
    }

    public void getCompletion(String contextText, String keywords, LLMCallback callback) {
        synchronized (this) {
            if (nativePtr == 0) {
                Log.e(TAG, "Llama not initialized");
                callback.onError("Llama not initialized");
                return;
            }
            
            // Cancel pending task if any (only works if task hasn't started)
            // But since it's a SingleThreadExecutor, we can't easily "remove" from queue.
            // A better way is to use a volatile variable to hold the latest keywords.
        }

        llmExecutor.execute(() -> {
            long currentPtr;
            synchronized (this) {
                currentPtr = nativePtr;
            }
            
            if (currentPtr == 0) return;

            try {
                String prompt = formatPrompt(contextText, keywords);
                Log.d(TAG, "Executing LLM request: " + keywords);
                String result = nativeGetCompletion(currentPtr, prompt);
                if (result != null) {
                    callback.onSuccess(result.trim());
                } else {
                    callback.onError("Generation failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during completion", e);
                callback.onError(e.getMessage());
            }
        });
    }

    private String formatPrompt(String contextText, String keywords) {
        // Template exacto solicitado: <start_of_turn>user\nCrea una oración con: {{ .Prompt }}.<end_of_turn>\n<start_of_turn>model\n
        String prompt = keywords;
        if (!contextText.isEmpty()) {
            prompt += " (Contexto: " + contextText + ")";
        }
        
        return "<start_of_turn>user\n" +
               "Crea una oracion con: " + prompt + ".<end_of_turn>\n" +
               "<start_of_turn>model\n";
    }

    private String copyModelFromAssets(Context context, String modelName) {
        File file = new File(context.getFilesDir(), modelName);
        if (file.exists()) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(modelName);
             FileOutputStream os = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024 * 4];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
            os.flush();
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Error copying model", e);
            return null;
        }
    }

    public synchronized void release() {
        if (nativePtr != 0) {
            nativeRelease(nativePtr);
            nativePtr = 0;
        }
    }

    public synchronized boolean isReady() {
        return nativePtr != 0;
    }

    private native long nativeInit(String modelPath);
    private native String nativeGetCompletion(long ptr, String prompt);
    private native void nativeRelease(long ptr);
}
