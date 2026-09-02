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
        if (gazeType == 6 || gazeType == 7 || gazeType == 1 || gazeType == 2) {
            blurryInput.addGaze(gazeType);
            updatePredictions();
        } else if (gazeType == 5) { // Closed -> Cambiar a Word Mode if predictions exist
            if (!currentPredictions.isEmpty()) {
                letterModeUI = false;
                wordModeUI = true;
                wordIndex = 0;
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

        // Use Left/Right (1/2) to navigate predictions
        if (gazeType == 1) { // Left
            wordIndex = (wordIndex - 1 + currentPredictions.size()) % currentPredictions.size();
        } else if (gazeType == 2) { // Right
            wordIndex = (wordIndex + 1) % currentPredictions.size();
        } else if (gazeType == 5) { // Closed -> Select word
            selectWord(currentPredictions.get(wordIndex));
        } else if (gazeType == 3) { // Up -> Back to Letter Mode
            letterModeUI = true;
            wordModeUI = false;
        }
    }

    private void updatePredictions() {
        currentPredictions = blurryInput.getPredictions();
        if (currentPredictions.isEmpty()) {
            wordIndex = -1;
        } else {
            wordIndex = 0;
        }
    }

    private void selectWord(String word) {
        currentSentence += word + " ";
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
