package com.obliterateharness.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * User-defined relation type.
 *
 * @param allowed   allowed source/target pairs. Each side takes a type name, "@Base" (e.g. "@Agent") or "*".
 *                  Empty or null = any pair.
 * @param temporal  when true, the relation accepts narrative anchors (fromUnit/toUnit)
 * @param systemKey language-independent key the engine relies on (appears_in, plants, pays...); null for ordinary relations
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelTypeDef(
        String id,
        String projectId,
        String name,
        String base,
        String description,
        Boolean symmetric,
        String inverseName,
        Boolean temporal,
        List<Endpoint> allowed,
        String systemKey
) {
    public record Endpoint(String from, String to) {}

    public RelTypeDef(String id, String projectId, String name, String base, String description, Boolean symmetric, String inverseName,
                      Boolean temporal, List<Endpoint> allowed) {
        this(id, projectId, name, base, description, symmetric, inverseName, temporal, allowed, null);
    }

    public RelBase relBase() {
        return RelBase.parse(base);
    }

    public boolean isSymmetric() {
        return Boolean.TRUE.equals(symmetric);
    }

    public boolean allows(NodeTypeDef from, NodeTypeDef to) {
        if (allowed == null || allowed.isEmpty()) return true;
        return allowed.stream().anyMatch(e -> matches(e.from(), from) && matches(e.to(), to)
                || isSymmetric() && matches(e.from(), to) && matches(e.to(), from));
    }

    public static boolean matches(String pattern, NodeTypeDef type) {
        if (pattern == null || pattern.equals("*")) return true;
        if (pattern.startsWith("@")) return type.baseKind().name().equalsIgnoreCase(pattern.substring(1));
        return pattern.equalsIgnoreCase(type.name());
    }
}
