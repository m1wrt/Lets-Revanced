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
    private static final String SERVER_URL = "http://192.168.1.13:11434/v1/chat/completions";
    private static final String MODEL_NAME = "sent";
    
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

            // Simplified Prompt to match the Modelfile template: "Crea una oración con: {{ .Prompt }}"
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            
            String userContent = "Keywords: " + (keywords.isEmpty() ? "---" : keywords);
            if (!contextText.isEmpty()) {
                userContent += ", Context: " + contextText;
            }
            userMsg.put("content", userContent);
            messages.put(userMsg);

            /*
                ##############################################
                This is the modelfile from the generated model
                ##############################################

                PARAMETER stop "<end_of_turn>"
                PARAMETER stop "<eos>"
                PARAMETER temperature 1.0
                PARAMETER min_p 0.0
                PARAMETER top_k 64
                PARAMETER top_p 0.95
                PARAMETER num_predict 32768
            * */
            json.put("messages", messages);
            json.put("max_tokens", 40); // Matching num_predict from Modelfile
            json.put("temperature", 0.5); // Whoops... I forget this...
            // json.put("min_p", 0.0); // Esto está asi debido a que debo revisar si está bien.
            json.put("top_k", 64);
            json.put("top_p", 0.95);
            json.put("min_p", 0.95);
            json.put("repeat_penalty", 1.0);
            json.put("num_predict", 67);

            // Critical: Add "model" and other control tokens to stop array
            JSONArray stopTokens = new JSONArray();
            stopTokens.put("\n");
            stopTokens.put("model");
            stopTokens.put("<start_of_turn>");
            stopTokens.put("<end_of_turn>");
            stopTokens.put("Resultado:");
            json.put("stop", stopTokens);

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
