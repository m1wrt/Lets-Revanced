package com.mike.lets.textEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlurryInput {
    // Mapping of gaze groups to letters
    // 6: LeftUp -> A-F
    // 7: RightUp -> G-M
    // 1: Left -> N-T
    // 2: Right -> U-Z
    private static final Map<Integer, String> GAZE_TO_LETTERS = new HashMap<>();
    
    static {
        GAZE_TO_LETTERS.put(6, "ABCDEF");
        GAZE_TO_LETTERS.put(7, "GHIJKLM");
        GAZE_TO_LETTERS.put(1, "NOPQRST");
        GAZE_TO_LETTERS.put(2, "UVWXYZ");
    }

    private final List<Integer> inputSequence = new ArrayList<>();
    private final List<String> dictionary = Arrays.asList(
        "HELLO", "WORLD", "ALL", "ALS", "PATIENTS", "SHOULD", "HAVE", "A", "VOICE", "IN", "THE", "DIGITAL", "EYE", "GAZE", "LINK"
    );

    public void addGaze(int gazeType) {
        if (GAZE_TO_LETTERS.containsKey(gazeType)) {
            inputSequence.add(gazeType);
        }
    }

    public void clear() {
        inputSequence.clear();
    }

    public void deleteLast() {
        if (!inputSequence.isEmpty()) {
            inputSequence.remove(inputSequence.size() - 1);
        }
    }

    public List<String> getPredictions() {
        if (inputSequence.isEmpty()) return new ArrayList<>();
        
        List<String> results = new ArrayList<>();
        for (String word : dictionary) {
            if (isMatch(word.toUpperCase())) {
                results.add(word);
            }
        }
        return results;
    }

    private boolean isMatch(String word) {
        if (word.length() != inputSequence.size()) return false;
        
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            int gaze = inputSequence.get(i);
            String allowed = GAZE_TO_LETTERS.get(gaze);
            if (allowed == null || allowed.indexOf(c) == -1) {
                return false;
            }
        }
        return true;
    }
    
    public String getInputLog() {
        StringBuilder sb = new StringBuilder();
        for (int gaze : inputSequence) {
            String letters = GAZE_TO_LETTERS.get(gaze);
            if (letters != null) {
                // Format: [A-F]
                sb.append("[").append(letters.charAt(0)).append("-").append(letters.charAt(letters.length() - 1)).append("] ");
            }
        }
        return sb.toString().trim();
    }
}
