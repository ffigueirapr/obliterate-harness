package com.obliterateharness.i18n;

import java.util.function.Supplier;

/**
 * Bilingual messages (English is the platform default, Portuguese is optional).
 * The language is per request: the UI sends X-Lang; MCP tools switch to the project's locale.
 */
public final class I18n {
    private static final ThreadLocal<Lang> CURRENT = ThreadLocal.withInitial(() -> Lang.en);

    private I18n() {}

    public static Lang lang() {
        return CURRENT.get();
    }

    public static void set(Lang lang) {
        CURRENT.set(lang == null ? Lang.en : lang);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Picks the text for the current language. */
    public static String t(String en, String pt) {
        return CURRENT.get() == Lang.pt ? pt : en;
    }

    public static <T> T with(Lang lang, Supplier<T> work) {
        Lang previous = CURRENT.get();
        set(lang);
        try {
            return work.get();
        } finally {
            set(previous);
        }
    }
}
