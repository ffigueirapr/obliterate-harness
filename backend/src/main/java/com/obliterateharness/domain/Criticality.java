package com.obliterateharness.domain;

import static com.obliterateharness.i18n.I18n.t;

public enum Criticality {
    normal, critical, foundational;

    public static Criticality parse(String value) {
        if (value == null || value.isBlank()) return normal;
        for (Criticality c : values()) {
            if (c.name().equalsIgnoreCase(value)) return c;
        }
        throw new DomainException(t("Invalid criticality: '", "Criticidade inválida: '") + value
                + t("'. Valid: normal, critical, foundational", "'. Válidas: normal, critical, foundational"));
    }

    public boolean isPinned() {
        return this != normal;
    }

    /** Default rigidity of the mirrored Fact when none is given. */
    public Rigidity defaultRigidity() {
        return this == foundational ? Rigidity.law : Rigidity.norm;
    }
}
