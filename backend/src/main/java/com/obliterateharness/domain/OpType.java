package com.obliterateharness.domain;

import static com.obliterateharness.i18n.I18n.t;

public enum OpType {
    create_node("n"), update_node(null), delete_node(null),
    create_edge("e"), update_edge(null), delete_edge(null),
    create_fact("f"), update_fact(null), delete_fact(null),
    upsert_node_type("nt"), upsert_rel_type("rt"), upsert_object_type("ot"),
    delete_node_type(null), delete_rel_type(null),
    upsert_directive("dr"), delete_directive(null);

    /** Id prefix when the operation creates something new. */
    public final String idPrefix;

    OpType(String idPrefix) {
        this.idPrefix = idPrefix;
    }

    public boolean creates() {
        return idPrefix != null;
    }

    public static OpType parse(String value) {
        if (value != null) {
            for (OpType t : values()) {
                if (t.name().equalsIgnoreCase(value)) return t;
            }
        }
        throw new DomainException(t("Invalid operation: '", "Operação inválida: '") + value + t("'. Valid: ", "'. Válidas: ")
                + "create_node, update_node, delete_node, create_edge, update_edge, delete_edge, create_fact, update_fact, delete_fact, "
                + "upsert_node_type, upsert_rel_type, upsert_object_type, delete_node_type, delete_rel_type, upsert_directive, delete_directive");
    }
}
