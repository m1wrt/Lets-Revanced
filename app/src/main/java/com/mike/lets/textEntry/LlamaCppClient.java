package com.mike.lets.textEntry;

import android.content.Context;
import android.util.Log;
import com.google.ai.edge.litertlm.*;
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
            if (LlmRuntimeManager.isReady()) {
                release();
            }
        }

        llmExecutor.execute(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                Log.d(TAG, "Iniciando carga del modelo: " + modelNameOrPath);
                String modelPath;
                if (modelNameOrPath.startsWith("/")) {
                    modelPath = modelNameOrPath;
                } else {
                    modelPath = copyModelFromAssets(context, modelNameOrPath);
                }

                if (modelPath != null && new File(modelPath).exists()) {
                    Log.d(TAG, "Ruta del archivo de modelo encontrada: " + modelPath + ", tamaño: " + new File(modelPath).length());

                    int cores = 2;
                    ModelConfig modelConfig = new ModelConfig(
                        modelPath,
                        context.getCacheDir().getAbsolutePath(),
                        cores,
                        cores
                    );

                    Log.d(TAG, "Inicializando runtime con LlmRuntimeManager...");
                    kotlinx.coroutines.BuildersKt.runBlocking(
                        kotlinx.coroutines.Dispatchers.getDefault(),
                        (coroutineScope, continuation) -> {
                            try {
                                if (LlmRuntimeManager.canUseGpu(context)) {
                                    try {
                                        LlmRuntimeManager.INSTANCE.initModelWithGpu(context, modelConfig, continuation);
                                    } catch (Exception gpuEx) {
                                        Log.w(TAG, "GPU init failed, falling back to CPU", gpuEx);
                                        LlmRuntimeManager.INSTANCE.initModelWithCpu(context, modelConfig, continuation);
                                    }
                                } else {
                                    LlmRuntimeManager.INSTANCE.initModelWithCpu(context, modelConfig, continuation);
                                }
                            } catch (Throwable t) {
                                if (isOpenClError(t)) {
                                    Log.w(TAG, "OpenCL error during GPU init, falling back to CPU", t);
                                    LlmRuntimeManager.INSTANCE.initModelWithCpu(context, modelConfig, continuation);
                                } else {
                                    throw new RuntimeException(t);
                                }
                            }
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                    Log.d(TAG, "Runtime inicializado correctamente.");

                    synchronized (this) {
                        isInitializing = false;
                    }
                    callback.onSuccess("Model loaded");
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
                Log.e(TAG, "Error crítico al inicializar LiteRT-LM", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            }
        });
    }

    public synchronized void getCompletion(String contextText, String keywords, LLMCallback callback) {
        if (keywords == null || keywords.trim().isEmpty()) {
            callback.onError("Sin palabras clave");
            return;
        }

        if (!LlmRuntimeManager.isReady()) {
            Log.e(TAG, "LiteRT-LM engine not initialized");
            callback.onError("LiteRT-LM not initialized");
            return;
        }

        if (currentGenerationFuture != null && !currentGenerationFuture.isDone()) {
            currentGenerationFuture.cancel(true);
        }

        currentGenerationFuture = llmExecutor.submit(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                String prompt = formatPrompt(keywords);
                Log.d(TAG, "Prompt enviado:\n" + prompt);
                
                long startTime = System.currentTimeMillis();

                String result = kotlinx.coroutines.BuildersKt.runBlocking(
                    kotlinx.coroutines.Dispatchers.getDefault(),
                    (coroutineScope, continuation) -> LlmRuntimeManager.INSTANCE.safeGenerate(prompt, continuation)
                );

                long elapsedTimeMs = System.currentTimeMillis() - startTime;
                double elapsedSeconds = elapsedTimeMs / 1000.0;
                
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                if (result != null && !result.trim().isEmpty()) {
                    String cleaned = cleanOutput(result);
                    String logBanner = String.format(Locale.US,
                        "\n============================================================\n" +
                        " [LlamaCppClient] INFERENCIA COMPLETADA\n" +
                        " -> Tiempo de procesamiento: %.2f segundos (%d ms)\n" +
                        " -> Resultado (Limpio)     : %s\n" +
                        "============================================================",
                        elapsedSeconds, elapsedTimeMs, cleaned
                    );
                    Log.i(TAG, logBanner);
                    callback.onSuccess(cleaned);
                } else {
                    Log.e(TAG, "Generation failed: result is null or empty");
                    callback.onError("Generation failed");
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Log.d(TAG, "LLM generation interrupted/cancelled");
                } else {
                    Log.e(TAG, "Error during completion", e);
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    private boolean isOpenClError(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            String msg = curr.getMessage() != null ? curr.getMessage() : "";
            String clsName = curr.getClass().getName();
            if (clsName.contains("LiteRtLmJniException") ||
                msg.contains("OpenCL") ||
                msg.contains("libOpenCL") ||
                msg.contains("Can not find OpenCL")) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    private String formatPrompt(String keywords) {
        return "<start_of_turn>user\n" +
                keywords.trim() + "<end_of_turn>\n" +
                "<start_of_turn>model\n";
    }

    private String cleanOutput(String raw) {
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
        LlmRuntimeManager.cleanupCurrentModel();
        Log.d(TAG, "LiteRT-LM released");
    }

    public synchronized boolean isReady() {
        return LlmRuntimeManager.isReady();
    }

    public static void test(Context context) {
        // no-op test helper
    }
}
