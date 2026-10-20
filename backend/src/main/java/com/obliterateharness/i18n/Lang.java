package com.obliterateharness.i18n;

/** Supported languages. English is the default. */
public enum Lang {
    en, pt;

    /** Accepts "en", "pt", "pt-BR", "en-US"... Anything else falls back to the given default. */
    public static Lang parse(String value, Lang fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim().toLowerCase();
        if (v.startsWith("pt")) return pt;
        if (v.startsWith("en")) return en;
        return fallback;
    }
}
