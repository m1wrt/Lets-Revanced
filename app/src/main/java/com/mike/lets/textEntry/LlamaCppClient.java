package com.mike.lets.textEntry;

import android.content.Context;
import android.util.Log;
import com.google.ai.edge.litertlm.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LlamaCppClient {
    private static final String TAG = "LlamaCppClient";

    private Engine engine = null;
    private boolean isInitializing = false;
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor();

    public interface LLMCallback {
        void onSuccess(String prediction);
        void onError(String error);
    }

    public void initialize(Context context, String modelNameOrPath, LLMCallback callback) {
        synchronized (this) {
            if (isInitializing) {
                callback.onError("Model is already loading...");
                return;
            }
            isInitializing = true;
            if (engine != null) {
                release();
            }
        }

        llmExecutor.execute(() -> {
            try {
                Log.d(TAG, "Iniciando carga del modelo: " + modelNameOrPath);
                String modelPath;
                if (modelNameOrPath.startsWith("/")) {
                    modelPath = modelNameOrPath;
                } else {
                    modelPath = copyModelFromAssets(context, modelNameOrPath);
                }

                if (modelPath != null && new File(modelPath).exists()) {
                    Log.d(TAG, "Ruta del archivo de modelo encontrada: " + modelPath + ", tamaño: " + new File(modelPath).length());

                    EngineConfig engineConfig = new EngineConfig(
                        modelPath,
                        new Backend.CPU(4, 4), // CPU backend (4 threads, 4 batch threads)
                        null,
                        null,
                        null,   // maxNumTokens (context size n_ctx = 64)
                        null,
                        context.getCacheDir().getAbsolutePath()
                    );

                    Log.d(TAG, "Inicializando Engine con LiteRT-LM...");
                    Engine eng = new Engine(engineConfig);
                    eng.initialize();
                    Log.d(TAG, "Engine inicializado correctamente.");

                    synchronized (this) {
                        this.engine = eng;
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

    public void getCompletion(String contextText, String keywords, LLMCallback callback) {
        if (keywords == null || keywords.trim().isEmpty()) {
            callback.onError("Sin palabras clave");
            return;
        }

        Engine currentEngine;
        synchronized (this) {
            currentEngine = engine;
            if (currentEngine == null) {
                Log.e(TAG, "LiteRT-LM engine not initialized");
                callback.onError("LiteRT-LM not initialized");
                return;
            }
        }

        llmExecutor.execute(() -> {
            Conversation conv = null;
            try {
                SamplerConfig samplerConfig = new SamplerConfig(
                    64,   // topK
                    0.95, // topP
                    0.5,  // temperature
                    0     // seed
                );
                ConversationConfig convConfig = new ConversationConfig(
                    null,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    samplerConfig
                );

                conv = currentEngine.createConversation(convConfig);

                String prompt = formatPrompt(keywords);
                Log.d(TAG, "Prompt enviado (fresh session):\n" + prompt);
                
                Message responseMessage = conv.sendMessage(prompt);
                String result = extractText(responseMessage);
                
                if (result != null) {
                    String cleaned = cleanOutput(result);
                    Log.d(TAG, "SALIDA LLM (Raw):\n" + result);
                    Log.d(TAG, "SALIDA LLM (Cleaned):\n" + cleaned);
                    callback.onSuccess(cleaned);
                } else {
                    Log.e(TAG, "Generation failed: result is null");
                    callback.onError("Generation failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during completion", e);
                callback.onError(e.getMessage());
            } finally {
                if (conv != null) {
                    try {
                        conv.close();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private String extractText(Message msg) {
        if (msg == null || msg.getContents() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Object c : msg.getContents().getContents()) {
            if (c instanceof Content.Text) {
                sb.append(((Content.Text) c).getText());
            }
        }
        return sb.toString();
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
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception ignored) {}
            engine = null;
        }
        Log.d(TAG, "LiteRT-LM released");
    }

    public synchronized boolean isReady() {
        return engine != null;
    }

    public static void test(Context context) {
        // no-op test helper
    }
}
