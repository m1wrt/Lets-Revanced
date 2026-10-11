package com.mike.lets.textEntry;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class LlamaCppClient {
    private static final String TAG = "LlamaCppClient";

    static {
        try {
            System.loadLibrary("lets");
            Log.d(TAG, "LlamaCpp native library 'lets' loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library 'lets'", e);
        }
    }

    private native long nativeInit(String modelPath);
    private native String nativeGetCompletion(long ptr, String prompt);
    private native void nativeRelease(long ptr);

    private volatile long nativeHandle = 0;
    private boolean isInitializing = false;
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor();
    private volatile Future<?> currentGenerationFuture = null;

    public interface LLMCallback {
        void onSuccess(String prediction);
        void onError(String error);
        default void onPartial(String partialPrediction) {}
    }

    public void initialize(Context context, String modelNameOrPath, LLMCallback callback) {
        synchronized (this) {
            if (isInitializing) {
                callback.onError("Model is already loading...");
                return;
            }
            isInitializing = true;
            if (isReady()) {
                release();
            }
        }

        llmExecutor.execute(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                Log.d(TAG, "Iniciando carga del modelo llama.cpp: " + modelNameOrPath);
                String modelPath;
                if (modelNameOrPath.startsWith("/")) {
                    modelPath = modelNameOrPath;
                } else {
                    modelPath = copyModelFromAssets(context, modelNameOrPath);
                }

                if (modelPath != null && new File(modelPath).exists()) {
                    Log.d(TAG, "Ruta del archivo de modelo encontrada: " + modelPath + ", tamaño: " + new File(modelPath).length());

                    long handle = nativeInit(modelPath);
                    if (handle != 0) {
                        synchronized (this) {
                            nativeHandle = handle;
                            isInitializing = false;
                        }
                        Log.d(TAG, "Motor llama.cpp nativo inicializado correctamente con handle: " + handle);
                        callback.onSuccess("Model loaded");
                    } else {
                        synchronized (this) {
                            isInitializing = false;
                        }
                        String err = "Error al inicializar el modelo nativo llama.cpp (nativeInit retorno 0)";
                        Log.e(TAG, err);
                        callback.onError(err);
                    }
                } else {
                    synchronized (this) {
                        isInitializing = false;
                    }
                    String err = "Archivo de modelo no encontrado: " + modelNameOrPath;
                    Log.e(TAG, err);
                    callback.onError(err);
                }
            } catch (Exception e) {
                synchronized (this) {
                    isInitializing = false;
                }
                Log.e(TAG, "Error crítico al inicializar llama.cpp", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            }
        });
    }

    public synchronized void getCompletion(String contextText, String keywords, LLMCallback callback) {
        if (keywords == null || keywords.trim().isEmpty()) {
            callback.onError("Sin palabras clave");
            return;
        }

        final long handle = nativeHandle;
        if (handle == 0) {
            Log.e(TAG, "Motor llama.cpp no inicializado");
            callback.onError("llama.cpp not initialized");
            return;
        }

        if (currentGenerationFuture != null && !currentGenerationFuture.isDone()) {
            currentGenerationFuture.cancel(true);
        }

        String prompt = formatPrompt(keywords);
        Log.d(TAG, "Enviando prompt a llama.cpp:\n" + prompt);
        long startTime = System.currentTimeMillis();

        currentGenerationFuture = llmExecutor.submit(() -> {
            try {
                String rawResult = nativeGetCompletion(handle, prompt);
                long elapsedTimeMs = System.currentTimeMillis() - startTime;
                double elapsedSeconds = elapsedTimeMs / 1000.0;
                String fullResult = cleanOutput(rawResult);

                String logBanner = String.format(Locale.US,
                    "\n============================================================\n" +
                    " [LlamaCppClient] INFERENCIA NATIVA LLAMA.CPP COMPLETADA\n" +
                    " -> Tiempo de procesamiento: %.2f segundos (%d ms)\n" +
                    " -> Resultado (Limpio)     : %s\n" +
                    "============================================================",
                    elapsedSeconds, elapsedTimeMs, fullResult
                );
                Log.i(TAG, logBanner);

                callback.onSuccess(fullResult);
            } catch (Exception e) {
                Log.e(TAG, "Error durante la inferencia en llama.cpp", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : "Inference error");
            }
        });
    }

    private String formatPrompt(String keywords) {
        return "<start_of_turn>user\n" +
                keywords.trim() + "<end_of_turn>\n" +
                "<start_of_turn>model\n";
    }

    private String cleanOutput(String raw) {
        if (raw == null) return "";
        String out = raw;
        int fin = out.indexOf("<end_of_turn>");
        if (fin >= 0) out = out.substring(0, fin);
        out = out.replaceAll("(?i)^(next:?\\s*|next\\s+words:?\\s*|oraci[oó]n:?\\s*|respuesta:?\\s*|output:?\\s*|result:?\\s*)", "");
        return out.trim();
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
            Log.e(TAG, "Error copying model from assets: " + modelName, e);
            return null;
        }
    }

    public synchronized void release() {
        if (currentGenerationFuture != null) {
            currentGenerationFuture.cancel(true);
        }
        if (nativeHandle != 0) {
            nativeRelease(nativeHandle);
            nativeHandle = 0;
            Log.d(TAG, "llama.cpp native handle released");
        }
    }

    public synchronized boolean isReady() {
        return nativeHandle != 0;
    }

    public static void test(Context context) {
        // no-op test helper
    }
}
