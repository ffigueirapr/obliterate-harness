package com.obliterateharness.domain;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Base relation kinds the engine understands.
 */
public enum RelBase {
    STRUCTURE, PARTICIPATION, SOCIAL, POSSESSION, SPATIAL, CAUSAL, KNOWLEDGE, ORIGIN, SETUP_PAYOFF, REFERENCE;

    public static RelBase parse(String value) {
        for (RelBase k : values()) {
            if (k.name().equalsIgnoreCase(value)) return k;
        }
        throw new DomainException(t("Invalid relation base: '", "Tipo-base de relação inválido: '") + value
                + t("'. Valid: ", "'. Válidos: ") + "STRUCTURE, PARTICIPATION, SOCIAL, POSSESSION, SPATIAL, CAUSAL, KNOWLEDGE, ORIGIN, SETUP_PAYOFF, REFERENCE");
    }
}
