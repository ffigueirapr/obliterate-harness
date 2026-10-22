package com.obliterateharness.domain;

import java.util.Map;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Spaces that group the graph into tabs: actors (people, teams, organizations: base Agent), knowledge (everything else)
 * and analysis (insights and studies). Each context names them its own way (spaceLabels).
 */
public enum Space {
    knowledge, actors, analysis;

    /** Names used before the platform became domain-neutral; accepted on input and migrated on startup. */
    public static final Map<String, Space> LEGACY = Map.of(
            "world", knowledge, "characters", actors, "story", knowledge, "templates", analysis);

    public static Space parse(String value, BaseKind base) {
        if (value == null || value.isBlank()) return defaultFor(base);
        for (Space s : values()) {
            if (s.name().equalsIgnoreCase(value)) return s;
        }
        Space legacy = LEGACY.get(value.toLowerCase());
        if (legacy != null) return legacy;
        throw new DomainException(t("Invalid space: '", "Espaço inválido: '") + value
                + t("'. Valid: knowledge, actors, analysis", "'. Válidos: knowledge, actors, analysis"));
    }

    /** Accepts a current or legacy name; null when unknown. */
    public static Space of(String value) {
        if (value == null) return null;
        for (Space s : values()) if (s.name().equalsIgnoreCase(value)) return s;
        return LEGACY.get(value.toLowerCase());
    }

    public static Space defaultFor(BaseKind base) {
        return base == BaseKind.Agent ? actors : knowledge;
    }
}
