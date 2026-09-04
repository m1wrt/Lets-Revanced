package com.mike.lets.textEntry;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class TextEntryManager {
    private final BlurryInput blurryInput = new BlurryInput();
    private String currentSentence = "";
    private List<String> currentPredictions = new ArrayList<>();
    
    public boolean letterModeUI = true;
    public boolean wordModeUI = false;
    public int wordIndex = -1;
    public int predictionPage = 0;

    public void initialize(android.content.Context context, String contextText) {
        blurryInput.initialize(context, contextText);
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
        if (gazeType == 6 || gazeType == 7 || gazeType == 1) {
            blurryInput.addGaze(gazeType);
            updatePredictions();
        } else if (gazeType == 2) { // BR -> MÁS PALABRAS (in letter mode too)
            int wordsInPage = 4;
            int nextStart = (predictionPage + 1) * wordsInPage;
            if (nextStart < currentPredictions.size()) {
                predictionPage++;
            } else {
                predictionPage = 0; // Cycle back
            }
        } else if (gazeType == 5) { // Closed -> CAMBIAR a Word Mode
            if (!currentPredictions.isEmpty()) {
                letterModeUI = false;
                wordModeUI = true;
                predictionPage = 0;
            }
        } else if (gazeType == 3) { // Up -> Borrar
            blurryInput.deleteLast();
            updatePredictions();
        }
    }

    private void handleWordMode(int gazeType) {
        if (currentPredictions.isEmpty()) {
            letterModeUI = true;
            wordModeUI = false;
            return;
        }

        int wordsInPage = 3; // Now using 3 words per page to leave BR for "More Words"
        int startIdx = predictionPage * wordsInPage;

        if (gazeType == 5 || gazeType == 2) { // Closed (CAMBIAR) or BR (MAS PALABRAS)
            int nextStart = (predictionPage + 1) * wordsInPage;
            if (nextStart >= currentPredictions.size()) {
                // If we reach the end, return to letter mode
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
            currentSentence += word; // No space for single letters (spelling mode)
        } else {
            currentSentence += word + " "; // Space for full words
        }
        blurryInput.clear();
        currentPredictions.clear();
        wordIndex = -1;
        letterModeUI = true;
        wordModeUI = false;
    }

    public String getCurrentText() {
        return currentSentence + (letterModeUI ? blurryInput.getInputLog() : "");
    }
    
    public List<String> getPredictions() {
        return currentPredictions;
    }
}
