package com.obliterateharness.domain;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Separate creation spaces. Canon is born in world/characters; the narrative in story.
 */
public enum Space {
    world, characters, story, analysis, templates;

    public static Space parse(String value, BaseKind base) {
        if (value == null || value.isBlank()) return defaultFor(base);
        for (Space s : values()) {
            if (s.name().equalsIgnoreCase(value)) return s;
        }
        throw new DomainException(t("Invalid space: '", "Espaço inválido: '") + value
                + t("'. Valid: world, characters, story, analysis, templates", "'. Válidos: world, characters, story, analysis, templates"));
    }

    public static Space defaultFor(BaseKind base) {
        return switch (base) {
            case Agent -> characters;
            case Unit, Device -> story;
            default -> world;
        };
    }
}
