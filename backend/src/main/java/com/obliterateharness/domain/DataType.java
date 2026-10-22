package com.obliterateharness.domain;

import static com.obliterateharness.i18n.I18n.t;

/** Attribute data types supported by the meta-model. */
public enum DataType {
    INT, DECIMAL, BOOL, STRING_SHORT, TEXT_LONG, ENUM, JSON, NODE_REF, LIST, COMPOSITE, MEDIA;

    public static DataType parse(String value) {
        if (value == null) throw new DomainException(t("dataType is required on the attribute", "dataType é obrigatório no atributo"));
        for (DataType d : values()) {
            if (d.name().equalsIgnoreCase(value)) return d;
        }
        throw new DomainException(t("Invalid dataType: '", "dataType inválido: '") + value
                + t("'. Valid: ", "'. Válidos: ") + "int, decimal, bool, string_short, text_long, enum, json, node_ref, list, composite, media");
    }
}
