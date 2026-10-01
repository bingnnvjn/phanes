package com.gph.fable.shared.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class ArgumentTokenizerTest {
    @Test
    public void tokenizesQuotesAndEscapes() {
        assertEquals(
            Arrays.asList("one", "two words", "three\"four", "tail\\"),
            ArgumentTokenizer.tokenize("one \"two words\" three\\\"four tail\\")
        );
    }

    @Test
    public void stringifiesTokensWithSpecialCharacters() {
        assertEquals(
            Arrays.asList("\"line\"", "\"quote\\\"slash\\\\\""),
            ArgumentTokenizer.tokenize("line\nquote\\\"slash\\\\", true)
        );
    }
}
