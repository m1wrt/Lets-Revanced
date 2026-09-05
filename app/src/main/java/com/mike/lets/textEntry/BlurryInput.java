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
 * Sistema de entrada de texto optimizado para "letra por letra" y predicción por grupos.
 * Mapea códigos de mirada a grupos de letras:
 * 0: a-f, 1: g-m, 2: n-t, 3: u-z
 */
public class BlurryInput {
    private static final String TAG = "BlurryInput";
    // Mapeo de mirada a código interno (6:A-F, 7:G-M, 1:N-T, 2:U-Z)
    private static final Map<Integer, Character> GAZE_TO_CODE = new HashMap<>();
    static {
        GAZE_TO_CODE.put(6, '0'); // a-f
        GAZE_TO_CODE.put(7, '1'); // g-m
        GAZE_TO_CODE.put(1, '2'); // n-t
        GAZE_TO_CODE.put(2, '3'); // u-z
    }

    private final List<String> wordList = new ArrayList<>();
    private final Map<String, String> wordToCodeMap = new HashMap<>();
    private final StringBuilder internalInputCode = new StringBuilder();
    private String currentContext = "";
    private Context context;

    /**
     * Inicializa el sistema, carga el diccionario y asegura que las letras individuales estén presentes.
     */
    public void initialize(Context context, String contextText) {
        this.context = context;
        this.currentContext = contextText != null ? contextText.toLowerCase() : "";
        wordList.clear();
        wordToCodeMap.clear();
        internalInputCode.setLength(0);

        // 1. Agregar letras individuales para permitir el deletreo letra por letra
        for (char c = 'a'; c <= 'z'; c++) {
            String letter = String.valueOf(c);
            wordList.add(letter);
            wordToCodeMap.put(letter, encode(letter));
        }

        // 2. Cargar diccionario principal
        loadWordsFromAssets(context, "SpanishWords.txt");
        
        rebuildCodeMap();
    }

    private void rebuildCodeMap() {
        // Eliminar duplicados manteniendo el orden (letras primero)
        LinkedHashSet<String> uniqueWords = new LinkedHashSet<>(wordList);
        wordList.clear();
        wordList.addAll(uniqueWords);
        
        for (String word : wordList) {
            if (!wordToCodeMap.containsKey(word)) {
                wordToCodeMap.put(word, encode(normalize(word)));
            }
        }
    }

    private void loadWordsFromAssets(Context context, String fileName) {
        if (context == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(fileName)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim().toLowerCase();
                if (!word.isEmpty()) {
                    wordList.add(word);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading dictionary: " + fileName, e);
        }
    }

    /**
     * Normaliza una palabra: minúsculas, sin acentos y solo letras a-z.
     */
    public String normalize(String word) {
        if (word == null) return "";
        String normalized = Normalizer.normalize(word, Normalizer.Form.NFD);
        normalized = Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(normalized).replaceAll("");
        return normalized.toLowerCase().replaceAll("[^a-z]", "");
    }

    /**
     * Codifica una palabra normalizada en una secuencia de grupos (0-3).
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
     * Busca palabras que coincidan con el código de entrada.
     * Prioriza resultados cortos (letras) para el modo "letra por letra".
     */
    public List<String> getMatchingWords(String inputCode) {
        if (inputCode == null || inputCode.isEmpty()) return new ArrayList<>();

        List<String> matches = new ArrayList<>();
        for (String word : wordList) {
            String code = wordToCodeMap.get(word);
            // Coincidencia por prefijo desde el primer carácter para feedback inmediato
            if (code != null && code.startsWith(inputCode)) {
                matches.add(word);
            }
        }

        // Ordenamiento para "letra por letra":
        // 1. Longitud (letras de 1 char primero, luego palabras cortas)
        // 2. Relevancia de contexto
        // 3. Alfabético
        matches.sort((a, b) -> {
            if (a.length() != b.length()) {
                return a.length() - b.length();
            }
            
            boolean aContext = isContextRelevant(a);
            boolean bContext = isContextRelevant(b);
            if (aContext != bContext) return aContext ? -1 : 1;
            
            return a.compareTo(b);
        });

        // Limitar a un número razonable de predicciones
        if (matches.size() > 60) {
            return new ArrayList<>(matches.subList(0, 60));
        }
        return matches;
    }

    private boolean isContextRelevant(String word) {
        return !currentContext.isEmpty() && (currentContext.contains(word) || word.contains(currentContext));
    }

    /**
     * Añade un código de mirada al buffer de entrada actual.
     */
    public void addGaze(int gazeType) {
        Character code = GAZE_TO_CODE.get(gazeType);
        if (code != null) {
            internalInputCode.append(code);
        }
    }

    /**
     * Limpia el buffer de entrada.
     */
    public void clear() {
        internalInputCode.setLength(0);
    }

    /**
     * Borra el último código del buffer.
     */
    public void deleteLast() {
        if (internalInputCode.length() > 0) {
            internalInputCode.setLength(internalInputCode.length() - 1);
        }
    }

    /**
     * Verifica si el buffer de entrada está vacío.
     */
    public boolean isEmpty() {
        return internalInputCode.length() == 0;
    }

    public Context getContext() {
        return context;
    }

    /**
     * Obtiene las predicciones actuales basadas en el buffer interno.
     */
    public List<String> getPredictions() {
        return getMatchingWords(internalInputCode.toString());
    }

    /**
     * Devuelve una representación legible del buffer de entrada.
     */
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
                sb.append(range).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Gestiona la paginación de resultados (Legacy support).
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
    
    /**
     * Añade una palabra personalizada al diccionario (Legacy support / Tests).
     */
    public void addExtendedWord(String word) {
        if (word != null && !word.isEmpty() && !wordList.contains(word)) {
            wordList.add(word);
            wordToCodeMap.put(word, encode(normalize(word)));
        }
    }
}
