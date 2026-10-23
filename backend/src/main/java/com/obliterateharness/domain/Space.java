package com.obliterateharness.domain;

import java.util.Map;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Graph spaces (tabs). actors, knowledge and analysis are neutral; story and templates are used by the narrative
 * module (units and structure templates). Each context labels them its own way (narrative: actors = Characters,
 * knowledge = World). The old narrative names are accepted as aliases.
 */
public enum Space {
    knowledge, actors, analysis, story, templates;

    public static final Map<String, Space> LEGACY = Map.of("world", knowledge, "characters", actors);

    public static Space parse(String value, BaseKind base) {
        if (value == null || value.isBlank()) return defaultFor(base);
        Space s = of(value);
        if (s != null) return s;
        throw new DomainException(t("Invalid space: '", "Espaço inválido: '") + value
                + t("'. Valid: knowledge, actors, analysis, story, templates", "'. Válidos: knowledge, actors, analysis, story, templates"));
    }

    public static Space of(String value) {
        if (value == null) return null;
        for (Space s : values()) if (s.name().equalsIgnoreCase(value)) return s;
        return LEGACY.get(value.toLowerCase());
    }

    public static Space defaultFor(BaseKind base) {
        return switch (base) {
            case Agent -> actors;
            case Unit, Device -> story;
            default -> knowledge;
        };
    }
}
