package com.mike.lets;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Gestor centralizado de localización de la app que funciona de forma nativa
 * sin depender de archivos JSON externos (es.json / en.json).
 */
public class LanguageManager {
    private static final String TAG = "LanguageManager";
    private static LanguageManager instance;

    private String currentLanguage = "Spanish"; // "Spanish" or "English"
    private final Map<String, String> currentTranslations = new HashMap<>();

    // Mapas nativos internos para Español e Inglés
    private static final Map<String, String> SPANISH_STRINGS = new HashMap<>();
    private static final Map<String, String> ENGLISH_STRINGS = new HashMap<>();

    static {
        // Español
        SPANISH_STRINGS.put("settings_title", "AJUSTES Y CONFIGURACIÓN");
        SPANISH_STRINGS.put("settings_content", "Aquí podrás administrar el idioma de la app e importar paquetes de lenguaje e IA (.zip).");
        SPANISH_STRINGS.put("language_section_title", "IDIOMA DE LA APP");
        SPANISH_STRINGS.put("current_language", "Idioma actual: Español");
        SPANISH_STRINGS.put("btn_change_language", "CAMBIAR A INGLÉS");
        SPANISH_STRINGS.put("btn_import_package", "IMPORTAR PAQUETE (.ZIP)");
        SPANISH_STRINGS.put("btn_clear_package", "X");
        SPANISH_STRINGS.put("btn_back", "VOLVER");
        SPANISH_STRINGS.put("model_status_prefix", "Modelo: ");
        SPANISH_STRINGS.put("model_default", "Predeterminado (gemma3-1b.gguf)");
        SPANISH_STRINGS.put("restoring_model", "Restaurando modelo...");
        SPANISH_STRINGS.put("loading_default_model", "Cargando modelo predeterminado");
        SPANISH_STRINGS.put("package_restored_toast", "Paquete predeterminado restaurado");
        SPANISH_STRINGS.put("lang_switched_toast", "Idioma cambiado a Español");

        // Inglés
        ENGLISH_STRINGS.put("settings_title", "SETTINGS & CONFIGURATION");
        ENGLISH_STRINGS.put("settings_content", "Here you can manage the app language and import language and AI packages (.zip).");
        ENGLISH_STRINGS.put("language_section_title", "APP LANGUAGE");
        ENGLISH_STRINGS.put("current_language", "Current language: English");
        ENGLISH_STRINGS.put("btn_change_language", "SWITCH TO SPANISH");
        ENGLISH_STRINGS.put("btn_import_package", "IMPORT PACKAGE (.ZIP)");
        ENGLISH_STRINGS.put("btn_clear_package", "X");
        ENGLISH_STRINGS.put("btn_back", "BACK");
        ENGLISH_STRINGS.put("model_status_prefix", "Model: ");
        ENGLISH_STRINGS.put("model_default", "Default (gemma3-1b.gguf)");
        ENGLISH_STRINGS.put("restoring_model", "Restoring model...");
        ENGLISH_STRINGS.put("loading_default_model", "Loading default model");
        ENGLISH_STRINGS.put("package_restored_toast", "Default package restored");
        ENGLISH_STRINGS.put("lang_switched_toast", "Language changed to English");
    }

    private LanguageManager() {}

    public static synchronized LanguageManager getInstance() {
        if (instance == null) {
            instance = new LanguageManager();
        }
        return instance;
    }

    public boolean loadLanguage(Context context, String lang) {
        currentTranslations.clear();
        if ("English".equalsIgnoreCase(lang) || "en".equalsIgnoreCase(lang)) {
            this.currentLanguage = "English";
            currentTranslations.putAll(ENGLISH_STRINGS);
        } else {
            this.currentLanguage = "Spanish";
            currentTranslations.putAll(SPANISH_STRINGS);
        }
        Log.d(TAG, "Cargado idioma " + currentLanguage + " nativamente en memoria sin JSONs externos.");
        return true;
    }

    public String getString(String key) {
        if (currentTranslations.containsKey(key)) {
            return currentTranslations.get(key);
        }
        return key;
    }

    public String getString(String key, Object... args) {
        String template = getString(key);
        try {
            return String.format(template, args);
        } catch (Exception e) {
            return template;
        }
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }

    public String getLanguageCode() {
        return "English".equalsIgnoreCase(currentLanguage) ? "en" : "es";
    }
}
