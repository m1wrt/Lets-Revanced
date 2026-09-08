package com.mike.lets.textEntry;

import android.util.Log;
import com.mike.lets.UserDataManager;
import java.util.ArrayList;
import java.util.List;

public class TextEntryManager {
    private final BlurryInput blurryInput = new BlurryInput();
    private final LlamaCppClient llmClient = new LlamaCppClient();
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
        llmClient.initialize(context, "gemma3-1b.gguf", new LlamaCppClient.LLMCallback() {
            @Override
            public void onSuccess(String prediction) {
                Log.d("TextEntryManager", "LLM Initialized: " + prediction);
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
        // También inicializamos BlurryInput con el nuevo contexto para el filtrado de palabras
        blurryInput.initialize(blurryInput.getContext(), this.conversationContext);
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
        blurryInput.initialize(blurryInput.getContext(), ""); // Limpiar también el contexto del buscador de palabras
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
        this.llmPrediction = "";
        triggerLLM();
    }

    private void triggerLLM() {
        // Solo procesamos palabras reales (currentSentence), ignoramos los rangos de letras [A-F...]
        String llmKeywords = currentSentence.trim();
        Log.d("TextEntryManager", "triggerLLM called with keywords: '" + llmKeywords + "'");

        if (llmKeywords.isEmpty()) {
            Log.d("TextEntryManager", "Keywords empty, clearing prediction");
            llmPrediction = "";
            lastLlmInput = "";
            return;
        }

        if (!llmClient.isReady()) {
            Log.d("TextEntryManager", "LLM not ready yet");
            llmPrediction = "Cargando modelo de lenguaje...";
            lastLlmInput = ""; // Asegurar que reintente cuando esté listo
            return;
        }

        // If input hasn't changed, don't spam
        String fullInput = conversationContext + "|" + llmKeywords;
        if (fullInput.equals(lastLlmInput)) {
            Log.d("TextEntryManager", "Input hasn't changed, skipping LLM");
            return;
        }
        
        llmClient.getCompletion(conversationContext, llmKeywords, new LlamaCppClient.LLMCallback() {
            @Override
            public void onSuccess(String prediction) {
                lastLlmInput = fullInput; // Solo marcar como "procesado" si hubo éxito
                if (prediction.isEmpty()) {
                    setLlmPrediction("(Sin respuesta)");
                } else {
                    setLlmPrediction(prediction);
                }
            }

            @Override
            public void onError(String error) {
                Log.e("TextEntryManager", "LLM Error: " + error);
                setLlmPrediction("Error: " + error);
                lastLlmInput = ""; // Permitir reintento
            }
        });
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

    public void release() {
        llmClient.release();
    }
}
