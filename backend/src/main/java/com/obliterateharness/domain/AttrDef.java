package com.obliterateharness.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Definition of an attribute of a node type (or a field of an ObjectType).
 *
 * @param key                attribute id (slug, e.g. "age")
 * @param label              friendly label
 * @param dataType           int, decimal, bool, string_short, text_long, enum, json, node_ref, list, composite, media
 * @param required           mandatory on creation
 * @param options            valid values (enum)
 * @param itemType           item type (list)
 * @param refType            target node type (node_ref or list of node_ref): type name, "@Base" or empty for any
 * @param objectType         ObjectType name (composite or list of composite)
 * @param jsonSchema         documentation-only JSON Schema for json attributes
 * @param maxLength          character limit (string_short defaults to 255)
 * @param defaultCriticality criticality applied when the value is set without an explicit one
 * @param role               what the engine uses this attribute for, independent of its name/language:
 *                           link (URL to an external document or system with the details; shown as a link)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttrDef(
        String key,
        String label,
        String dataType,
        Boolean required,
        String description,
        List<String> options,
        String itemType,
        String refType,
        String objectType,
        Map<String, Object> jsonSchema,
        Integer maxLength,
        String defaultCriticality,
        String role
) {
    public static final Set<String> ROLES = Set.of("link");

    /** Short constructor without role (legacy call sites). */
    public AttrDef(String key, String label, String dataType, Boolean required, String description, List<String> options, String itemType,
                   String refType, String objectType, Map<String, Object> jsonSchema, Integer maxLength, String defaultCriticality) {
        this(key, label, dataType, required, description, options, itemType, refType, objectType, jsonSchema, maxLength, defaultCriticality, null);
    }

    public DataType type() {
        return DataType.parse(dataType);
    }

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    public void check(String owner) {
        if (key == null || !key.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}")) {
            throw new DomainException(t("Invalid attribute key in ", "Chave de atributo inválida em ") + owner + ": '" + key
                    + t("'. Use letters, digits and _ (e.g. age, eye_color)", "'. Use letras, números e _ (ex.: idade, cor_dos_olhos)"));
        }
        DataType dt = type();
        if (dt == DataType.ENUM && (options == null || options.isEmpty())) {
            throw new DomainException(t("Enum attribute '", "Atributo enum '") + key + t("' in ", "' em ") + owner + t(" needs 'options'", " precisa de 'options'"));
        }
        if (dt == DataType.LIST) {
            if (itemType == null) throw new DomainException(t("List attribute '", "Atributo list '") + key + t("' in ", "' em ") + owner + t(" needs 'itemType'", " precisa de 'itemType'"));
            DataType it = DataType.parse(itemType);
            if (it == DataType.LIST) throw new DomainException(t("Lists of lists are not supported ('", "Lista de listas não é suportada ('") + key + "' " + owner + ")");
            if (it == DataType.COMPOSITE && objectType == null) {
                throw new DomainException(t("List of composite '", "Lista de composite '") + key + t("' needs 'objectType'", "' precisa de 'objectType'"));
            }
        }
        if (dt == DataType.COMPOSITE && objectType == null) {
            throw new DomainException(t("Composite attribute '", "Atributo composite '") + key + t("' in ", "' em ") + owner + t(" needs 'objectType'", " precisa de 'objectType'"));
        }
        if (defaultCriticality != null) Criticality.parse(defaultCriticality);
        if (role != null && !ROLES.contains(role)) {
            throw new DomainException(t("Invalid role '", "Papel inválido '") + role + "' (" + key + t("). Valid: ", "). Válidos: ") + ROLES);
        }
    }
}
