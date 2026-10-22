package com.obliterateharness.graph;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.AttrDef;
import com.obliterateharness.domain.AttributeValidator;
import com.obliterateharness.domain.BaseKind;
import com.obliterateharness.domain.Criticality;
import com.obliterateharness.domain.DataType;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Ids;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.NotFoundException;
import com.obliterateharness.domain.ObjectTypeDef;
import com.obliterateharness.domain.OpType;
import com.obliterateharness.domain.Operation;
import com.obliterateharness.domain.RelBase;
import com.obliterateharness.domain.RelTypeDef;
import com.obliterateharness.domain.Rigidity;
import com.obliterateharness.domain.Space;
import com.obliterateharness.domain.Views;
import com.obliterateharness.domain.Views.Detail;
import org.neo4j.driver.SimpleQueryRunner;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

/**
 * Applies write operations to the graph, inside a transaction supplied by the caller.
 * Every integrity rule of the meta-model is checked here — this is the server-side guardrail.
 */
@Component
public class ChangeExecutor {

    /** Result of an applied operation, with snapshots for auditing. */
    public record OpResult(int index, String op, String entityKind, String entityId, String ref, Object before, Object after) {}

    /** Outcome of one operation in collecting mode (dry run / best effort): either a result or an error. */
    public record OpOutcome(int index, String op, String ref, OpResult result, String error, String errorKind) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Operations ready to apply: ids assigned, aliases resolved.
     *
     * @param newRefs aliases declared in this batch → assigned ids
     * @param errors  per-op problems found while resolving (undeclared or repeated alias...)
     */
    public record Prepared(List<Operation> ops, Map<String, String> newRefs, Map<Integer, String> errors) {
        public void throwIfErrors() {
            if (!errors.isEmpty()) {
                var first = errors.entrySet().iterator().next();
                throw new DomainException("Op " + first.getKey() + ": " + first.getValue());
            }
        }
    }

    private final SchemaStore schema;
    private final GraphReader reader;
    private final JsonCodec json;

    public ChangeExecutor(SchemaStore schema, GraphReader reader, JsonCodec json) {
        this.schema = schema;
        this.reader = reader;
        this.json = json;
    }

    // =====================================================================
    // Preparation: assigns ids and resolves "$x" aliases
    // =====================================================================

