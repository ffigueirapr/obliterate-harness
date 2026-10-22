package com.obliterateharness.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Optional;

/**
 * User-defined node type, extending a base kind.
 *
 * @param strict    when true (default), attributes outside the definition are rejected
 * @param systemKey language-independent key the engine relies on to find a type whatever its name; null for ordinary types
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeTypeDef(
        String id,
        String projectId,
        String name,
        String base,
        String description,
        String color,
        Boolean strict,
        List<AttrDef> attributes,
        String systemKey
) {
    public NodeTypeDef(String id, String projectId, String name, String base, String description, String color, Boolean strict,
                       List<AttrDef> attributes) {
        this(id, projectId, name, base, description, color, strict, attributes, null);
    }

    public BaseKind baseKind() {
        return BaseKind.parse(base);
    }

    public boolean isStrict() {
        return strict == null || strict;
    }

    public List<AttrDef> attrs() {
        return attributes == null ? List.of() : attributes;
    }

    public Optional<AttrDef> attr(String key) {
        return attrs().stream().filter(a -> a.key().equals(key)).findFirst();
    }

    /** First attribute with the given role (e.g. link). */
    public Optional<AttrDef> attrWithRole(String role) {
        return attrs().stream().filter(a -> role.equals(a.role())).findFirst();
    }
}
