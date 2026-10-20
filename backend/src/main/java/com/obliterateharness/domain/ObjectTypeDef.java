package com.obliterateharness.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Tipo de objeto customizado, usado por atributos composite (ex.: "Ficha de Status"). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObjectTypeDef(
        String id,
        String projectId,
        String name,
        String description,
        List<AttrDef> fields
) {
    public List<AttrDef> fieldList() {
        return fields == null ? List.of() : fields;
    }
}
