package com.mike.lets.textEntry;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sistema de busqueda de palabras por rangos de mirada.
 * Grupos: a-f(0), g-m(1), n-t(2), u-z(3)
 */
public class BlurryInput {
    private static final String TAG = "BlurryInput";
    private static final int CHARACTER_THRESHOLD = 3;

    // Mapping for legacy addGaze (6:LeftUp, 7:RightUp, 1:Left, 2:Right)
    private static final Map<Integer, Character> GAZE_TO_CODE = new HashMap<>();
    static {
        GAZE_TO_CODE.put(6, '0'); // a-f
        GAZE_TO_CODE.put(7, '1'); // g-m
        GAZE_TO_CODE.put(1, '2'); // n-t
        GAZE_TO_CODE.put(2, '3'); // u-z
    }

    private final List<String> wordList = new ArrayList<>();
    private final List<String> extendedWordList = new ArrayList<>();
    private final Map<String, String> wordToCodeMap = new HashMap<>();
    private String currentContext = "";
    private final StringBuilder internalInputCode = new StringBuilder();

    /**
     * Inicializa el diccionario y precalcula los codigos de mirada.
     * @param context Contexto de Android para acceder a assets.
     * @param contextText Texto de contexto para priorizar predicciones.
     */
    public void initialize(Context context, String contextText) {
        this.currentContext = contextText != null ? contextText.toLowerCase() : "";
        wordList.clear();
        // Requirement 7: Limpia los datos anteriores para evitar duplicados.
        // Mantenemos extendedWordList si se desea persistencia manual, 
        // pero la limpiamos aqui para cumplir estrictamente con "Limpia los datos anteriores".
        extendedWordList.clear();
        wordToCodeMap.clear();
        internalInputCode.setLength(0);

        loadWordsFromAssets(context, "SpanishWords.txt");
        
        rebuildCodeMap();
    }

    private void rebuildCodeMap() {
        // Requirement 4: Elimina duplicados entre wordList y extendedWordList.
        LinkedHashSet<String> uniqueExtended = new LinkedHashSet<>(extendedWordList);
        extendedWordList.clear();
        extendedWordList.addAll(uniqueExtended);

        LinkedHashSet<String> uniqueWords = new LinkedHashSet<>(wordList);
        uniqueWords.removeAll(extendedWordList);
        wordList.clear();
        wordList.addAll(uniqueWords);
        
        // Precompute codes (Requirement 6)
        for (String word : extendedWordList) {
            wordToCodeMap.put(word, encode(normalize(word)));
        }
        for (String word : wordList) {
            wordToCodeMap.put(word, encode(normalize(word)));
        }
    }

    private void loadWordsFromAssets(Context context, String fileName) {
        if (context == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(fileName)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty()) {
                    wordList.add(word);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading " + fileName, e);
        }
    }

    /**
     * Normaliza una palabra: minusculas, sin acentos y solo caracteres a-z.
     */
    public String normalize(String word) {
        if (word == null) return "";
        String normalized = Normalizer.normalize(word, Normalizer.Form.NFD);
        normalized = Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(normalized).replaceAll("");
        return normalized.toLowerCase().replaceAll("[^a-z]", "");
    }

    /**
     * Codifica una palabra normalizada a una secuencia de grupos (0-3).
     */
    public String encode(String normalized) {
        StringBuilder sb = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            if (c >= 'a' && c <= 'f') sb.append('0');
            else if (c >= 'g' && c <= 'm') sb.append('1');
            else if (c >= 'n' && c <= 't') sb.append('2');
            else if (c >= 'u' && c <= 'z') sb.append('3');
        }
        return sb.toString();
    }

    /**
     * Devuelve las palabras que coinciden con el codigo de entrada.
     * Soporta coincidencia exacta y por prefijo (si len >= 3).
     */
    public List<String> getMatchingWords(String inputCode) {
        if (inputCode == null || inputCode.isEmpty()) return new ArrayList<>();

        List<String> contextRelevantExtended = new ArrayList<>();
        List<String> otherExtended = new ArrayList<>();
        List<String> normalMatches = new ArrayList<>();

        for (String word : extendedWordList) {
            if (isCodeMatch(wordToCodeMap.get(word), inputCode)) {
                if (isContextRelevant(word)) {
                    contextRelevantExtended.add(word);
                } else {
                    otherExtended.add(word);
                }
            }
        }

        for (String word : wordList) {
            if (isCodeMatch(wordToCodeMap.get(word), inputCode)) {
                normalMatches.add(word);
            }
        }

        List<String> result = new ArrayList<>();
        result.addAll(contextRelevantExtended);
        result.addAll(otherExtended);
        result.addAll(normalMatches);
        return result;
    }

    private boolean isCodeMatch(String wordCode, String inputCode) {
        if (wordCode == null) return false;
        if (wordCode.length() == inputCode.length()) {
            return wordCode.equals(inputCode);
        } else if (inputCode.length() >= CHARACTER_THRESHOLD && wordCode.length() > inputCode.length()) {
            return wordCode.startsWith(inputCode);
        }
        return false;
    }

    private boolean isContextRelevant(String word) {
        if (currentContext.isEmpty()) return false;
        String w = word.toLowerCase();
        return currentContext.contains(w) || w.contains(currentContext);
    }

    /**
     * Gestiona la paginacion de resultados.
     */
    public List<String> getWordPage(List<String> words, int page, int wordsPerPage) {
        if (words == null || words.isEmpty() || page < 1 || wordsPerPage <= 0) {
            return new ArrayList<>();
        }
        int start = (page - 1) * wordsPerPage;
        if (start >= words.size()) return new ArrayList<>();
        int end = Math.min(start + wordsPerPage, words.size());
        return new ArrayList<>(words.subList(start, end));
    }

    // --- Metodos legacy para compatibilidad con TextEntryManager ---

    public void addGaze(int gazeType) {
        Character code = GAZE_TO_CODE.get(gazeType);
        if (code != null) {
            internalInputCode.append(code);
        }
    }

    public void clear() {
        internalInputCode.setLength(0);
    }

    public void deleteLast() {
        if (internalInputCode.length() > 0) {
            internalInputCode.setLength(internalInputCode.length() - 1);
        }
    }

    public List<String> getPredictions() {
        return getMatchingWords(internalInputCode.toString());
    }

    public String getInputLog() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < internalInputCode.length(); i++) {
            char code = internalInputCode.charAt(i);
            String range = "";
            if (code == '0') range = "A-F";
            else if (code == '1') range = "G-M";
            else if (code == '2') range = "N-T";
            else if (code == '3') range = "U-Z";
            
            if (!range.isEmpty()) {
                sb.append("[").append(range).append("] ");
            }
        }
        return sb.toString().trim();
    }
    
    // Metodo para facilitar pruebas sin Context
    public void addExtendedWord(String word) {
        if (word != null && !word.isEmpty()) {
            if (!extendedWordList.contains(word)) {
                extendedWordList.add(word);
                // Requirement 6: Aseguramos que el codigo este precalculado
                wordToCodeMap.put(word, encode(normalize(word)));
                // Requirement 4: Elimina duplicados entre wordList y extendedWordList
                wordList.remove(word);
            }
        }
    }
}
