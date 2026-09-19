package com.mike.lets.textEntry;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.Objects;

public class TranslationManager {
    private static final String TAG = "TranslationManager";
    private Translator toSpanishTranslator;
    private Translator fromSpanishTranslator;
    private boolean isInitialized = false;
    private String currentLanguage = "Spanish";

    public interface TranslationCallback {
        void onSuccess(String translatedText);
        void onError(Exception e);
    }

    public void initialize(Context context, String targetLanguage, OnSuccessListener<Void> successListener) {
        this.currentLanguage = targetLanguage;
        
        if (Objects.equals(targetLanguage, "Spanish")) {
            isInitialized = true;
            successListener.onSuccess(null);
            return;
        }

        String mlKitLanguage = getMlKitLanguage(targetLanguage);
        if (mlKitLanguage == null) {
            Log.e(TAG, "Unsupported language: " + targetLanguage);
            return;
        }

        // Target to Spanish
        TranslatorOptions toOptions = new TranslatorOptions.Builder()
                .setSourceLanguage(mlKitLanguage)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build();
        toSpanishTranslator = Translation.getClient(toOptions);

        // Spanish to Target
        TranslatorOptions fromOptions = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.SPANISH)
                .setTargetLanguage(mlKitLanguage)
                .build();
        fromSpanishTranslator = Translation.getClient(fromOptions);

        DownloadConditions conditions = new DownloadConditions.Builder()
                .requireWifi()
                .build();

        toSpanishTranslator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v -> {
                    fromSpanishTranslator.downloadModelIfNeeded(conditions)
                            .addOnSuccessListener(v2 -> {
                                isInitialized = true;
                                successListener.onSuccess(null);
                            })
                            .addOnFailureListener(e -> Log.e(TAG, "Error downloading from-Spanish model", e));
                })
                .addOnFailureListener(e -> Log.e(TAG, "Error downloading to-Spanish model", e));
    }

    public void translateToSpanish(String text, TranslationCallback callback) {
        if (!isInitialized || Objects.equals(currentLanguage, "Spanish") || toSpanishTranslator == null) {
            callback.onSuccess(text);
            return;
        }

        toSpanishTranslator.translate(text)
                .addOnSuccessListener(callback::onSuccess)
                .addOnFailureListener(callback::onError);
    }

    public void translateFromSpanish(String text, TranslationCallback callback) {
        if (!isInitialized || Objects.equals(currentLanguage, "Spanish") || fromSpanishTranslator == null) {
            callback.onSuccess(text);
            return;
        }

        fromSpanishTranslator.translate(text)
                .addOnSuccessListener(callback::onSuccess)
                .addOnFailureListener(callback::onError);
    }

    private String getMlKitLanguage(String language) {
        if (Objects.equals(language, "Japanese")) return TranslateLanguage.JAPANESE;
        if (Objects.equals(language, "Spanish")) return TranslateLanguage.SPANISH;
        return null;
    }

    public void close() {
        if (toSpanishTranslator != null) toSpanishTranslator.close();
        if (fromSpanishTranslator != null) fromSpanishTranslator.close();
    }
}