    /**
     * @param knownRefs aliases from earlier batches of the same session (may be empty)
     */
    public Prepared prepare(List<Operation> ops, Map<String, String> knownRefs) {
        if (ops == null || ops.isEmpty()) throw new DomainException(t("No operation given", "Nenhuma operação informada"));
        Map<String, String> refs = new HashMap<>(knownRefs == null ? Map.of() : knownRefs);
        Map<String, String> newRefs = new LinkedHashMap<>();
        Map<Integer, String> errors = new java.util.TreeMap<>();
        List<Operation> withIds = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Operation op = ops.get(i);
            OpType type;
            try {
                type = op.opType();
            } catch (DomainException e) {
                errors.put(i, e.getMessage());
                withIds.add(op);
                continue;
            }
            if (type.creates() && op.id() == null) op = op.withId(Ids.next(type.idPrefix));
            if (op.ref() != null) {
                if (!type.creates()) {
                    errors.put(i, t("'ref' only applies to operations that create something", "'ref' só vale para operações que criam algo"));
                } else if (!op.ref().startsWith("$")) {
                    errors.put(i, t("an alias must start with $ (e.g. \"$ana\")", "apelido deve começar com $ (ex.: \"$ana\")"));
                } else if (newRefs.containsKey(op.ref())) {
                    errors.put(i, t("alias ", "apelido ") + op.ref() + t(" repeated in this batch", " repetido neste lote"));
                } else {
                    // redeclaring a session alias in a new batch points it at the new entity
                    refs.put(op.ref(), op.id());
                    newRefs.put(op.ref(), op.id());
                }
            }
            withIds.add(op);
        }
        List<Operation> out = new ArrayList<>();
        for (int i = 0; i < withIds.size(); i++) {
            Operation op = withIds.get(i);
            if (errors.containsKey(i)) {
                out.add(op);
                continue;
            }
            List<String> unknown = new ArrayList<>();
            UnaryOperator<String> r = s -> {
                if (s == null || !s.startsWith("$")) return s;
                String id = refs.get(s);
                if (id == null) {
                    unknown.add(s);
                    return s;
                }
                return id;
            };
            Map<String, Object> attrs = null;
            if (op.attributes() != null) {
                attrs = new LinkedHashMap<>();
                for (var e : op.attributes().entrySet()) attrs.put(e.getKey(), resolveValue(e.getValue(), refs, unknown));
            }
            List<String> about = op.about() == null ? null : op.about().stream().map(r).toList();
            Operation resolved = op.resolved(r, attrs, about);
            if (!unknown.isEmpty()) {
                errors.put(i, t("alias ", "apelido ") + String.join(", ", unknown)
                        + t(" was not declared in this batch nor earlier in the session (pass sessionId to reuse aliases across batches)",
                        " não foi declarado neste lote nem antes na sessão (passe sessionId para reaproveitar apelidos entre lotes)"));
            }
            out.add(resolved);
        }
        return new Prepared(out, newRefs, errors);
    }

    private static Object resolveValue(Object v, Map<String, String> refs, List<String> unknown) {
        if (v instanceof String s && s.startsWith("$")) {
            if (refs.containsKey(s)) return refs.get(s);
            if (s.matches("\\$[A-Za-z_][A-Za-z0-9_]*")) unknown.add(s);
            return v;
        }
        if (v instanceof List<?> l) return l.stream().map(x -> resolveValue(x, refs, unknown)).toList();
        return v;
    }

    // =====================================================================
    // Application
    // =====================================================================

    /** Strict mode: stops at the first error (the caller's transaction then rolls back). */
    public List<OpResult> apply(SimpleQueryRunner q, String projectId, Actor actor, List<Operation> ops) {
        requireProject(q, projectId);
        var ctx = new Ctx(q, projectId, actor, schema.cache(q, projectId));
        List<OpResult> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Operation op = ops.get(i);
            try {
                results.add(applyOne(ctx, i, op));
            } catch (ConflictException e) {
                throw new ConflictException("Op " + i + " (" + op.op() + "): " + e.getMessage());
            } catch (NotFoundException e) {
                throw new NotFoundException("Op " + i + " (" + op.op() + "): " + e.getMessage());
            } catch (DomainException e) {
                throw new DomainException("Op " + i + " (" + op.op() + "): " + e.getMessage());
            }
        }
        return results;
    }

    /**
     * Collecting mode: tries every operation and records each error instead of stopping. Used by dry runs
     * (always rolled back) and by the first pass of best-effort.
     *
     * @param preErrors errors already known from {@link #prepare}; those ops are reported and skipped
     */
    public List<OpOutcome> applyCollecting(SimpleQueryRunner q, String projectId, Actor actor, List<Operation> ops, Map<Integer, String> preErrors) {
        requireProject(q, projectId);
        var ctx = new Ctx(q, projectId, actor, schema.cache(q, projectId));
        List<OpOutcome> out = new ArrayList<>();
        boolean broken = false;
        for (int i = 0; i < ops.size(); i++) {
            Operation op = ops.get(i);
            if (preErrors.containsKey(i)) {
                out.add(new OpOutcome(i, op.op(), op.ref(), null, preErrors.get(i), "invalid"));
                continue;
            }
            if (broken) {
                out.add(new OpOutcome(i, op.op(), op.ref(), null,
                        t("not evaluated: the database rejected an earlier operation", "não avaliada: o banco rejeitou uma operação anterior"), "skipped"));
                continue;
            }
            try {
                out.add(new OpOutcome(i, op.op(), op.ref(), applyOne(ctx, i, op), null, null));
            } catch (ConflictException e) {
                out.add(new OpOutcome(i, op.op(), op.ref(), null, e.getMessage(), "conflict"));
            } catch (NotFoundException e) {
                out.add(new OpOutcome(i, op.op(), op.ref(), null, e.getMessage(), "not_found"));
            } catch (DomainException e) {
                out.add(new OpOutcome(i, op.op(), op.ref(), null, e.getMessage(), "invalid"));
            } catch (RuntimeException e) {
                // the database aborted the transaction: nothing after this can run in it
                out.add(new OpOutcome(i, op.op(), op.ref(), null, e.getMessage(), "database"));
                broken = true;
            }
        }
        return out;
    }

    private record Ctx(SimpleQueryRunner q, String p, Actor actor, SchemaStore.Cache cache) {
        String now() {
            return Instant.now().toString();
        }
    }

    private OpResult applyOne(Ctx c, int i, Operation op) {
        return switch (op.opType()) {
            case create_node -> createNode(c, i, op);
            case update_node -> updateNode(c, i, op);
            case delete_node -> deleteNode(c, i, op);
            case create_edge -> createEdge(c, i, op);
            case update_edge -> updateEdge(c, i, op);
            case delete_edge -> deleteEdge(c, i, op);
            case create_fact -> createFact(c, i, op);
            case update_fact -> updateFact(c, i, op);
            case delete_fact -> deleteFact(c, i, op);
            case upsert_node_type -> upsertNodeType(c, i, op);
            case upsert_rel_type -> upsertRelType(c, i, op);
            case upsert_object_type -> upsertObjectType(c, i, op);
            case delete_node_type -> deleteNodeType(c, i, op);
            case delete_rel_type -> deleteRelType(c, i, op);
            case upsert_directive -> upsertDirective(c, i, op);
            case delete_directive -> deleteDirective(c, i, op);
        };
    }

    // ---------------------------------------------------------------- nodes

    private OpResult createNode(Ctx c, int i, Operation op) {
        NodeTypeDef type = c.cache.requireNodeType(op.type());
        BaseKind base = type.baseKind();
        if (blank(op.name())) throw new DomainException(t("'name' is required to create a node", "'name' é obrigatório ao criar nó"));
        Criticality crit = Criticality.parse(op.criticality());
        Space space = Space.parse(op.space(), base);
        var validator = new AttributeValidator(c.cache::objectType);
        var res = validator.validate(type, op.attributes(), true);
        Map<String, Object> values = new LinkedHashMap<>(res.values());
        Map<String, String> attrMeta = mergeAttrMeta(type, new LinkedHashMap<>(), res, op.attributeCriticality());

        Map<String, Object> props = new HashMap<>();
        props.put("id", op.id());
        props.put("projectId", c.p);
        props.put("typeId", type.id());
        props.put("typeName", type.name());
        props.put("base", base.name());
        props.put("space", space.name());
        props.put("name", op.name().trim());
        putIfNotNull(props, "summary", op.summary());
        putIfNotNull(props, "description", op.description());
        props.put("tags", normalizeTags(op.tags()));
        props.put("criticality", crit.name());
        props.put("attrs", json.write(values));
        props.put("attrMeta", json.write(attrMeta));
        props.put("attrsText", attrsText(values));
        props.put("version", 1L);
        props.put("createdAt", c.now());
        props.put("updatedAt", c.now());
        props.put("createdBy", c.actor.name());
        props.put("updatedBy", c.actor.name());

        c.q.run("CREATE (n:N:" + base.name() + ") SET n = $props", Map.of("props", props)).consume();
        writeRefs(c, op.id(), type, res.refs());
        syncNodeFacts(c, op.id());
        return new OpResult(i, op.op(), "node", op.id(), op.ref(), null, reader.node(c.q, c.p, op.id(), Detail.full));
    }

    private OpResult updateNode(Ctx c, int i, Operation op) {
        Views.Node before = reader.node(c.q, c.p, requireId(op), Detail.full);
        checkVersion(c, op, before.version(), t("node '", "nó '") + before.name() + "'");
        NodeTypeDef type = c.cache.requireNodeType(typeIdOf(c, before.id()));
        BaseKind base = type.baseKind();

        Map<String, Object> set = new HashMap<>();
        if (op.name() != null) {
            if (op.name().isBlank()) throw new DomainException(t("'name' cannot be empty", "'name' não pode ficar vazio"));
            set.put("name", op.name().trim());
        }
        if (op.summary() != null) set.put("summary", op.summary().isBlank() ? null : op.summary());
        if (op.description() != null) set.put("description", op.description().isBlank() ? null : op.description());
        if (op.tags() != null) set.put("tags", normalizeTags(op.tags()));
        if (op.criticality() != null) set.put("criticality", Criticality.parse(op.criticality()).name());
        if (op.space() != null) set.put("space", Space.parse(op.space(), base).name());
        if (op.type() != null && !c.cache.requireNodeType(op.type()).id().equals(type.id())) {
            throw new DomainException(t("Changing a node's type is not supported; create a new node and move the relations",
                    "Trocar o tipo de um nó não é suportado; crie um novo nó e migre as relações"));
        }

        Map<String, Object> attrs = new LinkedHashMap<>(before.attributes() == null ? Map.of() : before.attributes());
        Map<String, String> attrMeta = new LinkedHashMap<>(before.attributeCriticality() == null ? Map.of() : before.attributeCriticality());
        AttributeValidator.Result res = null;
        if (op.attributes() != null || op.attributeCriticality() != null) {
            res = new AttributeValidator(c.cache::objectType).validate(type, op.attributes(), false);
            attrs.putAll(res.values());
            for (String k : res.removed()) {
                attrs.remove(k);
                attrMeta.remove(k);
            }
            attrMeta = mergeAttrMeta(type, attrMeta, res, op.attributeCriticality());
            set.put("attrs", json.write(attrs));
            set.put("attrMeta", json.write(attrMeta));
            set.put("attrsText", attrsText(attrs));
        }

        set.put("version", before.version() + 1);
        set.put("updatedAt", c.now());
        set.put("updatedBy", c.actor.name());
        c.q.run("MATCH (n:N {id:$id}) SET n += $set", params("id", before.id(), "set", set)).consume();
        // SET += does not remove properties set to null; remove them explicitly
        for (var e : set.entrySet()) {
            if (e.getValue() == null) c.q.run("MATCH (n:N {id:$id}) REMOVE n." + e.getKey(), Map.of("id", before.id())).consume();
        }
        if (res != null) {
            writeRefs(c, before.id(), type, res.refs());
            for (String k : res.removed()) {
                c.q.run("MATCH (n:N {id:$id})-[r:R {kind:'attr', attrKey:$k}]->() DELETE r", Map.of("id", before.id(), "k", k)).consume();
            }
        }
        syncNodeFacts(c, before.id());
        return new OpResult(i, op.op(), "node", before.id(), null, before, reader.node(c.q, c.p, before.id(), Detail.full));
    }

    private OpResult deleteNode(Ctx c, int i, Operation op) {
        Views.Node before = reader.node(c.q, c.p, requireId(op), Detail.full);
        checkVersion(c, op, before.version(), t("node '", "nó '") + before.name() + "'");
        c.q.run("""
                MATCH (n:N {id:$id})
                OPTIONAL MATCH (n)-[r:R]-()
                WITH n, collect(r.id) AS rids
                OPTIONAL MATCH (f:Fact) WHERE f.originId = n.id OR f.originId IN rids
                DETACH DELETE f
                """, Map.of("id", before.id())).consume();
        c.q.run("MATCH (n:N {id:$id}) DETACH DELETE n", Map.of("id", before.id())).consume();
        return new OpResult(i, op.op(), "node", before.id(), null, before, null);
    }

    // ---------------------------------------------------------------- edges

    private OpResult createEdge(Ctx c, int i, Operation op) {
        if (op.relType() == null) throw new DomainException(t("'relType' is required in create_edge", "'relType' é obrigatório em create_edge"));
        RelTypeDef rt = c.cache.requireRelType(op.relType());
        if (op.from() == null || op.to() == null) throw new DomainException(t("'from' and 'to' are required in create_edge", "'from' e 'to' são obrigatórios em create_edge"));
        if (op.from().equals(op.to())) throw new DomainException(t("An edge from a node to itself is not allowed", "Aresta de um nó para ele mesmo não é permitida"));
        Views.Node a = reader.node(c.q, c.p, op.from(), Detail.summary);
        Views.Node b = reader.node(c.q, c.p, op.to(), Detail.summary);
        NodeTypeDef ta = c.cache.requireNodeType(typeIdOf(c, a.id()));
        NodeTypeDef tb = c.cache.requireNodeType(typeIdOf(c, b.id()));
        if (!rt.allows(ta, tb)) {
            List<String> alternatives = c.cache.allRelTypes().stream()
                    .filter(x -> !x.id().equals(rt.id()) && x.allows(ta, tb))
                    .map(RelTypeDef::name).sorted().toList();
            String hint = alternatives.isEmpty()
                    ? t(" No relation type allows this pair; adjust one with upsert_rel_type if it makes sense.",
                    " Nenhum tipo de relação permite esse par; ajuste um com upsert_rel_type se fizer sentido.")
                    : t(" Relations that allow ", " Relações que permitem ") + ta.name() + " → " + tb.name() + ": " + alternatives + ".";
            throw new DomainException(t("Relation '", "Relação '") + rt.name() + t("' is not allowed from ", "' não permitida de ") + ta.name()
                    + t(" to ", " para ") + tb.name() + t(". Allowed pairs: ", ". Pares permitidos: ")
                    + rt.allowed().stream().map(e -> e.from() + "→" + e.to()).toList() + "." + hint);
        }
        long dup = c.q.run("MATCH (a:N {id:$a})-[r:R {relTypeId:$rt}]->(b:N {id:$b}) RETURN count(r) AS c",
                params("a", a.id(), "b", b.id(), "rt", rt.id())).single().get("c").asLong();
        if (dup > 0) {
            throw new DomainException(t("There is already an edge '", "Já existe aresta '") + rt.name() + t("' from '", "' de '") + a.name()
                    + t("' to '", "' para '") + b.name() + "'");
        }

        Map<String, Object> props = new HashMap<>();
        props.put("id", op.id());
        props.put("projectId", c.p);
        props.put("relTypeId", rt.id());
        props.put("name", rt.name());
        props.put("base", rt.relBase().name());
        props.put("kind", "rel");
        putIfNotNull(props, "description", op.description());
        props.put("criticality", Criticality.parse(op.criticality()).name());
        props.put("tags", normalizeTags(op.tags()));
        props.put("version", 1L);
        props.put("createdAt", c.now());
        props.put("updatedAt", c.now());
        props.put("createdBy", c.actor.name());
        c.q.run("MATCH (a:N {id:$a}), (b:N {id:$b}) CREATE (a)-[r:R]->(b) SET r = $props",
                params("a", a.id(), "b", b.id(), "props", props)).consume();
        syncEdgeFact(c, op.id());
        return new OpResult(i, op.op(), "edge", op.id(), op.ref(), null, reader.edge(c.q, c.p, op.id()));
    }

    private OpResult updateEdge(Ctx c, int i, Operation op) {
        Views.Edge before = reader.edge(c.q, c.p, requireId(op));
        if (!"rel".equals(before.kind())) {
            throw new DomainException(t("Attribute edges are edited through the node's node_ref attribute",
                    "Arestas de atributo são editadas pelo atributo node_ref do nó"));
        }
        checkVersion(c, op, before.version(), t("edge '", "aresta '") + before.relType() + "'");
        Map<String, Object> set = new HashMap<>();
        if (op.description() != null) set.put("description", blankToNull(op.description()));
        if (op.criticality() != null) set.put("criticality", Criticality.parse(op.criticality()).name());
        if (op.tags() != null) set.put("tags", normalizeTags(op.tags()));
        set.put("version", before.version() + 1);
        set.put("updatedAt", c.now());
        c.q.run("MATCH ()-[r:R {id:$id}]->() SET r += $set", params("id", before.id(), "set", set)).consume();
        for (var e : set.entrySet()) {
            if (e.getValue() == null) c.q.run("MATCH ()-[r:R {id:$id}]->() REMOVE r." + e.getKey(), Map.of("id", before.id())).consume();
        }
        syncEdgeFact(c, before.id());
        return new OpResult(i, op.op(), "edge", before.id(), null, before, reader.edge(c.q, c.p, before.id()));
    }

    private OpResult deleteEdge(Ctx c, int i, Operation op) {
        Views.Edge before = reader.edge(c.q, c.p, requireId(op));
        if (!"rel".equals(before.kind())) {
            throw new DomainException(t("Attribute edges are removed through the node's node_ref attribute",
                    "Arestas de atributo são removidas pelo atributo node_ref do nó"));
        }
        checkVersion(c, op, before.version(), t("edge '", "aresta '") + before.relType() + "'");
        c.q.run("MATCH (f:Fact {originId:$id}) DETACH DELETE f", Map.of("id", before.id())).consume();
        c.q.run("MATCH ()-[r:R {id:$id}]->() DELETE r", Map.of("id", before.id())).consume();
        return new OpResult(i, op.op(), "edge", before.id(), null, before, null);
    }

    // ---------------------------------------------------------------- manual facts

    private OpResult createFact(Ctx c, int i, Operation op) {
        if (blank(op.text())) throw new DomainException(t("'text' is required in create_fact", "'text' é obrigatório em create_fact"));
        Criticality crit = op.criticality() == null ? Criticality.critical : Criticality.parse(op.criticality());
        Rigidity rig = Rigidity.parse(op.rigidity(), crit.defaultRigidity());
        List<String> about = op.about() == null ? List.of() : op.about();
        requireNodes(c, about);
        upsertFact(c, op.id(), op.text().trim(), crit, rig, "manual", null, null, about, true);
        return new OpResult(i, op.op(), "fact", op.id(), op.ref(), null, reader.fact(c.q, c.p, op.id()));
    }

    private OpResult updateFact(Ctx c, int i, Operation op) {
        Views.Fact before = reader.fact(c.q, c.p, requireId(op));
        checkVersion(c, op, before.version(), t("fact", "fato"));
        boolean manual = "manual".equals(before.origin());
        if (!manual && (op.text() != null || op.about() != null || op.criticality() != null)) {
            throw new DomainException(t("Fact mirrored from ", "Fato espelhado de ") + before.origin()
                    + t(": edit its origin (node/attribute/edge). Only 'rigidity' can change here.",
                    ": edite a origem (nó/atributo/aresta). Aqui só 'rigidity' pode mudar."));
        }
        Criticality crit = op.criticality() != null ? Criticality.parse(op.criticality()) : Criticality.parse(before.criticality());
        Rigidity rig = Rigidity.parse(op.rigidity(), Rigidity.parse(before.rigidity(), crit.defaultRigidity()));
        List<String> about = op.about() != null ? op.about() : before.about().stream().map(Views.Ref::id).toList();
        requireNodes(c, about);
        String text = op.text() != null ? op.text().trim() : before.text();
        if (manual) {
            upsertFact(c, before.id(), text, crit, rig, "manual", null, null, about, true);
        } else {
            c.q.run("MATCH (f:Fact {id:$id}) SET f.rigidity=$r, f.version=f.version+1, f.updatedAt=$now",
                    params("id", before.id(), "r", rig.name(), "now", c.now())).consume();
        }
        return new OpResult(i, op.op(), "fact", before.id(), null, before, reader.fact(c.q, c.p, before.id()));
    }

    private OpResult deleteFact(Ctx c, int i, Operation op) {
        Views.Fact before = reader.fact(c.q, c.p, requireId(op));
        if (!"manual".equals(before.origin())) {
            throw new DomainException(t("Fact mirrored from ", "Fato espelhado de ") + before.origin()
                    + t(": to remove it, set the origin's criticality back to 'normal'", ": para removê-lo, volte a criticidade da origem para 'normal'"));
        }
        checkVersion(c, op, before.version(), t("fact", "fato"));
        c.q.run("MATCH (f:Fact {id:$id}) DETACH DELETE f", Map.of("id", before.id())).consume();
        return new OpResult(i, op.op(), "fact", before.id(), null, before, null);
    }

    // ---------------------------------------------------------------- schema

    private OpResult upsertNodeType(Ctx c, int i, Operation op) {
        NodeTypeDef in = json.convert(requireDefinition(op), NodeTypeDef.class);
        // op.id points to an existing type (rename) or is the new id from prepare; otherwise match by name
        NodeTypeDef before = c.cache.nodeType(op.id()).or(() -> c.cache.nodeType(in.name())).orElse(null);
        String name = in.name() != null ? in.name().trim() : (before != null ? before.name() : null);
        if (blank(name)) throw new DomainException(t("definition.name is required", "definition.name é obrigatório"));
        String base = in.base() != null ? BaseKind.parse(in.base()).name() : (before != null ? before.base() : null);
        if (base == null) {
            throw new DomainException(t("definition.base is required for a new type", "definition.base é obrigatório para um tipo novo")
                    + " (Agent, Place, Object, Ability, Concept, Event, Media, Note)");
        }
        var other = c.cache.nodeType(name);
        if (other.isPresent() && (before == null || !other.get().id().equals(before.id()))) {
            throw new DomainException(t("There is already a node type named '", "Já existe um tipo de nó chamado '") + name + "'");
        }
        String id = before != null ? before.id() : op.id();
        if (before != null && !before.base().equals(base)) {
            long count = countNodesOfType(c, before.id());
            if (count > 0) {
                throw new DomainException(t("Cannot change the base of '", "Não dá para trocar a base de '") + name + t("': there are ", "': há ")
                        + count + t(" nodes of this type", " nós desse tipo"));
            }
        }
        String systemKey = in.systemKey() != null ? in.systemKey() : (before != null ? before.systemKey() : null);
        if (systemKey != null) {
            var holder = c.cache.nodeTypeBySystem(systemKey);
            if (holder.isPresent() && !holder.get().id().equals(id)) {
                throw new DomainException(t("System key '", "Chave de sistema '") + systemKey + t("' is already used by type '", "' já é usada pelo tipo '")
                        + holder.get().name() + "'");
            }
        }
        List<AttrDef> attrs = in.attributes() != null ? in.attributes() : (before != null ? before.attrs() : List.of());
        Set<String> keys = new HashSet<>();
        for (AttrDef a : attrs) {
            a.check(t("type ", "tipo ") + name);
            if (!keys.add(a.key())) throw new DomainException(t("Attribute '", "Atributo '") + a.key() + t("' repeated in type ", "' repetido no tipo ") + name);
            String ot = a.objectType();
            if (ot != null && c.cache.objectType(ot) == null) {
                throw new DomainException("ObjectType '" + ot + t("' (attribute ", "' (atributo ") + a.key()
                        + t(") does not exist; create it with upsert_object_type first", ") não existe; crie com upsert_object_type antes"));
            }
        }
        NodeTypeDef after = new NodeTypeDef(id, c.p, name, base,
                in.description() != null ? in.description() : (before != null ? before.description() : null),
                in.color() != null ? in.color() : (before != null ? before.color() : null),
                in.strict() != null ? in.strict() : (before != null ? before.strict() : null),
                attrs, systemKey);
        schema.saveNodeType(c.q, after);
        if (before != null) {
            c.cache.removeNodeType(before);
            if (!before.name().equals(name)) {
                c.q.run("MATCH (n:N {projectId:$p, typeId:$id}) SET n.typeName=$name", params("p", c.p, "id", id, "name", name)).consume();
            }
        }
        c.cache.put(after);
        return new OpResult(i, op.op(), "node_type", id, op.ref(), before, after);
    }

    private OpResult upsertRelType(Ctx c, int i, Operation op) {
        RelTypeDef in = json.convert(requireDefinition(op), RelTypeDef.class);
        var existing = c.cache.relType(op.id()).or(() -> c.cache.relType(in.name()));
        RelTypeDef before = existing.orElse(null);
        String name = in.name() != null ? in.name().trim() : (before != null ? before.name() : null);
        if (blank(name)) throw new DomainException(t("definition.name is required", "definition.name é obrigatório"));
        var other = c.cache.relType(name);
        if (other.isPresent() && (before == null || !other.get().id().equals(before.id()))) {
            throw new DomainException(t("There is already a relation type named '", "Já existe um tipo de relação chamado '") + name + "'");
        }
        String base = in.base() != null ? RelBase.parse(in.base()).name() : (before != null ? before.base() : null);
        if (base == null) {
            throw new DomainException(t("definition.base is required for a new type (SOCIAL, POSSESSION, KNOWLEDGE, ...)",
                    "definition.base é obrigatório para um tipo novo (SOCIAL, POSSESSION, KNOWLEDGE, ...)"));
        }
        List<RelTypeDef.Endpoint> allowed = in.allowed() != null ? in.allowed() : (before != null ? before.allowed() : List.of());
        for (var e : allowed) {
            checkEndpoint(c, e.from());
            checkEndpoint(c, e.to());
        }
        String id = before != null ? before.id() : op.id();
        String systemKey = in.systemKey() != null ? in.systemKey() : (before != null ? before.systemKey() : null);
        if (systemKey != null) {
            var holder = c.cache.relTypeBySystem(systemKey);
            if (holder.isPresent() && !holder.get().id().equals(id)) {
                throw new DomainException(t("System key '", "Chave de sistema '") + systemKey + t("' is already used by relation '", "' já é usada pela relação '")
                        + holder.get().name() + "'");
            }
        }
        RelTypeDef after = new RelTypeDef(id, c.p, name, base,
                in.description() != null ? in.description() : (before != null ? before.description() : null),
                in.symmetric() != null ? in.symmetric() : (before != null ? before.symmetric() : null),
                in.inverseName() != null ? in.inverseName() : (before != null ? before.inverseName() : null),
                in.temporal() != null ? in.temporal() : (before != null ? before.temporal() : null),
                allowed, systemKey);
        schema.saveRelType(c.q, after);
        if (before != null) {
            c.cache.removeRelType(before);
            if (!before.name().equals(name) || !before.base().equals(base)) {
                c.q.run("MATCH ()-[r:R {projectId:$p, relTypeId:$id}]->() SET r.name=$name, r.base=$base",
                        params("p", c.p, "id", id, "name", name, "base", base)).consume();
            }
        }
        c.cache.put(after);
        return new OpResult(i, op.op(), "rel_type", id, op.ref(), before, after);
    }

    private void checkEndpoint(Ctx c, String pattern) {
        if (pattern == null || pattern.equals("*")) return;
        if (pattern.startsWith("@")) {
            BaseKind.parse(pattern.substring(1));
            return;
        }
        c.cache.requireNodeType(pattern);
    }

    private OpResult upsertObjectType(Ctx c, int i, Operation op) {
        ObjectTypeDef in = json.convert(requireDefinition(op), ObjectTypeDef.class);
        ObjectTypeDef before = c.cache.objectType(op.id());
        if (before == null && in.name() != null) before = c.cache.objectType(in.name());
        String name = in.name() != null ? in.name().trim() : (before != null ? before.name() : null);
        if (blank(name)) throw new DomainException(t("definition.name is required", "definition.name é obrigatório"));
        List<AttrDef> fields = in.fields() != null ? in.fields() : (before != null ? before.fieldList() : List.of());
        for (AttrDef f : fields) {
            f.check("ObjectType " + name);
            if (f.type() == DataType.NODE_REF) throw new DomainException(t("ObjectType does not accept node_ref fields (", "ObjectType não aceita campos node_ref (") + f.key() + ")");
            if (f.objectType() != null && !f.objectType().equalsIgnoreCase(name) && c.cache.objectType(f.objectType()) == null) {
                throw new DomainException("ObjectType '" + f.objectType() + t("' (field ", "' (campo ") + f.key() + t(") does not exist", ") não existe"));
            }
        }
        String id = before != null ? before.id() : op.id();
        ObjectTypeDef after = new ObjectTypeDef(id, c.p, name, in.description() != null ? in.description() : (before != null ? before.description() : null), fields);
        schema.saveObjectType(c.q, after);
        c.cache.put(after);
        return new OpResult(i, op.op(), "object_type", id, op.ref(), before, after);
    }

    private OpResult deleteNodeType(Ctx c, int i, Operation op) {
        NodeTypeDef nt = c.cache.requireNodeType(op.id() != null ? op.id() : op.type());
        schema.deleteNodeType(c.q, nt);
        c.cache.removeNodeType(nt);
        return new OpResult(i, op.op(), "node_type", nt.id(), null, nt, null);
    }

    private OpResult deleteRelType(Ctx c, int i, Operation op) {
        RelTypeDef rt = c.cache.requireRelType(op.id() != null ? op.id() : op.relType());
        schema.deleteRelType(c.q, rt);
        c.cache.removeRelType(rt);
        return new OpResult(i, op.op(), "rel_type", rt.id(), null, rt, null);
    }

    // ---------------------------------------------------------------- directives

    private OpResult upsertDirective(Ctx c, int i, Operation op) {
        var existing = reader.directive(c.q, c.p, op.id());
        Map<String, Object> def = op.definition() == null ? Map.of() : op.definition();
        String text = op.text() != null ? op.text().trim() : (existing != null ? existing.text() : null);
        if (blank(text)) throw new DomainException(t("'text' is required in upsert_directive (when creating)", "'text' é obrigatório em upsert_directive (ao criar)"));
        List<String> when = stringList(def, "when", existing != null ? existing.when() : List.of("always"));
        for (String w : when) {
            if (!List.of("always", "session", "context", "write", "validate").contains(w)) {
                throw new DomainException(t("invalid when: '", "when inválido: '") + w + t("'. Valid: always, session, context, write, validate",
                        "'. Válidos: always, session, context, write, validate"));
            }
        }
        List<String> bases = stringList(def, "bases", existing != null ? existing.bases() : List.of());
        bases.forEach(BaseKind::parse);
        List<String> types = stringList(def, "types", existing != null ? existing.types() : List.of());
        Object pr = def.get("priority");
        int priority = pr instanceof Number n ? n.intValue() : (existing != null ? existing.priority() : 0);
        Object en = def.get("enabled");
        boolean enabled = en instanceof Boolean b ? b : (existing == null || existing.enabled());
        c.q.run("""
                MERGE (d:Directive {id:$id})
                ON CREATE SET d.createdAt=$now, d.version=0
                SET d.projectId=$p, d.text=$text, d.when=$when, d.bases=$bases, d.types=$types, d.priority=$priority,
                    d.enabled=$enabled, d.updatedAt=$now, d.version=d.version+1
                """, params("id", op.id(), "now", c.now(), "p", c.p, "text", text, "when", when, "bases", bases, "types", types,
                "priority", priority, "enabled", enabled)).consume();
        return new OpResult(i, op.op(), "directive", op.id(), op.ref(), existing, reader.directive(c.q, c.p, op.id()));
    }

    private OpResult deleteDirective(Ctx c, int i, Operation op) {
        var existing = reader.directive(c.q, c.p, requireId(op));
        if (existing == null) throw new NotFoundException(t("Directive '", "Diretiva '") + op.id() + t("' does not exist in the project", "' não existe no projeto"));
        c.q.run("MATCH (d:Directive {id:$id}) DELETE d", Map.of("id", op.id())).consume();
        return new OpResult(i, op.op(), "directive", op.id(), null, existing, null);
    }

    private static List<String> stringList(Map<String, Object> def, String key, List<String> fallback) {
        Object v = def.get(key);
        if (v == null) return fallback;
        if (!(v instanceof List<?> l)) throw new DomainException("'" + key + t("' must be a list of strings", "' deve ser uma lista de textos"));
        return l.stream().map(String::valueOf).toList();
    }

    // =====================================================================
    // Criticality mirrored into :Fact
    // =====================================================================

    /** Re-mirrors the facts of the given nodes (used after schema migrations such as localization). */
    public void resyncFacts(SimpleQueryRunner q, String projectId, List<String> nodeIds) {
        var ctx = new Ctx(q, projectId, Actor.system, schema.cache(q, projectId));
        for (String id : nodeIds) syncNodeFacts(ctx, id);
        var edgeIds = q.run("MATCH (f:Fact {projectId:$p, originKind:'edge'}) RETURN f.originId AS id", Map.of("p", projectId)).list(r -> r.get("id").asString());
        for (String e : edgeIds) {
            if (!reader.edgesById(q, projectId, List.of(e)).isEmpty()) syncEdgeFact(ctx, e);
        }
    }

    private void syncNodeFacts(Ctx c, String nodeId) {
        Views.Node n = reader.node(c.q, c.p, nodeId, Detail.full);
        NodeTypeDef type = c.cache.requireNodeType(typeIdOf(c, nodeId));
        Set<String> keep = new HashSet<>();

        Criticality nc = Criticality.parse(n.criticality());
        if (nc.isPinned()) {
            String id = "f_" + nodeId + "__node";
            keep.add(id);
            String text = n.name() + " (" + type.name() + ")" + (n.summary() != null ? ": " + n.summary() : "");
            upsertFact(c, id, text, nc, null, "node", nodeId, null, List.of(nodeId), false);
        }
        Map<String, String> meta = n.attributeCriticality() == null ? Map.of() : n.attributeCriticality();
        for (var e : meta.entrySet()) {
            Criticality ac = Criticality.parse(e.getValue());
            if (!ac.isPinned()) continue;
            String key = e.getKey();
            String label = type.attr(key).map(a -> a.label() != null ? a.label() : a.key()).orElse(key);
            String rendered;
            List<String> about = new ArrayList<>(List.of(nodeId));
            if (n.refs() != null && n.refs().containsKey(key)) {
                List<String> targets = n.refs().get(key);
                about.addAll(targets);
                rendered = reader.nodes(c.q, c.p, targets, Detail.id).stream().map(Views.Node::name).collect(Collectors.joining(", "));
            } else if (n.attributes() != null && n.attributes().containsKey(key)) {
                Object v = n.attributes().get(key);
                rendered = v instanceof String s ? s : json.write(v);
            } else {
                continue;
            }
            if (rendered.length() > 600) rendered = rendered.substring(0, 600) + "…";
            String id = "f_" + nodeId + "__" + key;
            keep.add(id);
            upsertFact(c, id, n.name() + " — " + label + ": " + rendered, ac, null, "attr", nodeId, key, about, false);
        }
        c.q.run("MATCH (f:Fact {originId:$id}) WHERE f.originKind IN ['node','attr'] AND NOT f.id IN $keep DETACH DELETE f",
                params("id", nodeId, "keep", List.copyOf(keep))).consume();
    }

    private void syncEdgeFact(Ctx c, String edgeId) {
        Views.Edge e = reader.edge(c.q, c.p, edgeId);
        String id = "f_" + edgeId;
        Criticality ec = Criticality.parse(e.criticality());
        if (!ec.isPinned()) {
            c.q.run("MATCH (f:Fact {id:$id}) DETACH DELETE f", Map.of("id", id)).consume();
            return;
        }
        StringBuilder text = new StringBuilder(e.fromName() + " —" + e.relType() + "→ " + e.toName());
        if (e.description() != null) text.append(": ").append(e.description());
        upsertFact(c, id, text.toString(), ec, null, "edge", edgeId, null, List.of(e.from(), e.to()), false);
    }

    /** rigidity null = keep the current one (or the criticality default, if new). */
    private void upsertFact(Ctx c, String id, String text, Criticality crit, Rigidity rigidity, String originKind,
                            String originId, String originKey, List<String> about, boolean manual) {
        c.q.run("""
                MERGE (f:Fact {id:$id})
                ON CREATE SET f.createdAt=$now, f.version=0, f.createdBy=$actor
                SET f.projectId=$p, f.text=$text, f.criticality=$crit, f.originKind=$kind, f.originId=$originId,
                    f.originKey=$originKey, f.updatedAt=$now, f.version=f.version+1,
                    f.rigidity = CASE WHEN $rig IS NOT NULL THEN $rig ELSE coalesce(f.rigidity, $defaultRig) END
                REMOVE f:Foundational:Critical
                FOREACH (_ IN CASE WHEN $crit = 'foundational' THEN [1] ELSE [] END | SET f:Foundational)
                FOREACH (_ IN CASE WHEN $crit = 'critical' THEN [1] ELSE [] END | SET f:Critical)
                WITH f
                OPTIONAL MATCH (f)-[old:ABOUT]->()
                DELETE old
                WITH DISTINCT f
                UNWIND $about AS nid
                MATCH (n:N {id:nid})
                MERGE (f)-[:ABOUT]->(n)
                """, params("id", id, "now", Instant.now().toString(), "actor", manual ? "manual" : "mirror", "p", c.p, "text", text,
                "crit", crit.name(), "kind", originKind, "originId", originId, "originKey", originKey,
                "rig", rigidity == null ? null : rigidity.name(), "defaultRig", crit.defaultRigidity().name(), "about", about)).consume();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private void requireProject(SimpleQueryRunner q, String projectId) {
        if (!q.run("MATCH (p:Project {id:$id}) RETURN p", Map.of("id", projectId)).hasNext()) {
            throw new NotFoundException(t("Project '", "Projeto '") + projectId + t("' does not exist", "' não existe"));
        }
    }

    private Map<String, String> mergeAttrMeta(NodeTypeDef type, Map<String, String> meta, AttributeValidator.Result res, Map<String, String> explicit) {
        for (var v : res.values().keySet()) applyDefaultCrit(type, meta, v);
        for (var r : res.refs()) applyDefaultCrit(type, meta, r.attrKey());
        if (explicit != null) {
            for (var e : explicit.entrySet()) {
                if (type.isStrict() && type.attr(e.getKey()).isEmpty()) {
                    throw new DomainException(t("attributeCriticality: attribute '", "attributeCriticality: atributo '") + e.getKey()
                            + t("' does not exist in type ", "' não existe no tipo ") + type.name());
                }
                Criticality cr = Criticality.parse(e.getValue());
                if (cr == Criticality.normal) meta.remove(e.getKey()); else meta.put(e.getKey(), cr.name());
            }
        }
        return meta;
    }

    private static void applyDefaultCrit(NodeTypeDef type, Map<String, String> meta, String key) {
        if (meta.containsKey(key)) return;
        type.attr(key).map(AttrDef::defaultCriticality).filter(d -> !"normal".equals(d)).ifPresent(d -> meta.put(key, Criticality.parse(d).name()));
    }

    private void writeRefs(Ctx c, String nodeId, NodeTypeDef type, List<AttributeValidator.RefTarget> refs) {
        for (var ref : refs) {
            for (String target : ref.targetIds()) {
                if (target.equals(nodeId)) {
                    throw new DomainException(t("Attribute '", "Atributo '") + ref.attrKey() + t("' cannot point to the node itself", "' não pode apontar para o próprio nó"));
                }
                reader.node(c.q, c.p, target, Detail.id);
                NodeTypeDef tt = c.cache.requireNodeType(typeIdOf(c, target));
                if (!RelTypeDef.matches(ref.refType(), tt)) {
                    throw new DomainException(t("Attribute '", "Atributo '") + ref.attrKey() + t("' expects a node of type '", "' espera um nó do tipo '")
                            + ref.refType() + t("', but '", "', mas '") + target + t("' is ", "' é ") + tt.name());
                }
            }
            c.q.run("MATCH (n:N {id:$id})-[r:R {kind:'attr', attrKey:$k}]->() DELETE r", Map.of("id", nodeId, "k", ref.attrKey())).consume();
            c.q.run("""
                    UNWIND $targets AS tid
                    MATCH (n:N {id:$id}), (t:N {id:tid})
                    CREATE (n)-[:R {id:'e_' + $id + '_' + $k + '_' + tid, projectId:$p, kind:'attr', attrKey:$k, name:$k,
                                    base:'REFERENCE', criticality:'normal', version:1, createdAt:$now}]->(t)
                    """, params("targets", ref.targetIds(), "id", nodeId, "k", ref.attrKey(), "p", c.p, "now", c.now())).consume();
        }
    }

    private void requireNodes(Ctx c, List<String> ids) {
        if (ids.isEmpty()) return;
        var found = reader.nodes(c.q, c.p, ids, Detail.id).stream().map(Views.Node::id).collect(Collectors.toSet());
        var missing = ids.stream().filter(x -> !found.contains(x)).toList();
        if (!missing.isEmpty()) throw new NotFoundException(t("Nodes not found: ", "Nós não encontrados: ") + missing);
    }

    private String typeIdOf(Ctx c, String nodeId) {
        return c.q.run("MATCH (n:N {id:$id}) RETURN n.typeId AS t", Map.of("id", nodeId)).single().get("t").asString();
    }

    private long countNodesOfType(Ctx c, String typeId) {
        return c.q.run("MATCH (n:N {projectId:$p, typeId:$t}) RETURN count(n) AS c", Map.of("p", c.p, "t", typeId)).single().get("c").asLong();
    }

    private void checkVersion(Ctx c, Operation op, Long current, String what) {
        if (op.expectedVersion() == null) {
            if (c.actor == Actor.llm) {
                throw new DomainException(t("Pass expectedVersion to change ", "Informe expectedVersion para alterar ") + what
                        + t(" (current version: ", " (versão atual: ") + current
                        + t("). Read the current state before writing (node_get / edge_get / graph_focus).",
                        "). Leia o estado atual antes de escrever (node_get / edge_get / graph_focus)."));
            }
            return;
        }
        if (!op.expectedVersion().equals(current)) {
            throw new ConflictException(t("Version conflict on ", "Conflito de versão em ") + what + t(": expected ", ": esperado ") + op.expectedVersion()
                    + t(", current ", ", atual ") + current
                    + t(". Someone changed it after you read it; read again and reapply.", ". Alguém alterou depois da sua leitura; releia e reaplique."));
        }
    }

    private static String requireId(Operation op) {
        if (op.id() == null || op.id().isBlank()) throw new DomainException(t("'id' is required in ", "'id' é obrigatório em ") + op.op());
        return op.id();
    }

    private static Map<String, Object> requireDefinition(Operation op) {
        if (op.definition() == null) throw new DomainException(t("'definition' is required in ", "'definition' é obrigatório em ") + op.op());
        return op.definition();
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream().filter(Objects::nonNull).map(x -> x.trim().toLowerCase(Locale.ROOT)).filter(x -> !x.isEmpty()).distinct().sorted().toList();
    }

    private static String attrsText(Map<String, Object> attrs) {
        StringBuilder sb = new StringBuilder();
        attrs.forEach((k, v) -> {
            if (v instanceof String || v instanceof Number || v instanceof Boolean) sb.append(k).append(": ").append(v).append("; ");
        });
        return sb.toString();
    }

    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Optimistic concurrency conflict (stale version). */
    public static class ConflictException extends DomainException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
