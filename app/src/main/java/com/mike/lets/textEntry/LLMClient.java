package com.mike.lets.textEntry;

import android.util.Log;
import androidx.annotation.NonNull;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class LLMClient {
    private static final String TAG = "LLMClient";
    private static final String SERVER_URL = "http://192.168.1.13:9379/v1/chat/completions";
    private static final String MODEL_NAME = "gemma-4-E2B-it.litertlm";
    
    private final OkHttpClient client;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface LLMCallback {
        void onSuccess(String prediction);
        void onError(String error);
    }

    public LLMClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public void getCompletion(String contextText, String keywords, final LLMCallback callback) {
        Log.d(TAG, "Attempting request to " + SERVER_URL + " with keywords: " + keywords);
        try {
            JSONObject json = new JSONObject();
            json.put("model", MODEL_NAME);
            
            JSONArray messages = new JSONArray();
            
            // Strict System Prompt from User
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Eres un asistente de IA para que los pacientes con enfermedades de las neuronas motoras se comuniquen. " +
                    "Tu ÚNICO objetivo es convertir las palabras clave en una oración completa y natural. " +
                    "REGLAS CRÍTICAS:\n" +
                    "1. DEBES usar todas las palabras clave proporcionadas.\n" +
                    "2. NO inventes información que no esté en las palabras clave.\n" +
                    "3. Si las palabras clave son 'hola dame', NO respondas '¿cómo estás?', sino algo como 'Hola, dame eso'.\n" +
                    "4. Los grupos entre corchetes como '[A-F G-M]' representan una palabra que se está escribiendo actualmente. Úsalos para predecir la palabra más probable que encaje en la oración.\n" +
                    "5. Responde ÚNICAMENTE con la oración final.");
            messages.put(systemMsg);

            // Improved Few-shot prompting for natural Spanish synthesis
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            String prompt = "Instrucción: Convierte estas palabras clave en una oración gramatical.\n\n" +
                    "Keywords: pollo, Context: Qué quieres cenar\n" +
                    "Resultado: Quiero pollo para cenar.\n\n" +
                    "Keywords: hola dame, Context: Conversación general\n" +
                    "Resultado: Hola, dame eso por favor.\n\n" +
                    "Keywords: quiero pasta queso, Context: Pidiendo comida\n" +
                    "Resultado: Quiero pasta con queso.\n\n" +
                    "Ahora genera el resultado para esta entrada:\n" +
                    "Keywords: " + (keywords.isEmpty() ? "---" : keywords) + "\n" +
                    "Context: " + (contextText.isEmpty() ? "Conversación general" : contextText) + "\n" +
                    "Resultado:";
            userMsg.put("content", prompt);
            messages.put(userMsg);

            json.put("messages", messages);
            json.put("max_tokens", 80);
            json.put("temperature", 0.3); // Lower temperature for more stability
            json.put("top_p", 0.9);
            
            // Avoid repeating the prompt or being too chatty
            json.put("stop", new JSONArray().put("\n").put("Entrada:"));

            String jsonString = json.toString();
            Log.d(TAG, "Request Body: " + jsonString);

            RequestBody body = RequestBody.create(jsonString, JSON);
            Request request = new Request.Builder()
                    .url(SERVER_URL)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Request failed: " + e.getMessage(), e);
                    callback.onError(e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    Log.d(TAG, "Response Code: " + response.code());
                    if (!response.isSuccessful()) {
                        String err = "Error " + response.code() + ": " + response.message();
                        Log.e(TAG, err);
                        callback.onError(err);
                        return;
                    }

                    try {
                        String responseData = response.body().string();
                        Log.d(TAG, "Response Body: " + responseData);
                        JSONObject responseObject = new JSONObject(responseData);
                        JSONArray choices = responseObject.getJSONArray("choices");
                        if (choices.length() > 0) {
                            String prediction = choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content");
                            callback.onSuccess(prediction.trim());
                        } else {
                            callback.onError("No choices returned from LLM");
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON Parsing error", e);
                        callback.onError(e.getMessage());
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "JSON creation error", e);
            callback.onError(e.getMessage());
        }
    }
}
