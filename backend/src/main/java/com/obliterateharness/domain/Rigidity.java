package com.obliterateharness.domain;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Rule rigidity: law (never break), norm (rule of thumb; deviating needs a narrative reason), flavor (background).
 */
public enum Rigidity {
    law, norm, flavor;

    public static Rigidity parse(String value, Rigidity fallback) {
        if (value == null || value.isBlank()) return fallback;
        for (Rigidity r : values()) {
            if (r.name().equalsIgnoreCase(value)) return r;
        }
        throw new DomainException(t("Invalid rigidity: '", "Rigidez inválida: '") + value + t("'. Valid: law, norm, flavor", "'. Válidas: law, norm, flavor"));
    }
}
