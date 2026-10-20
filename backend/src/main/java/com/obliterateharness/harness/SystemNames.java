package com.obliterateharness.harness;

import com.obliterateharness.domain.AttrDef;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.graph.SchemaStore;
import org.neo4j.driver.SimpleQueryRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves, per project, the types and relations the engine relies on (by system key), and the attribute keys of the
 * engine's own types in either language. Lets the engine work the same on English, Portuguese and renamed schemas.
 */
@Component
public class SystemNames {

    /** Engine fields of Template/Beat/Hook nodes: candidate attribute keys (English first). */
    public enum Field {
        TEMPLATE_ID("template_id"),
        UNIT_TYPE("unit_type", "tipo_de_unidade"),
        PLANNED_UNITS("planned_units", "unidades_planejadas"),
        SCOPE("scope", "escopo"),
        SOURCE("source", "fonte"),
        BEAT_KEY("key", "chave"),
        POSITION("position", "posicao"),
        FUNCTION("function", "funcao"),
        CRITERIA("criteria", "criterio"),
        DEVICES("devices", "recursos"),
        REQUIRED("required", "obrigatorio"),
        THREAD_KIND("kind", "tipo"),
        THREAD_STATUS("status"),
        PLANNED_PAYOFF("planned_payoff", "payoff_planejado");

        public final List<String> keys;

        Field(String... keys) {
            this.keys = List.of(keys);
        }

        /** Value of the field in an attribute map, whichever language the key is in. */
        public Object read(Map<String, Object> attrs) {
            for (String k : keys) if (attrs.containsKey(k)) return attrs.get(k);
            return null;
        }

        /** Key to write this field into a node of the given type (the one the type defines). */
        public String keyFor(NodeTypeDef type) {
            if (type != null) {
                for (String k : keys) if (type.attr(k).isPresent()) return k;
            }
            return keys.getFirst();
        }
    }

    private final SchemaStore schema;

    public SystemNames(SchemaStore schema) {
        this.schema = schema;
    }

    public Optional<NodeTypeDef> nodeType(SimpleQueryRunner q, String projectId, String systemKey) {
        return schema.cache(q, projectId).nodeTypeBySystem(systemKey);
    }

    /** Type id for a system key, or a value that never matches (so queries simply return nothing). */
    public String nodeTypeId(SimpleQueryRunner q, String projectId, String systemKey) {
        return nodeType(q, projectId, systemKey).map(NodeTypeDef::id).orElse("__none__");
    }

    public String relTypeId(SimpleQueryRunner q, String projectId, String systemKey) {
        return schema.cache(q, projectId).relTypeBySystem(systemKey).map(r -> r.id()).orElse("__none__");
    }

    public Optional<String> relTypeName(SimpleQueryRunner q, String projectId, String systemKey) {
        return schema.cache(q, projectId).relTypeBySystem(systemKey).map(r -> r.name());
    }

    public Optional<String> nodeTypeName(SimpleQueryRunner q, String projectId, String systemKey) {
        return nodeType(q, projectId, systemKey).map(NodeTypeDef::name);
    }

    /** The attribute with a role on a type, if any (manuscript, location, secret...). */
    public static Optional<AttrDef> roleAttr(NodeTypeDef type, String role) {
        return type == null ? Optional.empty() : type.attrWithRole(role);
    }
}
