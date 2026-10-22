package com.obliterateharness.graph;

import com.obliterateharness.domain.AttrDef;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.ObjectTypeDef;
import com.obliterateharness.domain.RelTypeDef;
import org.neo4j.driver.SimpleQueryRunner;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.obliterateharness.i18n.I18n.t;

/** Persistence of the meta-model (node, relation and object types) in Neo4j. */
@Component
public class SchemaStore {

    private final JsonCodec json;

    public SchemaStore(JsonCodec json) {
        this.json = json;
    }

    // ---------- reads ----------

    public List<NodeTypeDef> nodeTypes(SimpleQueryRunner q, String projectId) {
        return q.run("MATCH (t:NodeType {projectId:$p}) RETURN t ORDER BY t.nameLower", Map.of("p", projectId))
                .list(r -> toNodeType(r.get("t")));
    }

    public List<RelTypeDef> relTypes(SimpleQueryRunner q, String projectId) {
        return q.run("MATCH (t:RelType {projectId:$p}) RETURN t ORDER BY t.nameLower", Map.of("p", projectId))
                .list(r -> toRelType(r.get("t")));
    }

    public List<ObjectTypeDef> objectTypes(SimpleQueryRunner q, String projectId) {
        return q.run("MATCH (t:ObjectType {projectId:$p}) RETURN t ORDER BY t.nameLower", Map.of("p", projectId))
                .list(r -> toObjectType(r.get("t")));
    }

    public Cache cache(SimpleQueryRunner q, String projectId) {
        return new Cache(nodeTypes(q, projectId), relTypes(q, projectId), objectTypes(q, projectId));
    }

    /** In-memory snapshot of the schema, used while applying a changeset. */
    public static final class Cache {
        private final Map<String, NodeTypeDef> nodeTypes = new HashMap<>();
        private final Map<String, RelTypeDef> relTypes = new HashMap<>();
        private final Map<String, ObjectTypeDef> objectTypes = new HashMap<>();
        private final Map<String, NodeTypeDef> nodeBySystem = new HashMap<>();
        private final Map<String, RelTypeDef> relBySystem = new HashMap<>();

        Cache(List<NodeTypeDef> nts, List<RelTypeDef> rts, List<ObjectTypeDef> ots) {
            nts.forEach(this::put);
            rts.forEach(this::put);
            ots.forEach(this::put);
        }

        public void put(NodeTypeDef t) {
            nodeTypes.put(t.id(), t);
            nodeTypes.put(t.name().toLowerCase(), t);
            if (t.systemKey() != null) nodeBySystem.put(t.systemKey(), t);
        }

        public void put(RelTypeDef t) {
            relTypes.put(t.id(), t);
            relTypes.put(t.name().toLowerCase(), t);
            if (t.systemKey() != null) relBySystem.put(t.systemKey(), t);
        }

        public void put(ObjectTypeDef t) {
            objectTypes.put(t.id(), t);
            objectTypes.put(t.name().toLowerCase(), t);
        }

        public void removeNodeType(NodeTypeDef t) {
            nodeTypes.remove(t.id());
            nodeTypes.remove(t.name().toLowerCase());
            if (t.systemKey() != null) nodeBySystem.remove(t.systemKey());
        }

        public void removeRelType(RelTypeDef t) {
            relTypes.remove(t.id());
            relTypes.remove(t.name().toLowerCase());
            if (t.systemKey() != null) relBySystem.remove(t.systemKey());
        }

        public Optional<NodeTypeDef> nodeType(String nameOrId) {
            return nameOrId == null ? Optional.empty() : Optional.ofNullable(nodeTypes.getOrDefault(nameOrId, nodeTypes.get(nameOrId.toLowerCase())));
        }

        public Optional<RelTypeDef> relType(String nameOrId) {
            return nameOrId == null ? Optional.empty() : Optional.ofNullable(relTypes.getOrDefault(nameOrId, relTypes.get(nameOrId.toLowerCase())));
        }

        public Optional<NodeTypeDef> nodeTypeBySystem(String systemKey) {
            return Optional.ofNullable(nodeBySystem.get(systemKey));
        }

        public Optional<RelTypeDef> relTypeBySystem(String systemKey) {
            return Optional.ofNullable(relBySystem.get(systemKey));
        }

        public List<RelTypeDef> allRelTypes() {
            return relTypes.values().stream().distinct().toList();
        }

        public ObjectTypeDef objectType(String nameOrId) {
            return nameOrId == null ? null : objectTypes.getOrDefault(nameOrId, objectTypes.get(nameOrId.toLowerCase()));
        }

        public NodeTypeDef requireNodeType(String nameOrId) {
            return nodeType(nameOrId).orElseThrow(() -> new DomainException(t("Node type '", "Tipo de nó '") + nameOrId
                    + t("' does not exist. Available types: ", "' não existe. Tipos disponíveis: ")
                    + nodeTypes.values().stream().map(NodeTypeDef::name).distinct().sorted().toList()
                    + t(". Create it with upsert_node_type first.", ". Crie o tipo com upsert_node_type antes.")));
        }

