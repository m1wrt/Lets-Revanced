package com.mike.lets.textEntry;

import android.util.Log;
import com.mike.lets.UserDataManager;
import java.util.ArrayList;
import java.util.List;

public class TextEntryManager {
    private final BlurryInput blurryInput = new BlurryInput();
    private final com.mike.lets.AI.GeminiManager geminiManager = new com.mike.lets.AI.GeminiManager();
    private String currentSentence = "";
    private String conversationContext = "";
    private String lastLlmInput = "";
    private List<String> currentPredictions = new ArrayList<>();
    private String llmPrediction = "";
    public boolean justSelectedWord = false;

    public boolean letterModeUI = true;
    public boolean wordModeUI = false;
    public int wordIndex = -1;
    public int predictionPage = 0;

    public void initialize(android.content.Context context, String contextText) {
        this.conversationContext = contextText != null ? contextText : "";
        blurryInput.initialize(context, contextText);
        geminiManager.init(context);
    }

    public void setConversationContext(String context) {
        this.conversationContext = context != null ? context : "";
    }

    public void manageUserInput(int gazeType, boolean isLive) {
        if (gazeType == 0) return; // Straight/Nothing

        if (letterModeUI) {
            handleLetterMode(gazeType);
        } else if (wordModeUI) {
            handleWordMode(gazeType);
        }
    }

    private void handleLetterMode(int gazeType) {
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
        } else if (gazeType == 5) { // Closed -> CAMBIAR a Word Mode
            if (!currentPredictions.isEmpty()) {
                letterModeUI = false;
                wordModeUI = true;
                predictionPage = 0;
                // YA NO LLAMAMOS A triggerLLM() AQUÍ porque solo queremos procesar palabras reales
            }
        } else if (gazeType == 3) { // Up -> Borrar
            if (blurryInput.isEmpty()) {
                deleteLastWordFromSentence();
            } else {
                blurryInput.deleteLast();
            }
            updatePredictions();
        }
    }

    private void handleWordMode(int gazeType) {
        if (currentPredictions.isEmpty()) {
            letterModeUI = true;
            wordModeUI = false;
            return;
        }

        int wordsInPage = 3; 
        int startIdx = predictionPage * wordsInPage;

        if (gazeType == 5 || gazeType == 2) { // Closed (CAMBIAR) or BR (MAS PALABRAS)
            int nextStart = (predictionPage + 1) * wordsInPage;
            if (nextStart >= currentPredictions.size()) {
                letterModeUI = true;
                wordModeUI = false;
                predictionPage = 0;
            } else {
                predictionPage++;
            }
        } else if (gazeType == 3) { // Up -> COMPLETO / Volver a letras
            letterModeUI = true;
            wordModeUI = false;
        } else {
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
        }
    }

    private void updatePredictions() {
        currentPredictions = blurryInput.getPredictions();
        predictionPage = 0;
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
        
        this.conversationContext = ""; // Limpiar contexto por cada palabra confirmada
        this.justSelectedWord = true;
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
        triggerLLM();
    }

    private void triggerLLM() {
        // Solo procesamos palabras reales (currentSentence), ignoramos los rangos de letras [A-F...]
        String llmKeywords = currentSentence.trim();

        // If input hasn't changed, don't spam
        String fullInput = conversationContext + "|" + llmKeywords;
        if (fullInput.equals(lastLlmInput)) return;
        lastLlmInput = fullInput;
        
        if (llmKeywords.isEmpty()) {
            llmPrediction = "";
            return;
        }

        // Get language and model from UserDataManager
        UserDataManager userDataManager = (UserDataManager) blurryInput.getContext().getApplicationContext();
        String lang = userDataManager.getLanguage();
        String model = "gemma-4-E2B-it.litertlm"; 

        geminiManager.generate(llmKeywords, conversationContext, lang, model);
    }

    public void setLlmPrediction(String prediction) {
        if (prediction != null && prediction.startsWith("SP-")) {
            this.llmPrediction = prediction.substring(3);
        } else {
            this.llmPrediction = prediction;
        }
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
}
