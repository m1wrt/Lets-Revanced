package com.mike.lets.textEntry;

import android.util.Log;
import com.mike.lets.UserDataManager;
import java.util.ArrayList;
import java.util.List;

public class TextEntryManager {
    private final BlurryInput blurryInput = new BlurryInput();
    private final LlamaCppClient llmClient = new LlamaCppClient();
    private final TranslationManager translationManager = new TranslationManager();
    private String currentSentence = "";
    private String conversationContext = "";
    private String lastLlmInput = "";
    private List<String> currentPredictions = new ArrayList<>();
    private String llmPrediction = "";
    private String translatedSentence = "";
    private String translatedWord = "";
    private String currentLanguage = "Spanish";
    public boolean justSelectedWord = false;

    public boolean letterModeUI = true;
    public boolean wordModeUI = false;
    public int wordIndex = -1;
    public int predictionPage = 0;

    public void initialize(android.content.Context context, String contextText) {
        this.conversationContext = contextText != null ? contextText : "";
        
        UserDataManager userDataManager = (UserDataManager) context.getApplicationContext();
        this.currentLanguage = userDataManager.getLanguage();
        
        blurryInput.initialize(context, contextText, this.currentLanguage);
        
        translationManager.initialize(context, this.currentLanguage, v -> {
            Log.d("TextEntryManager", "Translation initialized for " + currentLanguage);
        });

        String savedPath = userDataManager.getLlmModelPath();
        String modelToLoad = (savedPath != null && !savedPath.isEmpty()) ? savedPath : "gemma3-1b.gguf";

        loadModel(context, modelToLoad);
    }

    public void loadModel(android.content.Context context, String modelPath) {
        llmClient.initialize(context, modelPath, new LlamaCppClient.LLMCallback() {
            @Override
            public void onSuccess(String prediction) {
                Log.d("TextEntryManager", "LLM Initialized with: " + modelPath);
                if (!currentSentence.isEmpty()) {
                    triggerLLM();
                }
            }

            @Override
            public void onError(String error) {
                Log.e("TextEntryManager", "LLM Init Error: " + error);
            }
        });
    }

    public void setConversationContext(String context) {
        this.conversationContext = context != null ? context : "";
        // Usamos updateContext para evitar reiniciar el buffer de entrada y el diccionario
        blurryInput.updateContext(this.conversationContext);
    }

    public int manageUserInput(int gazeType, boolean isLive) {
        if (gazeType == 0) return 0; // Straight/Nothing

        if (letterModeUI) {
            return handleLetterMode(gazeType);
        } else if (wordModeUI) {
            return handleWordMode(gazeType);
        }
        return 0;
    }

    private int handleLetterMode(int gazeType) {
        // 6, 7, 1, 2 are letter groups
        if (gazeType == 6 || gazeType == 7 || gazeType == 1 || gazeType == 2) {
            blurryInput.addGaze(gazeType);
            updatePredictions();
            
            // Paging for group UVWXYZ (2)
            if (gazeType == 2 && currentPredictions.size() > 4) {
                int wordsInPage = 4;
                int nextStart = (predictionPage + 1) * wordsInPage;
                if (nextStart < currentPredictions.size()) {
                    predictionPage++;
                } else {
                    predictionPage = 0;
                }
            }
        } else if (gazeType == 3) { // Up -> CAMBIAR a Word Mode
            letterModeUI = false;
            wordModeUI = true;
            predictionPage = 0;
        } else if (gazeType == 5) { // Both Closed -> Borrar
            if (blurryInput.isEmpty()) {
                deleteLastWordFromSentence();
            } else {
                blurryInput.deleteLast();
            }
            updatePredictions();
        }
        return 0;
    }

    private int handleWordMode(int gazeType) {
        if (gazeType == 3) { // Up -> COMPLETO
            return 3;
        }

        int wordsInPage = 3;
        int startIdx = predictionPage * wordsInPage;

        if (gazeType == 5 || gazeType == 2) { // Both Closed or BR (MAS PALABRAS)
            int nextStart = (predictionPage + 1) * wordsInPage;
            if (nextStart < currentPredictions.size()) {
                predictionPage++;
            } else {
                predictionPage = 0; // Cycle back to first page of words
            }
            return 0;
        }

        if (currentPredictions.isEmpty()) {
            return 0;
        }

        // Corner selection: 6(TL), 7(TR), 1(BL)
        int selectedOffset = -1;
        if (gazeType == 6) selectedOffset = 0;
        else if (gazeType == 7) selectedOffset = 1;
        else if (gazeType == 1) selectedOffset = 2;

        if (selectedOffset != -1) {
            int finalIdx = startIdx + selectedOffset;
            if (finalIdx < currentPredictions.size()) {
                selectWord(currentPredictions.get(finalIdx));
            }
        }
        return 0;
    }

    private void updatePredictions() {
        currentPredictions = blurryInput.getPredictions();
        predictionPage = 0;
        updateLiveWordTranslation();
    }

    private void updateLiveWordTranslation() {
        if (currentPredictions.isEmpty() || "Spanish".equalsIgnoreCase(currentLanguage)) {
            translatedWord = "";
            return;
        }
        String topWord = currentPredictions.get(0);
        translationManager.translateToSpanish(topWord, new TranslationManager.TranslationCallback() {
            @Override
            public void onSuccess(String translated) {
                translatedWord = translated;
            }
            @Override
            public void onError(Exception e) {
                translatedWord = "";
            }
        });
    }

