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

    /**
     * 回归：NBSP(U+00A0) 不按空白处理。
     * 迁移时一度改用 Kotlin 的 Char.isWhitespace()，它把 SpaceChar（NBSP 等）也算空白，
     * 会把 Java 原件的一个 token 拆成两个。这里固定 java.lang.Character.isWhitespace 语义。
     */
    @Test
    public void keepsNonBreakingSpaceInsideToken() {
        assertEquals(
            Arrays.asList("one\u00A0two"),
            ArgumentTokenizer.tokenize("one\u00A0two")
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
