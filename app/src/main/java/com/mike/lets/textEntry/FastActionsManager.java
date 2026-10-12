package com.mike.lets.textEntry;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestor de frases e instrucciones del archivo FastActions.json.
 *
 * Carga dinámicamente las categorías y frases del archivo FastActions.json
 * presente en el .zip del paquete o desde assets.
 */
public class FastActionsManager {
    private static final String TAG = "FastActionsManager";

    public static class Category {
        public String id;
        public String title;
        public String buttonText;
        public List<String> phrases = new ArrayList<>();
    }

    private final Map<String, Category> categoriesMap = new HashMap<>();

    public void loadFastActions(Context context, String customPath) {
        categoriesMap.clear();
        String jsonContent = null;

        if (customPath != null && !customPath.isEmpty()) {
            File file = new File(customPath);
            if (file.exists()) {
                try {
                    jsonContent = readStream(new FileInputStream(file));
                } catch (Exception e) {
                    Log.e(TAG, "Error reading custom FastActions.json at " + customPath, e);
                }
            }
        }

        if (jsonContent == null) {
            try {
                InputStream is = context.getAssets().open("FastActions.json");
                jsonContent = readStream(is);
            } catch (Exception e) {
                Log.e(TAG, "Error reading default FastActions.json from assets", e);
            }
        }

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            Log.e(TAG, "No FastActions.json content found");
            return;
        }

        try {
            JSONObject root = new JSONObject(jsonContent);
            JSONArray catArray = root.optJSONArray("categories");
            if (catArray != null) {
                for (int i = 0; i < catArray.length(); i++) {
                    JSONObject catObj = catArray.getJSONObject(i);
                    Category cat = new Category();
                    cat.id = catObj.optString("id", "");
                    cat.title = catObj.optString("title", "");
                    cat.buttonText = catObj.optString("buttonText", "");

                    JSONArray phraseArr = catObj.optJSONArray("phrases");
                    if (phraseArr != null) {
                        for (int j = 0; j < phraseArr.length(); j++) {
                            cat.phrases.add(phraseArr.getString(j));
                        }
                    }
                    categoriesMap.put(cat.id, cat);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing FastActions.json", e);
        }
    }

    public Category getCategory(String id) {
        return categoriesMap.get(id);
    }

    private String readStream(InputStream is) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error reading stream", e);
            return null;
        }
    }
}
