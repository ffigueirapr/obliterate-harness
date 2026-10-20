package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Ids;
import com.obliterateharness.graph.ChangeExecutor;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.JsonCodec;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.store.AuditRepository;
import com.obliterateharness.store.TextVersionRepository;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

/**
 * Copies a whole project (backlog item 1): schema, nodes, edges with anchors, facts, directives and text history,
 * with new ids. Returns the old → new id map. Optionally translates the copy's schema to another language.
 */
@Service
public class CloneService {

    /**
     * @param includeUnits      narrative units (chapters, scenes...) and everything anchored to them
     * @param includeFacts      manual facts (mirrored facts always follow their nodes/edges)
     * @param includeDirectives the project's own directives
     * @param includeTemplates  applied templates and their beats
     */
    public record Options(boolean includeUnits, boolean includeFacts, boolean includeDirectives, boolean includeTemplates, boolean includeNodes) {
        public Options(boolean includeUnits, boolean includeFacts, boolean includeDirectives, boolean includeTemplates) {
            this(includeUnits, includeFacts, includeDirectives, includeTemplates, true);
        }

        /** Only the schema, the manual facts (rules) and the directives: how a context's definition becomes a new project. */
        public static Options schemaOnly() {
            return new Options(false, true, true, false, false);
        }

        public static Options all() {
            return new Options(true, true, true, true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Result(String projectId, String name, Map<String, String> idMap, Map<String, Integer> counts,
                         LocalizationService.Report localization, List<String> notes) {}

    private final Graph graph;
    private final SchemaStore schema;
    private final JsonCodec json;
    private final ProjectService projects;
    private final LocalizationService localization;
    private final TextVersionRepository texts;
    private final AuditRepository audit;

    public CloneService(Graph graph, SchemaStore schema, JsonCodec json, ProjectService projects, LocalizationService localization,
                        TextVersionRepository texts, AuditRepository audit) {
        this.graph = graph;
        this.schema = schema;
        this.json = json;
        this.projects = projects;
        this.localization = localization;
        this.texts = texts;
        this.audit = audit;
    }

    public Result cloneProject(String sourceId, String newName, Options options, String translateTo) {
        var src = projects.get(sourceId);
        if (newName == null || newName.isBlank()) throw new DomainException(t("newName is required", "newName é obrigatório"));
        Options opt = options == null ? Options.all() : options;
        var copy = projects.create(newName.trim(), src.description(), src.profile(), src.requireReviewForLlm(), Lang.parse(src.locale(), Lang.pt).name());
        projects.update(copy.id(), null, null, null, null, src.styleSample(), src.styleReference());
        String pid = copy.id();
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        String now = Instant.now().toString();

        graph.write(tx -> {
            var cache = schema.cache(tx, sourceId);
            Set<String> skipTypes = new java.util.HashSet<>();
            if (!opt.includeTemplates()) {
                cache.nodeTypeBySystem(Reserved.TEMPLATE).ifPresent(x -> skipTypes.add(x.id()));
                cache.nodeTypeBySystem(Reserved.BEAT).ifPresent(x -> skipTypes.add(x.id()));
            }

            // ---- schema
            Map<String, String> typeIds = new HashMap<>();
            for (var nt : schema.nodeTypes(tx, sourceId)) {
                String nid = Ids.next("nt");
                typeIds.put(nt.id(), nid);
                schema.saveNodeType(tx, new com.obliterateharness.domain.NodeTypeDef(nid, pid, nt.name(), nt.base(), nt.description(), nt.color(), nt.strict(),
                        nt.attrs(), nt.systemKey()));
            }
            Map<String, String> relIds = new HashMap<>();
            for (var rt : schema.relTypes(tx, sourceId)) {
                if (SchemaStore.CONTAINS.equals(rt.name())) {
                    relIds.put(rt.id(), "rt_contains_" + pid);
                    continue;
                }
                String nid = Ids.next("rt");
                relIds.put(rt.id(), nid);
                schema.saveRelType(tx, new com.obliterateharness.domain.RelTypeDef(nid, pid, rt.name(), rt.base(), rt.description(), rt.symmetric(),
                        rt.inverseName(), rt.temporal(), rt.allowed(), rt.systemKey()));
            }
            for (var ot : schema.objectTypes(tx, sourceId)) {
                schema.saveObjectType(tx, new com.obliterateharness.domain.ObjectTypeDef(Ids.next("ot"), pid, ot.name(), ot.description(), ot.fieldList()));
            }
            counts.put("nodeTypes", typeIds.size());
            counts.put("relTypes", relIds.size() - 1);

            // ---- nodes
            var nodes = tx.run("MATCH (n:N {projectId:$p}) RETURN n, labels(n) AS labels", Map.of("p", sourceId)).list();
            List<Map<String, Object>> nodeRows = new ArrayList<>();
            for (var r : nodes) {
                if (!opt.includeNodes()) break;
                Value n = r.get("n");
                boolean unit = r.get("labels").asList(Value::asString).contains("Unit");
                if (unit && !opt.includeUnits()) continue;
                if (skipTypes.contains(n.get("typeId").asString())) continue;
                String nid = Ids.next("n");
                ids.put(n.get("id").asString(), nid);
                nodeRows.add(new HashMap<>(n.asMap()));
            }
            for (var props : nodeRows) {
                String oldId = (String) props.get("id");
                props.put("id", ids.get(oldId));
                props.put("projectId", pid);
                props.put("typeId", typeIds.get((String) props.get("typeId")));
                // attribute values holding node ids (e.g. a template's scope) follow the copy
                Map<String, Object> attrs = json.readMap((String) props.get("attrs"));
                attrs.replaceAll((k, v) -> v instanceof String s && ids.containsKey(s) ? ids.get(s) : v);
                props.put("attrs", json.write(attrs));
                String base = (String) props.get("base");
                tx.run("CREATE (n:N:" + com.obliterateharness.domain.BaseKind.parse(base).name() + ") SET n = $props", Map.of("props", props)).consume();
            }
            counts.put("nodes", nodeRows.size());

            // ---- edges between copied nodes
            var edges = tx.run("MATCH (a:N {projectId:$p})-[r:R]->(b:N) RETURN a.id AS a, b.id AS b, r", Map.of("p", sourceId)).list();
            Map<String, String> edgeIds = new HashMap<>();
            int edgeCount = 0, droppedAnchors = 0;
            for (var r : edges) {
                String a = ids.get(r.get("a").asString()), b = ids.get(r.get("b").asString());
                if (a == null || b == null) continue;
                Map<String, Object> props = new HashMap<>(r.get("r").asMap());
                String kind = (String) props.get("kind");
                String oldEdge = (String) props.get("id");
                String nid = switch (kind == null ? "rel" : kind) {
                    case "structure" -> "e_s_" + b;
                    case "attr" -> "e_" + a + "_" + props.get("attrKey") + "_" + b;
                    default -> Ids.next("e");
                };
                edgeIds.put(oldEdge, nid);
                props.put("id", nid);
                props.put("projectId", pid);
                if (props.get("relTypeId") != null) props.put("relTypeId", relIds.get((String) props.get("relTypeId")));
                for (String anchor : List.of("fromUnit", "toUnit")) {
                    Object u = props.get(anchor);
                    if (u == null) continue;
                    if (ids.containsKey((String) u)) props.put(anchor, ids.get((String) u));
                    else {
                        props.remove(anchor);
                        droppedAnchors++;
                    }
                }
                tx.run("MATCH (a:N {id:$a}), (b:N {id:$b}) CREATE (a)-[r:R]->(b) SET r = $props", params("a", a, "b", b, "props", props)).consume();
                edgeCount++;
            }
            counts.put("edges", edgeCount);
            if (droppedAnchors > 0) {
                notes.add(droppedAnchors + t(" time anchor(s) pointed to units that were not copied and were removed.",
                        " âncora(s) de tempo apontavam para unidades não copiadas e foram removidas."));
            }

            // ---- facts
            var facts = tx.run("""
                    MATCH (f:Fact {projectId:$p}) OPTIONAL MATCH (f)-[:ABOUT]->(n:N)
                    RETURN f, labels(f) AS labels, collect(n.id) AS about
                    """, Map.of("p", sourceId)).list();
            int factCount = 0;
            for (var r : facts) {
                Map<String, Object> props = new HashMap<>(r.get("f").asMap());
                String kind = (String) props.get("originKind");
                String origin = (String) props.get("originId");
                String nid;
                if ("manual".equals(kind)) {
                    if (!opt.includeFacts()) continue;
                    nid = Ids.next("f");
                } else if ("edge".equals(kind)) {
                    if (!edgeIds.containsKey(origin)) continue;
                    nid = "f_" + edgeIds.get(origin);
                    props.put("originId", edgeIds.get(origin));
                } else {
                    if (!ids.containsKey(origin)) continue;
                    nid = "f_" + ids.get(origin) + "__" + ("attr".equals(kind) ? props.get("originKey") : "node");
                    props.put("originId", ids.get(origin));
                }
                props.put("id", nid);
                props.put("projectId", pid);
                Object reveal = props.get("revealAt");
                if (reveal != null) {
                    if (ids.containsKey((String) reveal)) props.put("revealAt", ids.get((String) reveal)); else props.remove("revealAt");
                }
                List<String> labels = r.get("labels").asList(Value::asString);
                String extra = labels.contains("Foundational") ? ":Foundational" : labels.contains("Critical") ? ":Critical" : "";
                List<String> about = r.get("about").asList(Value::asString).stream().filter(ids::containsKey).map(ids::get).toList();
                tx.run("CREATE (f:Fact" + extra + ") SET f = $props WITH f UNWIND $about AS nid MATCH (n:N {id:nid}) MERGE (f)-[:ABOUT]->(n)",
                        params("props", props, "about", about)).consume();
                factCount++;
            }
            counts.put("facts", factCount);

            // ---- directives
            if (opt.includeDirectives()) {
                var dirs = tx.run("MATCH (d:Directive {projectId:$p}) RETURN d", Map.of("p", sourceId)).list();
                for (var r : dirs) {
                    Map<String, Object> props = new HashMap<>(r.get("d").asMap());
                    props.put("id", Ids.next("dr"));
                    props.put("projectId", pid);
                    props.put("createdAt", now);
                    tx.run("CREATE (d:Directive) SET d = $props", Map.of("props", props)).consume();
                }
                counts.put("directives", dirs.size());
            }
            return null;
        });
        texts.copyProject(sourceId, pid, ids);
        audit.record(pid, null, Actor.system.name(), List.of(new ChangeExecutor.OpResult(0, "clone", "project", pid, null,
                Map.of("from", sourceId), Map.of("counts", counts))));
        LocalizationService.Report loc = null;
        if (translateTo != null && !translateTo.isBlank()) {
            Lang to = Lang.parse(translateTo, null);
            if (to == null) throw new DomainException(t("translateTo must be en or pt", "translateTo deve ser en ou pt"));
            loc = localization.localize(pid, to);
        }
        return new Result(pid, copy.name(), ids, counts, loc, notes);
    }
}