        public RelTypeDef requireRelType(String nameOrId) {
            return relType(nameOrId).orElseThrow(() -> new DomainException(t("Relation type '", "Tipo de relação '") + nameOrId
                    + t("' does not exist. Available types: ", "' não existe. Tipos disponíveis: ")
                    + relTypes.values().stream().map(RelTypeDef::name).distinct().sorted().toList()
                    + t(". Create it with upsert_rel_type first.", ". Crie o tipo com upsert_rel_type antes.")));
        }
    }

    // ---------- writes ----------

    public void saveNodeType(SimpleQueryRunner q, NodeTypeDef t) {
        q.run("""
                MERGE (t:NodeType {id:$id})
                SET t.projectId=$p, t.name=$name, t.nameLower=toLower($name), t.base=$base, t.description=$description,
                    t.color=$color, t.strict=$strict, t.attributes=$attributes, t.systemKey=$systemKey
                """, params("id", t.id(), "p", t.projectId(), "name", t.name(), "base", t.baseKind().name(),
                "description", t.description(), "color", t.color(), "strict", t.isStrict(),
                "attributes", json.write(t.attrs()), "systemKey", t.systemKey())).consume();
    }

    public void saveRelType(SimpleQueryRunner q, RelTypeDef t) {
        q.run("""
                MERGE (t:RelType {id:$id})
                SET t.projectId=$p, t.name=$name, t.nameLower=toLower($name), t.base=$base, t.description=$description,
                    t.symmetric=$symmetric, t.inverseName=$inverseName, t.temporal=$temporal, t.allowed=$allowed, t.systemKey=$systemKey
                """, params("id", t.id(), "p", t.projectId(), "name", t.name(), "base", t.relBase().name(),
                "description", t.description(), "symmetric", t.isSymmetric(), "inverseName", t.inverseName(),
                "temporal", t.temporal() == null || t.temporal(), "allowed", json.write(t.allowed() == null ? List.of() : t.allowed()),
                "systemKey", t.systemKey())).consume();
    }

    public void saveObjectType(SimpleQueryRunner q, ObjectTypeDef t) {
        q.run("""
                MERGE (t:ObjectType {id:$id})
                SET t.projectId=$p, t.name=$name, t.nameLower=toLower($name), t.description=$description, t.fields=$fields
                """, params("id", t.id(), "p", t.projectId(), "name", t.name(), "description", t.description(),
                "fields", json.write(t.fieldList()))).consume();
    }

    public void deleteNodeType(SimpleQueryRunner q, NodeTypeDef t) {
        long count = q.run("MATCH (n:N {projectId:$p, typeId:$id}) RETURN count(n) AS c", Map.of("p", t.projectId(), "id", t.id()))
                .single().get("c").asLong();
        if (count > 0) {
            throw new DomainException(t("Type '", "Tipo '") + t.name() + t("' has ", "' tem ") + count
                    + t(" nodes. Delete or migrate them before removing the type.", " nós. Apague ou migre os nós antes de remover o tipo."));
        }
        q.run("MATCH (t:NodeType {id:$id}) DETACH DELETE t", Map.of("id", t.id())).consume();
    }

    public void deleteRelType(SimpleQueryRunner q, RelTypeDef t) {
        long count = q.run("MATCH ()-[r:R {projectId:$p, relTypeId:$id}]->() RETURN count(r) AS c", Map.of("p", t.projectId(), "id", t.id()))
                .single().get("c").asLong();
        if (count > 0) {
            throw new DomainException(t("Relation type '", "Tipo de relação '") + t.name() + t("' has ", "' tem ") + count
                    + t(" edges. Delete them before removing the type.", " arestas. Apague-as antes de remover o tipo."));
        }
        q.run("MATCH (t:RelType {id:$id}) DETACH DELETE t", Map.of("id", t.id())).consume();
    }

    // ---------- mapping ----------

    NodeTypeDef toNodeType(Value v) {
        return new NodeTypeDef(str(v, "id"), str(v, "projectId"), str(v, "name"), str(v, "base"), str(v, "description"),
                str(v, "color"), v.get("strict").isNull() ? null : v.get("strict").asBoolean(),
                json.readList(str(v, "attributes"), AttrDef.class), str(v, "systemKey"));
    }

    RelTypeDef toRelType(Value v) {
        return new RelTypeDef(str(v, "id"), str(v, "projectId"), str(v, "name"), str(v, "base"), str(v, "description"),
                v.get("symmetric").isNull() ? null : v.get("symmetric").asBoolean(), str(v, "inverseName"),
                v.get("temporal").isNull() ? null : v.get("temporal").asBoolean(),
                json.readList(str(v, "allowed"), RelTypeDef.Endpoint.class), str(v, "systemKey"));
    }

    ObjectTypeDef toObjectType(Value v) {
        return new ObjectTypeDef(str(v, "id"), str(v, "projectId"), str(v, "name"), str(v, "description"),
                json.readList(str(v, "fields"), AttrDef.class));
    }

    static String str(Value v, String key) {
        Value x = v.get(key);
        return x == null || x.isNull() ? null : x.asString();
    }

    /** Parameter map that accepts null values (Map.of does not). */
    public static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
