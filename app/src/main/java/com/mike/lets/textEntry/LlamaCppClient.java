package com.mike.lets.textEntry;

import android.content.Context;
import android.os.Environment;
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
    private native void nativeGetCompletionStream(long ptr, String prompt, LLMCallback callback);
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
                String modelPath = resolveModelPath(context, modelNameOrPath);

                if (modelPath != null) {
                    File file = new File(modelPath);
                    if (file.exists() && file.length() > 1024) {
                        long sizeMb = file.length() / (1024 * 1024);
                        Log.d(TAG, "Ruta del archivo de modelo encontrada: " + modelPath + ", tamaño: " + file.length() + " bytes (" + sizeMb + " MB)");

                        long handle = nativeInit(modelPath);
                        if (handle != 0) {
                            synchronized (this) {
                                nativeHandle = handle;
                                isInitializing = false;
                            }
                            Log.d(TAG, "Motor llama.cpp nativo inicializado correctamente con handle: " + handle);
                            callback.onSuccess("Model loaded");
                            return;
                        } else {
                            synchronized (this) {
                                isInitializing = false;
                            }
                            String err = "El archivo '" + file.getName() + "' (" + sizeMb + " MB) no es un modelo .gguf válido o compatible. Importa un paquete .zip con modelo .gguf.";
                            Log.e(TAG, err + " -> " + modelPath);
                            callback.onError(err);
                            return;
                        }
                    } else if (file.exists()) {
                        boolean deleted = file.delete();
                        if (!deleted) Log.w(TAG, "No se pudo borrar archivo corrupto/vacío: " + file.getName());
                    }
                }

                synchronized (this) {
                    isInitializing = false;
                }
                String err = "Sin modelo LLM cargado. Importa un paquete (.zip) con modelo .gguf en Ajustes.";
                Log.d(TAG, err);
                callback.onError(err);
            } catch (Exception e) {
                synchronized (this) {
                    isInitializing = false;
                }
                Log.e(TAG, "Error crítico al inicializar llama.cpp", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            }
        });
    }

    public String resolveModelPath(Context context, String modelNameOrPath) {
        if (modelNameOrPath == null || modelNameOrPath.trim().isEmpty()) {
            return null;
        }

        String fileName = new File(modelNameOrPath).getName();

        // 1. Check direct path if file exists
        File directFile = new File(modelNameOrPath);
        if (directFile.exists() && directFile.length() > 0) {
            return directFile.getAbsolutePath();
        }

        // 2. Check internal files directory
        File internalFile = new File(context.getFilesDir(), fileName);
        if (internalFile.exists() && internalFile.length() > 0) {
            return internalFile.getAbsolutePath();
        }

        // 3. Check external files directory
        File externalFile = context.getExternalFilesDir(null) != null ? new File(context.getExternalFilesDir(null), fileName) : null;
        if (externalFile != null && externalFile.exists() && externalFile.length() > 0) {
            return externalFile.getAbsolutePath();
        }

        // 4. Check cache directory
        File cacheFile = new File(context.getCacheDir(), fileName);
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.getAbsolutePath();
        }

        // 5. Check public Downloads directory
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir != null) {
            File downloadFile = new File(downloadsDir, fileName);
            if (downloadFile.exists() && downloadFile.length() > 0) {
                return downloadFile.getAbsolutePath();
            }
        }

        // 6. Check common SDCard Download paths
        String sdcardPath = Environment.getExternalStorageDirectory() != null ? Environment.getExternalStorageDirectory().getPath() : "/storage/emulated/0";
        String[] fallbackPaths = {
            sdcardPath + "/Download/" + fileName,
            "/storage/emulated/0/Download/" + fileName
        };
        for (String path : fallbackPaths) {
            File f = new File(path);
            if (f.exists() && f.length() > 0) {
                return f.getAbsolutePath();
            }
        }

        // 7. Try copying from assets as fallback
        return copyModelFromAssets(context, fileName);
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
                nativeGetCompletionStream(handle, prompt, callback);
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
        out = out.replaceAll("\\[.*?\\]|\\([^)]*\\)", "");
        out = out.replaceAll("[\\[\\]()]", "");
        return out.trim();
    }

    private String copyModelFromAssets(Context context, String modelName) {
        if (modelName == null || modelName.isEmpty()) return null;
        File file = new File(context.getFilesDir(), modelName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        if (file.exists()) {
            boolean deleted = file.delete();
            if (!deleted) Log.w(TAG, "No se pudo borrar archivo de 0 bytes existente: " + file.getName());
        }

        try (InputStream is = context.getAssets().open(modelName);
             FileOutputStream os = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024 * 64];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
            os.flush();
            if (file.exists() && file.length() > 0) {
                return file.getAbsolutePath();
            } else {
                if (file.exists()) file.delete();
                return null;
            }
        } catch (IOException e) {
            Log.d(TAG, "El modelo no se encuentra en assets: " + modelName);
            if (file.exists()) {
                file.delete();
            }
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

    public synchronized boolean isInitializing() {
        return isInitializing;
    }

    public static void test(Context context) {
        // no-op test helper
    }
}

