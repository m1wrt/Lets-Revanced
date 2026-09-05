package com.mike.lets.AI;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * GeminiManager adapted to call a local litert-lm server (Gemma) 
 * with extreme strictness to avoid chatty responses.
 */
public class GeminiManager {
    private static final String TAG = "GeminiGeneration";
    private static final String SERVER_URL = "http://192.168.1.13:9379/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private Context mContext;
    private final OkHttpClient client;

    public GeminiManager() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    private void sendMessage(String data) {
        Log.d(TAG, "Broadcasting Message");
        Intent intent = new Intent("textGenerationEvent");
        intent.putExtra("message", data);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
    }

    public void init(Context context) {
        mContext = context;
    }

    public void generate(String keywords, String context, String language, String modelName) {
        try {
            JSONObject json = new JSONObject();
            json.put("model", modelName != null && !modelName.isEmpty() ? modelName : "gemma-4-E2B-it.litertlm");
            
            JSONArray messages = new JSONArray();
            
            // System message - Extremely strict to prevent "I understand" chattyness
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            String system = "Eres un motor de construcción de oraciones asertivas para personas con ELA. " +
                    "Tu ÚNICO objetivo es convertir palabras clave en una oración AFIRMATIVA y DIRECTA en ESPAÑOL. " +
                    "REGLAS CRÍTICAS:\n" +
                    "1. NO digas 'I understand', 'Entiendo', 'Claro' o similares.\n" +
                    "2. NO hagas preguntas.\n" +
                    "3. NO expliques nada.\n" +
                    "4. Responde ÚNICAMENTE con la oración final.\n" +
                    "5. Si la palabra es 'hola', responde simplemente 'Hola.'";
            systemMsg.put("content", system);
            messages.put(systemMsg);

            // User message
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            String userPrompt = "Keywords: " + keywords + "\nSentence:";
            userMsg.put("content", userPrompt);
            messages.put(userMsg);

            json.put("messages", messages);
            json.put("max_tokens", 50);
            json.put("temperature", 0.0);
            json.put("stop", new JSONArray().put("\n").put("Sentence:"));

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                    .url(SERVER_URL)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.d(TAG, "Failure: " + e.getMessage());
                    sendMessage("SP-Error: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        sendMessage("SP-Error " + response.code());
                        return;
                    }
                    try {
                        String responseData = response.body().string();
                        JSONObject responseObject = new JSONObject(responseData);
                        JSONArray choices = responseObject.getJSONArray("choices");
                        if (choices.length() > 0) {
                            String resultText = choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content");
                            sendMessage("SP-" + resultText.trim());
                        }
                    } catch (JSONException e) {
                        sendMessage("SP-Error parsing response");
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
        }
    }
}