    private void selectWord(String word) {
        if (word.length() == 1) {
            currentSentence += word;
        } else {
            currentSentence += word + " ";
        }
        blurryInput.clear();
        currentPredictions = blurryInput.getPredictions();
        predictionPage = 0;
        wordIndex = -1;
        letterModeUI = true;
        wordModeUI = false;
        
        updateLiveSentenceTranslation();

        this.conversationContext = ""; // Limpiar contexto por cada palabra confirmada
        blurryInput.updateContext(""); // Limpiar también el contexto del buscador de palabras sin reiniciar todo
        this.justSelectedWord = true;
        this.llmPrediction = ""; // Limpiar la predicción anterior para no mezclar
        triggerLLM(); // Consulta al LLM al confirmar palabra
    }

    private void deleteLastWordFromSentence() {
        if (currentSentence.isEmpty()) return;
        currentSentence = currentSentence.trim();
        int lastSpace = currentSentence.lastIndexOf(" ");
        if (lastSpace == -1) {
            currentSentence = "";
        } else {
            currentSentence = currentSentence.substring(0, lastSpace + 1);
        }
        updateLiveSentenceTranslation();
        this.llmPrediction = "";
        triggerLLM();
    }

    private void updateLiveSentenceTranslation() {
        if (currentSentence.isEmpty() || "Spanish".equalsIgnoreCase(currentLanguage)) {
            translatedSentence = "";
            return;
        }
        translationManager.translateToSpanish(currentSentence.trim(), new TranslationManager.TranslationCallback() {
            @Override
            public void onSuccess(String translated) {
                translatedSentence = translated;
            }
            @Override
            public void onError(Exception e) {
                translatedSentence = "";
            }
        });
    }

    private void triggerLLM() {
        // Solo procesamos palabras reales (currentSentence), ignoramos los rangos de letras [A-F...]
        final String keywords = currentSentence.trim();
        Log.d("TextEntryManager", "triggerLLM called with keywords: '" + keywords + "'");

        if (keywords.isEmpty()) {
            Log.d("TextEntryManager", "Keywords empty, clearing prediction");
            llmPrediction = "";
            lastLlmInput = "";
            return;
        }

        if (!llmClient.isReady()) {
            Log.d("TextEntryManager", "LLM not ready yet");
            llmPrediction = "Cargando modelo de lenguaje...";
            lastLlmInput = ""; 
            return;
        }

        // Traducir keywords al español antes de procesar si el idioma no es español
        translationManager.translateToSpanish(keywords, new TranslationManager.TranslationCallback() {
            @Override
            public void onSuccess(String spanishKeywords) {
                // Traducir contexto al español también si es necesario
                translationManager.translateToSpanish(conversationContext, new TranslationManager.TranslationCallback() {
                    @Override
                    public void onSuccess(String spanishContext) {
                        processWithLLM(spanishContext, spanishKeywords, keywords);
                    }

                    @Override
                    public void onError(Exception e) {
                        processWithLLM(conversationContext, spanishKeywords, keywords);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                // Fallback a sin traducir si falla
                processWithLLM(conversationContext, keywords, keywords);
            }
        });
    }

    private void processWithLLM(String spanishContext, String spanishKeywords, String originalKeywords) {
        // If input hasn't changed, don't spam
        String fullInput = spanishContext + "|" + spanishKeywords;
        if (fullInput.equals(lastLlmInput)) {
            Log.d("TextEntryManager", "Input hasn't changed, skipping LLM");
            return;
        }

        llmClient.getCompletion(spanishContext, spanishKeywords, new LlamaCppClient.LLMCallback() {
            @Override
            public void onSuccess(String prediction) {
                lastLlmInput = fullInput;
                
                // Limpiar "oracion:" antes de traducir para evitar que ML Kit traduzca el prefijo
                String cleanedSpanish = prediction.replaceAll("(?i)^(oraci[oó]n:?\\s*)", "").trim();

                // Traducir de vuelta al idioma original si no es español
                translationManager.translateFromSpanish(cleanedSpanish, new TranslationManager.TranslationCallback() {
                    @Override
                    public void onSuccess(String translatedPrediction) {
                        setLlmPrediction(translatedPrediction);
                    }

                    @Override
                    public void onError(Exception e) {
                        setLlmPrediction(prediction);
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e("TextEntryManager", "LLM Error: " + error);
                setLlmPrediction("Error: " + error);
                lastLlmInput = ""; 
            }
        });
    }

    public void setLlmPrediction(String prediction) {
        if (prediction == null) {
            this.llmPrediction = "";
            return;
        }

        String cleaned = prediction;
        if (cleaned.startsWith("SP-")) {
            cleaned = cleaned.substring(3);
        }

        // Eliminar "oracion:", "Oración:", etc. al inicio si el modelo lo incluye
        cleaned = cleaned.replaceAll("(?i)^(oraci[oó]n:?\\s*)", "");

        this.llmPrediction = cleaned.trim();
    }

    public String getCurrentText() {
        return currentSentence + (letterModeUI ? " " + blurryInput.getInputLog() : "");
    }
    
    public String getLlmPrediction() {
        return llmPrediction;
    }
    
    public List<String> getPredictions() {
        return currentPredictions;
    }

    public String getTranslatedSentence() {
        return translatedSentence;
    }

    public String getTranslatedWord() {
        return translatedWord;
    }

    public void clearAll() {
        currentSentence = "";
        blurryInput.clear();
        currentPredictions.clear();
        translatedSentence = "";
        translatedWord = "";
        llmPrediction = "";
        letterModeUI = true;
        wordModeUI = false;
        predictionPage = 0;
        wordIndex = -1;
        justSelectedWord = true; // Forza la limpieza de contexto en MainActivity
    }

    public void release() {
        llmClient.release();
    }
}
