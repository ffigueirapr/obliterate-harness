package com.obliterateharness.domain;

import java.util.List;
import java.util.Optional;

/**
 * Finds a unit type's manuscript (prose/script) and word-count attributes. Prefers the attribute role;
 * falls back to well-known keys so projects created before roles existed keep working.
 */
public final class Manuscript {
    private Manuscript() {}

    private static final List<String> TEXT_KEYS = List.of("text", "texto", "manuscript", "manuscrito", "prose", "script", "roteiro");
    private static final List<String> WORD_KEYS = List.of("words", "word_count", "palavras");

    public static Optional<AttrDef> textAttr(NodeTypeDef type) {
        return type.attrWithRole("manuscript").or(() -> TEXT_KEYS.stream()
                .flatMap(k -> type.attr(k).stream())
                .filter(a -> "text_long".equalsIgnoreCase(a.dataType()))
                .findFirst());
    }

    public static Optional<AttrDef> wordCountAttr(NodeTypeDef type) {
        return type.attrWithRole("word_count").or(() -> WORD_KEYS.stream()
                .flatMap(k -> type.attr(k).stream())
                .filter(a -> "int".equalsIgnoreCase(a.dataType()))
                .findFirst());
    }

    /** Counts words: runs of characters that contain at least one letter or digit. */
    public static long countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        long n = 0;
        for (String token : text.trim().split("\\s+")) {
            if (token.codePoints().anyMatch(Character::isLetterOrDigit)) n++;
        }
        return n;
    }
}
