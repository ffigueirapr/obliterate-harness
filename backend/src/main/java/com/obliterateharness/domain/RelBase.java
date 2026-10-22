package com.obliterateharness.domain;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Base relation kinds the engine understands.
 */
public enum RelBase {
    PARTICIPATION, SOCIAL, POSSESSION, SPATIAL, CAUSAL, KNOWLEDGE, ORIGIN, REFERENCE;

    public static RelBase parse(String value) {
        for (RelBase k : values()) {
            if (k.name().equalsIgnoreCase(value)) return k;
        }
        throw new DomainException(t("Invalid relation base: '", "Tipo-base de relação inválido: '") + value
                + t("'. Valid: ", "'. Válidos: ") + "PARTICIPATION, SOCIAL, POSSESSION, SPATIAL, CAUSAL, KNOWLEDGE, ORIGIN, REFERENCE");
    }
}
