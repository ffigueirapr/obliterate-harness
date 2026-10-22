package com.obliterateharness.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Validates and normalizes attribute values against the type definition.
 * node_ref attributes are not stored as properties: they become edges (see {@link Result#refs()}).
 */
public final class AttributeValidator {

    public record RefTarget(String attrKey, String refType, List<String> targetIds) {}

    public record Result(Map<String, Object> values, List<RefTarget> refs, List<String> removed) {}

    private final Function<String, ObjectTypeDef> objectTypes;

    public AttributeValidator(Function<String, ObjectTypeDef> objectTypes) {
        this.objectTypes = objectTypes;
    }

    /**
     * @param incoming values sent (null value = remove the attribute)
     * @param creating true on creation: checks required attributes
     */
    public Result validate(NodeTypeDef type, Map<String, Object> incoming, boolean creating) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<RefTarget> refs = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Map<String, Object> in = incoming == null ? Map.of() : incoming;

        for (var entry : in.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            var def = type.attr(key);
            if (def.isEmpty()) {
                if (type.isStrict()) {
                    throw new DomainException(t("Attribute '", "Atributo '") + key + t("' does not exist in type '", "' não existe no tipo '") + type.name()
                            + t("'. Valid attributes: ", "'. Atributos válidos: ") + type.attrs().stream().map(AttrDef::key).toList()
                            + t(". To accept free attributes, set strict=false on the type or add the attribute to the schema.",
                            ". Para aceitar atributos livres, defina strict=false no tipo ou adicione o atributo ao schema."));
                }
                if (value == null) removed.add(key); else values.put(key, value);
                continue;
            }
            AttrDef d = def.get();
            if (value == null) {
                if (d.isRequired()) {
                    throw new DomainException(t("Required attribute '", "Atributo obrigatório '") + key + t("' of type '", "' do tipo '") + type.name()
                            + t("' cannot be removed", "' não pode ser removido"));
                }
                removed.add(key);
                continue;
            }
            DataType dt = d.type();
            if (dt == DataType.NODE_REF || (dt == DataType.LIST && DataType.parse(d.itemType()) == DataType.NODE_REF)) {
                refs.add(new RefTarget(key, d.refType(), toIdList(key, value, dt == DataType.LIST)));
                continue;
            }
            values.put(key, coerce(type.name() + "." + key, d, dt, value));
        }
        if (creating) {
            for (AttrDef d : type.attrs()) {
                if (d.isRequired() && !in.containsKey(d.key())) {
                    throw new DomainException(t("Required attribute '", "Atributo obrigatório '") + d.key() + "' (" + d.dataType()
                            + t(") missing when creating a node of type '", ") faltando ao criar nó do tipo '") + type.name() + "'");
                }
            }
        }
        return new Result(values, refs, removed);
    }

    private List<String> toIdList(String key, Object value, boolean list) {
        if (!list) {
            if (value instanceof String s && !s.isBlank()) return List.of(s);
            throw new DomainException(t("node_ref attribute '", "Atributo node_ref '") + key + t("' expects a node id (string)", "' espera o id de um nó (string)"));
        }
        if (value instanceof List<?> l) {
            List<String> ids = new ArrayList<>();
            for (Object o : l) {
                if (!(o instanceof String s)) throw new DomainException(t("Attribute '", "Atributo '") + key + t("' expects a list of node ids", "' espera uma lista de ids de nó"));
                ids.add(s);
            }
            return ids;
        }
        throw new DomainException(t("Attribute '", "Atributo '") + key + t("' expects a list of node ids", "' espera uma lista de ids de nó"));
    }

    private Object coerce(String path, AttrDef d, DataType dt, Object value) {
        return switch (dt) {
            case INT -> {
                if (value instanceof Number n && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0) yield n.longValue();
                throw typeError(path, t("an integer", "um número inteiro"), value);
            }
            case DECIMAL -> {
                if (value instanceof Number n) yield n.doubleValue();
                throw typeError(path, t("a number", "um número"), value);
            }
            case BOOL -> {
                if (value instanceof Boolean b) yield b;
                throw typeError(path, t("true or false", "true ou false"), value);
            }
            case STRING_SHORT -> {
                if (!(value instanceof String s)) throw typeError(path, t("a short text", "um texto curto"), value);
                int max = d.maxLength() != null ? d.maxLength() : 255;
                if (s.length() > max) {
                    throw new DomainException(path + t(" exceeds ", " excede ") + max + t(" characters (has ", " caracteres (tem ") + s.length()
                            + t("). Use text_long for longer texts.", "). Use text_long para textos maiores."));
                }
                yield s;
            }
            case TEXT_LONG, MEDIA -> {
                if (value instanceof String s) yield s;
                throw typeError(path, t("a text", "um texto"), value);
            }
            case ENUM -> {
                if (value instanceof String s && d.options().contains(s)) yield s;
                throw new DomainException(path + t(" must be one of ", " deve ser um de ") + d.options() + t(" (received: ", " (recebido: ") + value + ")");
            }
            case JSON -> value;
            case LIST -> {
                if (!(value instanceof List<?> l)) throw typeError(path, t("a list", "uma lista"), value);
                DataType it = DataType.parse(d.itemType());
                AttrDef itemDef = new AttrDef(d.key(), d.label(), d.itemType(), false, null, d.options(), null,
                        d.refType(), d.objectType(), null, d.maxLength(), null);
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) out.add(coerce(path + "[" + i + "]", itemDef, it, l.get(i)));
                yield out;
            }
            case COMPOSITE -> {
                if (!(value instanceof Map<?, ?> m)) throw typeError(path, t("an object of type ", "um objeto do tipo ") + d.objectType(), value);
                ObjectTypeDef ot = objectTypes.apply(d.objectType());
                if (ot == null) {
                    throw new DomainException(t("ObjectType '", "ObjectType '") + d.objectType() + t("' used in ", "' usado em ") + path
                            + t(" does not exist in the project", " não existe no projeto"));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (var e : m.entrySet()) {
                    String fk = String.valueOf(e.getKey());
                    AttrDef fd = ot.fieldList().stream().filter(f -> f.key().equals(fk)).findFirst()
                            .orElseThrow(() -> new DomainException(t("Field '", "Campo '") + fk + t("' does not exist in ObjectType '", "' não existe no ObjectType '")
                                    + ot.name() + t("'. Fields: ", "'. Campos: ") + ot.fieldList().stream().map(AttrDef::key).toList()));
                    DataType ft = fd.type();
                    if (ft == DataType.NODE_REF) {
                        throw new DomainException(t("node_ref inside composite is not supported (", "node_ref dentro de composite não é suportado (") + path + "." + fk + ")");
                    }
                    if (e.getValue() != null) out.put(fk, coerce(path + "." + fk, fd, ft, e.getValue()));
                }
                for (AttrDef fd : ot.fieldList()) {
                    if (fd.isRequired() && !out.containsKey(fd.key())) {
                        throw new DomainException(t("Required field '", "Campo obrigatório '") + fd.key() + t("' missing in ", "' faltando em ") + path);
                    }
                }
                yield out;
            }
            case NODE_REF -> throw new IllegalStateException("node_ref is handled separately");
        };
    }

    private static DomainException typeError(String path, String expected, Object value) {
        return new DomainException(path + t(" must be ", " deve ser ") + expected + t(" (received: ", " (recebido: ") + value + ")");
    }
}
