package com.mike.lets;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestor centralizado de todos los menús de la aplicación cargados desde AppMenus.json.
 */
public class MenuManager {
    private static final String TAG = "MenuManager";

    public static class MenuOption {
        public int gazeInput = -1;
        public String position = "";
        public String label = "";
        public String action = "";
        public String targetCategoryId = "";
        public String id = "";
    }

    public static class MenuCategory {
        public String id = "";
        public String title = "";
        public String buttonText = "";
        public List<PhraseOption> phrases = new ArrayList<>();
    }

    public static class PhraseOption {
        public int gazeInput = -1;
        public String position = "";
        public String text = "";
    }

    public static class Menu {
        public String id = "";
        public String title = "";
        public String type = "";
        public String description = "";
        public List<MenuOption> options = new ArrayList<>();
        public Map<String, MenuCategory> subcategories = new HashMap<>();
    }

    private final Map<String, Menu> menusMap = new HashMap<>();

    public void loadAppMenus(Context context) {
        menusMap.clear();
        String jsonContent = null;

        try {
            InputStream is = context.getAssets().open("AppMenus.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            jsonContent = sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error reading AppMenus.json from assets", e);
        }

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            Log.e(TAG, "No AppMenus.json content found");
            return;
        }

        try {
            JSONObject root = new JSONObject(jsonContent);
            JSONArray menusArray = root.optJSONArray("menus");
            if (menusArray != null) {
                for (int i = 0; i < menusArray.length(); i++) {
                    JSONObject menuObj = menusArray.getJSONObject(i);
                    Menu menu = new Menu();
                    menu.id = menuObj.optString("id", "");
                    menu.title = menuObj.optString("title", "");
                    menu.type = menuObj.optString("type", "");
                    menu.description = menuObj.optString("description", "");

                    JSONArray optionsArr = menuObj.optJSONArray("options");
                    if (optionsArr != null) {
                        for (int j = 0; j < optionsArr.length(); j++) {
                            JSONObject optObj = optionsArr.getJSONObject(j);
                            MenuOption opt = new MenuOption();
                            opt.gazeInput = optObj.optInt("gazeInput", -1);
                            opt.position = optObj.optString("position", "");
                            opt.label = optObj.optString("label", "");
                            opt.action = optObj.optString("action", "");
                            opt.targetCategoryId = optObj.optString("targetCategoryId", "");
                            opt.id = optObj.optString("id", "");
                            menu.options.add(opt);
                        }
                    }

                    JSONArray catArr = menuObj.optJSONArray("categories");
                    if (catArr != null) {
                        for (int k = 0; k < catArr.length(); k++) {
                            JSONObject catObj = catArr.getJSONObject(k);
                            MenuCategory cat = new MenuCategory();
                            cat.id = catObj.optString("id", "");
                            cat.title = catObj.optString("title", "");
                            cat.buttonText = catObj.optString("buttonText", "");

                            JSONArray phraseArr = catObj.optJSONArray("phrases");
                            if (phraseArr != null) {
                                for (int p = 0; p < phraseArr.length(); p++) {
                                    JSONObject pObj = phraseArr.getJSONObject(p);
                                    PhraseOption po = new PhraseOption();
                                    po.gazeInput = pObj.optInt("gazeInput", -1);
                                    po.position = pObj.optString("position", "");
                                    po.text = pObj.optString("text", "");
                                    cat.phrases.add(po);
                                }
                            }
                            menu.subcategories.put(cat.id, cat);
                        }
                    }

                    menusMap.put(menu.id, menu);
                }
            }
            Log.d(TAG, "AppMenus.json successfully loaded: " + menusMap.size() + " menus parsed.");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing AppMenus.json", e);
        }
    }

    public Menu getMenu(String id) {
        return menusMap.get(id);
    }

    public MenuOption getOptionByGaze(String menuId, int gazeInput) {
        Menu menu = getMenu(menuId);
        if (menu != null && menu.options != null) {
            for (MenuOption opt : menu.options) {
                if (opt.gazeInput == gazeInput) {
                    return opt;
                }
            }
        }
        return null;
    }
}
