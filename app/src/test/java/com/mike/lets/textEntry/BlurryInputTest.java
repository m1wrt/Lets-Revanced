package com.mike.lets.textEntry;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;

public class BlurryInputTest {
    private BlurryInput blurryInput;

    @Before
    public void setUp() {
        blurryInput = new BlurryInput();
    }

    @Test
    public void testNormalization() {
        assertEquals("hola", blurryInput.normalize("Hola"));
        assertEquals("adios", blurryInput.normalize("Adiós"));
        assertEquals("camion", blurryInput.normalize("Camión"));
        assertEquals("n", blurryInput.normalize("ñ"));
        assertEquals("nino", blurryInput.normalize("Niño"));
        assertEquals("test", blurryInput.normalize("Test 123!"));
    }

    @Test
    public void testEncoding() {
        // a-f(0), g-m(1), n-t(2), u-z(3)
        // h(1) o(2) l(1) a(0) -> 1210
        assertEquals("1210", blurryInput.encode("hola"));
        // m(1) u(3) n(2) d(0) o(2) -> 13202
        assertEquals("13202", blurryInput.encode("mundo"));
    }

    @Test
    public void testExactMatch() {
        blurryInput.initialize(null, "");
        blurryInput.addExtendedWord("hola");
        List<String> matches = blurryInput.getMatchingWords("1210");
        assertTrue(matches.contains("hola"));
    }

    @Test
    public void testPrefixMatch() {
        blurryInput.initialize(null, "");
        blurryInput.addExtendedWord("camioneta");
        // c(0) a(0) m(1) i(1) o(2) n(2) ...
        // "001122"
        List<String> matches = blurryInput.getMatchingWords("001122");
        assertTrue(matches.contains("camioneta"));
        
        // Short prefix (<3) should NOT match
        List<String> shortMatches = blurryInput.getMatchingWords("00");
        assertFalse(shortMatches.contains("camioneta"));
    }

    @Test
    public void testTooShortWord() {
        blurryInput.initialize(null, "");
        blurryInput.addExtendedWord("hi");
        // h(1) i(1)
        List<String> matches = blurryInput.getMatchingWords("11");
        assertTrue(matches.contains("hi"));
        
        // Should not match "112" (input longer than word)
        List<String> longerMatches = blurryInput.getMatchingWords("112");
        assertFalse(longerMatches.contains("hi"));
    }

    @Test
    public void testEmptyInput() {
        List<String> matches = blurryInput.getMatchingWords("");
        assertEquals(0, matches.size());
        
        matches = blurryInput.getMatchingWords(null);
        assertEquals(0, matches.size());
    }

    @Test
    public void testDuplicateRemovalAndSorting() {
        blurryInput.initialize(null, "casa"); // Context is "casa"
        blurryInput.addExtendedWord("casa");
        blurryInput.addExtendedWord("casa"); // Duplicate in extended
        
        List<String> matches = blurryInput.getMatchingWords("0020"); // c(0) a(0) s(2) a(0)
        assertEquals(1, matches.size());
        assertEquals("casa", matches.get(0));
    }

    @Test
    public void testPagination() {
        List<String> words = Arrays.asList("A", "B", "C", "D", "E");
        
        List<String> page1 = blurryInput.getWordPage(words, 1, 2);
        assertEquals(2, page1.size());
        assertEquals("A", page1.get(0));
        assertEquals("B", page1.get(1));
        
        List<String> page3 = blurryInput.getWordPage(words, 3, 2);
        assertEquals(1, page3.size());
        assertEquals("E", page3.get(0));
        
        List<String> invalidPage = blurryInput.getWordPage(words, 4, 2);
        assertTrue(invalidPage.isEmpty());
        
        List<String> invalidWordsPerPage = blurryInput.getWordPage(words, 1, 0);
        assertTrue(invalidWordsPerPage.isEmpty());
        
        List<String> emptyList = blurryInput.getWordPage(new ArrayList<>(), 1, 10);
        assertTrue(emptyList.isEmpty());
    }
}
