package com.obliterateharness.domain;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Base node kinds the engine understands. Every user-defined type extends one of them.
 */
public enum BaseKind {
    Agent, Place, Object, Ability, Concept, Event, Media, Note;

    public static BaseKind parse(String value) {
        for (BaseKind k : values()) {
            if (k.name().equalsIgnoreCase(value)) return k;
        }
        throw new DomainException(t("Invalid base kind: '", "Tipo-base inválido: '") + value
                + t("'. Valid: ", "'. Válidos: ") + "Agent, Place, Object, Ability, Concept, Event, Media, Note");
    }
}
