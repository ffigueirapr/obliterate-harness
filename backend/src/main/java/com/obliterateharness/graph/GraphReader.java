package com.obliterateharness.graph;

import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.NotFoundException;
import com.obliterateharness.domain.Views;
import com.obliterateharness.domain.Views.Detail;
import org.neo4j.driver.Record;
import org.neo4j.driver.SimpleQueryRunner;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

/** Read queries over a project's graph. */
@Component
public class GraphReader {

    /** Node listing filter. Null/empty fields do not filter. */
    public record NodeFilter(List<String> types, List<String> bases, List<String> tags, List<String> spaces, String minCriticality) {
        public static NodeFilter none() {
            return new NodeFilter(null, null, null, null, null);
        }
    }

    private static final String EDGE_RETURN = "r, a.id AS from, a.name AS fromName, b.id AS to, b.name AS toName";
    private static final String FACT_RETURN = """
            OPTIONAL MATCH (f)-[:ABOUT]->(n:N)
            WITH f, collect({id:n.id, name:n.name}) AS about
            OPTIONAL MATCH (ru:N {id:f.revealAt})
            RETURN f, about, ru.name AS revealAtName
            """;

    private final JsonCodec json;

    public GraphReader(JsonCodec json) {
        this.json = json;
    }

    // ---------- nodes ----------

    public List<Views.Node> nodes(SimpleQueryRunner q, String projectId, Collection<String> ids, Detail detail) {
        if (ids.isEmpty()) return List.of();
        var rows = q.run("""
                MATCH (n:N {projectId:$p}) WHERE n.id IN $ids
                OPTIONAL MATCH (n)-[a:R {kind:'attr'}]->(t:N)
                WITH n, collect(CASE WHEN a IS NULL THEN null ELSE {k:a.attrKey, id:t.id} END) AS refs
                OPTIONAL MATCH (par:N)-[:R {kind:'structure'}]->(n)
                RETURN n, refs, par.id AS parent
                """, params("p", projectId, "ids", List.copyOf(ids))).list();
        Map<String, Views.Node> byId = new HashMap<>();
        for (Record r : rows) {
            Views.Node v = toNode(r.get("n"), r.get("refs"), str(r, "parent"), detail);
            byId.put(v.id(), v);
        }
        List<Views.Node> out = new ArrayList<>();
        for (String id : ids) if (byId.containsKey(id)) out.add(byId.get(id));
        return out;
    }

    public Views.Node node(SimpleQueryRunner q, String projectId, String id, Detail detail) {
        var list = nodes(q, projectId, List.of(id), detail);
        if (list.isEmpty()) throw new NotFoundException(t("Node '", "Nó '") + id + t("' does not exist in project ", "' não existe no projeto ") + projectId);
        return list.getFirst();
    }

    public List<Views.Node> list(SimpleQueryRunner q, String projectId, NodeFilter f, int limit, int offset, Detail detail) {
        var where = new StringBuilder("n.projectId = $p");
        Map<String, Object> p = params("p", projectId, "limit", limit, "offset", offset);
        appendFilter(where, p, f);
        var ids = q.run("MATCH (n:N) WHERE " + where + " RETURN n.id AS id ORDER BY coalesce(n.sortKey, ''), n.name SKIP $offset LIMIT $limit", p)
                .list(r -> r.get("id").asString());
        return nodes(q, projectId, ids, detail);
    }

    public long count(SimpleQueryRunner q, String projectId, NodeFilter f) {
        var where = new StringBuilder("n.projectId = $p");
        Map<String, Object> p = params("p", projectId);
        appendFilter(where, p, f);
        return q.run("MATCH (n:N) WHERE " + where + " RETURN count(n) AS c", p).single().get("c").asLong();
    }

    private static void appendFilter(StringBuilder where, Map<String, Object> p, NodeFilter f) {
        if (f == null) return;
        if (notEmpty(f.types())) {
            where.append(" AND (n.typeName IN $types OR n.typeId IN $types)");
            p.put("types", f.types());
        }
        if (notEmpty(f.bases())) {
            where.append(" AND n.base IN $bases");
            p.put("bases", f.bases());
        }
        if (notEmpty(f.tags())) {
            where.append(" AND any(t IN coalesce(n.tags, []) WHERE t IN $tags)");
            p.put("tags", f.tags());
        }
        if (notEmpty(f.spaces())) {
            where.append(" AND n.space IN $spaces");
            p.put("spaces", f.spaces());
        }
        if ("critical".equals(f.minCriticality())) {
            where.append(" AND n.criticality IN ['critical','foundational']");
        } else if ("foundational".equals(f.minCriticality())) {
            where.append(" AND n.criticality = 'foundational'");
        }
    }

    /** Full-text search (with prefix matching) combined with filters. */
    public List<Views.Node> search(SimpleQueryRunner q, String projectId, String text, NodeFilter f, int limit, Detail detail) {
        if (text == null || text.isBlank()) return list(q, projectId, f, limit, 0, detail);
        var where = new StringBuilder("n.projectId = $p");
        Map<String, Object> p = params("p", projectId, "q", luceneQuery(text), "limit", limit);
        appendFilter(where, p, f);
        var ids = q.run("CALL db.index.fulltext.queryNodes('node_text', $q) YIELD node AS n, score WHERE " + where
                        + " RETURN n.id AS id ORDER BY score DESC LIMIT $limit", p)
                .list(r -> r.get("id").asString());
        return nodes(q, projectId, ids, detail);
    }

    static String luceneQuery(String text) {
        var terms = new ArrayList<String>();
        for (String w : text.trim().split("\\s+")) {
            String esc = w.replaceAll("([+\\-!(){}\\[\\]^\"~*?:\\\\/&|])", "\\\\$1");
            if (!esc.isBlank()) terms.add("(" + esc + " OR " + esc + "* OR " + esc + "~)");
        }
        return String.join(" AND ", terms);
    }

    /** Units of a type in narrative order (optionally only those inside a scope unit). */
    public List<Views.Node> unitsInOrder(SimpleQueryRunner q, String projectId, String typeName, String scopeUnitId, Detail detail) {
        var ids = scopeUnitId == null
                ? q.run("MATCH (u:N:Unit {projectId:$p, typeName:$t}) RETURN u.id AS id ORDER BY u.sortKey", params("p", projectId, "t", typeName))
                .list(r -> r.get("id").asString())
                : q.run("MATCH (:N {id:$s})-[:R*1..10 {kind:'structure'}]->(u:N:Unit {typeName:$t}) RETURN DISTINCT u.id AS id, u.sortKey AS k ORDER BY k",
                params("s", scopeUnitId, "t", typeName)).list(r -> r.get("id").asString());
        return nodes(q, projectId, ids, detail);
    }

    /** Child units (structure) of a unit, in order. */
    public List<Views.Node> childUnits(SimpleQueryRunner q, String projectId, String unitId, Detail detail) {
        var ids = q.run("MATCH (:N {id:$id})-[:R {kind:'structure'}]->(c:N) RETURN c.id AS id ORDER BY c.sortKey", Map.of("id", unitId))
                .list(r -> r.get("id").asString());
        return nodes(q, projectId, ids, detail);
    }

    // ---------- edges ----------

    public List<Views.Edge> edgesOf(SimpleQueryRunner q, String projectId, Collection<String> nodeIds) {
        return q.run("MATCH (a:N)-[r:R {projectId:$p}]->(b:N) WHERE a.id IN $ids OR b.id IN $ids RETURN " + EDGE_RETURN,
                params("p", projectId, "ids", List.copyOf(nodeIds))).list(this::toEdge);
    }

    /** Edges whose two ends are in the set. */
    public List<Views.Edge> edgesBetween(SimpleQueryRunner q, String projectId, Collection<String> nodeIds) {
        return q.run("MATCH (a:N)-[r:R {projectId:$p}]->(b:N) WHERE a.id IN $ids AND b.id IN $ids RETURN " + EDGE_RETURN,
                params("p", projectId, "ids", List.copyOf(nodeIds))).list(this::toEdge);
    }

    public List<Views.Edge> edgesById(SimpleQueryRunner q, String projectId, Collection<String> edgeIds) {
        return q.run("MATCH (a:N)-[r:R {projectId:$p}]->(b:N) WHERE r.id IN $ids RETURN " + EDGE_RETURN,
                params("p", projectId, "ids", List.copyOf(edgeIds))).list(this::toEdge);
    }

    public Views.Edge edge(SimpleQueryRunner q, String projectId, String edgeId) {
        var rows = edgesById(q, projectId, List.of(edgeId));
        if (rows.isEmpty()) throw new NotFoundException(t("Edge '", "Aresta '") + edgeId + t("' does not exist in the project", "' não existe no projeto"));
        return rows.getFirst();
    }

    // ---------- navigation ----------

    public Views.Subgraph neighborhood(SimpleQueryRunner q, String projectId, String nodeId, int hops, List<String> relTypes, int limit, Detail detail) {
        node(q, projectId, nodeId, Detail.id);
        int h = clamp(hops, 1, 4);
        String relFilter = notEmpty(relTypes) ? " AND all(x IN rels WHERE x.name IN $rels OR x.relTypeId IN $rels)" : "";
        var ids = q.run("MATCH path = (s:N {id:$id})-[rels:R*1.." + h + "]-(m:N) WHERE m.projectId = $p" + relFilter
                        + " WITH m, min(length(path)) AS d RETURN m.id AS id ORDER BY d, m.name LIMIT $limit",
                params("id", nodeId, "p", projectId, "rels", relTypes, "limit", limit + 1)).list(r -> r.get("id").asString());
        boolean truncated = ids.size() > limit;
        var all = new LinkedHashSet<String>();
        all.add(nodeId);
        all.addAll(truncated ? ids.subList(0, limit) : ids);
        return new Views.Subgraph(nodes(q, projectId, all, detail), edgesBetween(q, projectId, all), truncated);
    }

    /** Depth used when asked for "everything connected, even indirectly". */
    public static final int ALL_LEVELS = 25;

    /**
     * Breadth-first search from a node, level by level, up to maxHops or the node limit.
     *
     * @param includeStructure also follow structure edges (unit inside unit). Off, an indirect focus on a character does not
     *                         "leak" into the whole work through chapter ⊂ work.
     */
    public Views.Focus focus(SimpleQueryRunner q, String projectId, String nodeId, int maxHops, List<String> relTypes,
                             boolean includeStructure, int limit, Detail detail) {
        var center = node(q, projectId, nodeId, Detail.id);
        int h = clamp(maxHops, 1, ALL_LEVELS);
        int lim = clamp(limit, 1, 5000);
        String relFilter = notEmpty(relTypes) ? " AND (r.name IN $rels OR r.relTypeId IN $rels)" : "";
        String structFilter = includeStructure ? "" : " AND r.kind <> 'structure'";
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put(nodeId, 0);
        List<String> frontier = List.of(nodeId);
        boolean truncated = false;
        int level = 0;
        while (!frontier.isEmpty() && level < h) {
            level++;
            var next = q.run("MATCH (a:N)-[r:R]-(b:N {projectId:$p}) WHERE a.id IN $frontier AND NOT b.id IN $seen" + relFilter + structFilter
                            + " RETURN DISTINCT b.id AS id, b.name AS name ORDER BY name LIMIT $room",
                    params("p", projectId, "frontier", frontier, "seen", List.copyOf(dist.keySet()), "rels", relTypes,
                            "room", lim - dist.size() + 2)).list(r -> r.get("id").asString());
            List<String> added = new ArrayList<>();
            for (String id : next) {
                if (dist.size() >= lim) {
                    truncated = true;
                    break;
                }
                dist.put(id, level);
                added.add(id);
            }
            if (added.isEmpty()) {
                level--;
                break;
            }
            frontier = added;
        }
        Map<Integer, Integer> byLevel = new java.util.TreeMap<>();
        dist.values().stream().filter(d -> d > 0).forEach(d -> byLevel.merge(d, 1, Integer::sum));
        var edges = edgesBetween(q, projectId, dist.keySet()).stream()
                .filter(e -> includeStructure || !"structure".equals(e.kind()))
                .filter(e -> !notEmpty(relTypes) || relTypes.contains(e.relType()))
                .toList();
        return new Views.Focus(new Views.Ref(center.id(), center.name()), nodes(q, projectId, dist.keySet(), detail), edges, dist,
                byLevel.isEmpty() ? 0 : java.util.Collections.max(byLevel.keySet()), byLevel, truncated);
    }

    /** Shortest path between two nodes ("how does A relate to B?"). */
    public Views.Subgraph path(SimpleQueryRunner q, String projectId, String fromId, String toId, int maxHops) {
        int h = clamp(maxHops, 1, 8);
        var rows = q.run("MATCH (a:N {id:$a, projectId:$p}), (b:N {id:$b, projectId:$p}) "
                        + "MATCH path = shortestPath((a)-[:R*1.." + h + "]-(b)) "
                        + "RETURN [n IN nodes(path) | n.id] AS ids, [r IN relationships(path) | r.id] AS rids",
                params("a", fromId, "b", toId, "p", projectId)).list();
        if (rows.isEmpty()) return new Views.Subgraph(List.of(), List.of(), false);
        List<String> ids = rows.getFirst().get("ids").asList(Value::asString);
        List<String> rids = rows.getFirst().get("rids").asList(Value::asString);
        var edges = edgesBetween(q, projectId, ids).stream().filter(e -> rids.contains(e.id())).toList();
        return new Views.Subgraph(nodes(q, projectId, ids, Detail.summary), edges, false);
    }

    /** For a set of visible nodes, finds pairs linked only indirectly (2..maxHops hops). */
    public List<Views.IndirectLink> indirectLinks(SimpleQueryRunner q, String projectId, List<String> ids, int maxHops, List<String> relTypes, int limit) {
        if (ids.size() < 2) return List.of();
        if (ids.size() > 500) {
            throw new DomainException(t("Indirect links accept at most 500 visible nodes; narrow the view with filters",
                    "Ligações indiretas aceitam no máximo 500 nós visíveis; filtre mais a visão"));
        }
        int h = clamp(maxHops, 2, 5);
        String relFilter = notEmpty(relTypes) ? " AND all(x IN relationships(path) WHERE x.name IN $rels OR x.relTypeId IN $rels)" : "";
        return q.run("MATCH (a:N {projectId:$p}) WHERE a.id IN $ids "
                        + "MATCH (b:N {projectId:$p}) WHERE b.id IN $ids AND a.id < b.id AND NOT (a)-[:R]-(b) "
                        + "MATCH path = shortestPath((a)-[:R*1.." + h + "]-(b)) WHERE true" + relFilter
                        + " RETURN a.id AS from, b.id AS to, length(path) AS hops, [n IN nodes(path) | n.id] AS pathIds, "
                        + "[r IN relationships(path) | r.name] AS relNames ORDER BY hops LIMIT $limit",
                params("p", projectId, "ids", ids, "rels", relTypes, "limit", limit))
                .list(r -> new Views.IndirectLink(r.get("from").asString(), r.get("to").asString(), r.get("hops").asInt(),
                        r.get("pathIds").asList(Value::asString), r.get("relNames").asList(v -> v.isNull() ? "" : v.asString())));
    }

    /** State of an entity's relations at a point of the narrative (fromUnit/toUnit anchors). */
    public Views.StateAt stateAt(SimpleQueryRunner q, String projectId, String entityId, String unitId) {
        var entity = node(q, projectId, entityId, Detail.id);
        var unit = node(q, projectId, unitId, Detail.full);
        if (unit.sortKey() == null) {
            throw new DomainException("'" + unit.name() + t("' is not a narrative unit (base Unit)", "' não é uma unidade narrativa (tipo-base Unit)"));
        }
        var rows = q.run("""
                MATCH (e:N {id:$id})-[r:R {projectId:$p}]-(o:N) WHERE r.kind = 'rel'
                OPTIONAL MATCH (fu:N {id:r.fromUnit})
                OPTIONAL MATCH (tu:N {id:r.toUnit})
                WITH r, startNode(r) AS a, endNode(r) AS b, fu.sortKey AS fk, tu.sortKey AS tk
                RETURN r, a.id AS from, a.name AS fromName, b.id AS to, b.name AS toName,
                  CASE WHEN fk IS NOT NULL AND fk > $key AND NOT $key STARTS WITH fk THEN 'notYet'
                       WHEN tk IS NOT NULL AND tk < $key AND NOT $key STARTS WITH tk THEN 'ended'
                       ELSE 'active' END AS status
                """, params("id", entityId, "p", projectId, "key", unit.sortKey())).list();
        List<Views.Edge> active = new ArrayList<>(), ended = new ArrayList<>(), notYet = new ArrayList<>();
        for (Record r : rows) {
            var e = toEdge(r);
            switch (r.get("status").asString()) {
                case "ended" -> ended.add(e);
                case "notYet" -> notYet.add(e);
                default -> active.add(e);
            }
        }
        return new Views.StateAt(new Views.Ref(entity.id(), entity.name()), new Views.Ref(unit.id(), unit.name()), active, ended, notYet);
    }

    /** Tree of narrative units (work > arc > chapter > scene...). */
    public List<Views.OutlineItem> outline(SimpleQueryRunner q, String projectId, int maxDepth) {
        var rows = q.run("""
                MATCH (n:N:Unit {projectId:$p})
                OPTIONAL MATCH (par:N)-[:R {kind:'structure'}]->(n)
                RETURN n.id AS id, n.typeName AS type, n.name AS name, n.summary AS summary, n.attrs AS attrs,
                       n.wordCount AS words, par.id AS parent ORDER BY n.sortKey
                """, Map.of("p", projectId)).list();
        Map<String, Record> byId = new LinkedHashMap<>();
        for (Record r : rows) byId.put(r.get("id").asString(), r);
        List<String> roots = new ArrayList<>();
        for (Record r : rows) if (str(r, "parent") == null) roots.add(r.get("id").asString());
        return roots.stream().map(id -> buildOutline(id, byId, rows, 1, maxDepth)).toList();
    }

    private Views.OutlineItem buildOutline(String id, Map<String, Record> byId, List<Record> rows, int depth, int maxDepth) {
        Record r = byId.get(id);
        List<Views.OutlineItem> kids = depth >= maxDepth ? List.of() : rows.stream()
                .filter(x -> id.equals(str(x, "parent")))
                .map(x -> buildOutline(x.get("id").asString(), byId, rows, depth + 1, maxDepth)).toList();
        Object status = json.readMap(str(r, "attrs")).get("status");
        return new Views.OutlineItem(id, str(r, "type"), str(r, "name"), str(r, "summary"),
                status == null ? null : status.toString(), r.get("words").isNull() ? null : r.get("words").asLong(), kids);
    }

    // ---------- facts ----------

    public List<Views.Fact> facts(SimpleQueryRunner q, String projectId, List<String> aboutIds, String minCriticality, String text, int limit) {
        Map<String, Object> p = params("p", projectId, "ids", aboutIds, "limit", limit);
        var sb = new StringBuilder();
        if (text != null && !text.isBlank()) {
            sb.append("CALL db.index.fulltext.queryNodes('fact_text', $q) YIELD node AS f, score WHERE f.projectId = $p ");
            p.put("q", luceneQuery(text));
        } else {
            sb.append("MATCH (f:Fact {projectId:$p}) WHERE true ");
        }
        if ("foundational".equals(minCriticality)) sb.append("AND f.criticality = 'foundational' ");
        else if ("critical".equals(minCriticality)) sb.append("AND f.criticality IN ['critical','foundational'] ");
        if (notEmpty(aboutIds)) sb.append("AND EXISTS { MATCH (f)-[:ABOUT]->(x:N) WHERE x.id IN $ids } ");
        sb.append("WITH f ORDER BY CASE f.criticality WHEN 'foundational' THEN 0 WHEN 'critical' THEN 1 ELSE 2 END, f.text LIMIT $limit\n");
        sb.append(FACT_RETURN);
        return q.run(sb.toString(), p).list(this::toFact);
    }

    public Views.Fact fact(SimpleQueryRunner q, String projectId, String factId) {
        var rows = q.run("MATCH (f:Fact {id:$id, projectId:$p})\n" + FACT_RETURN, params("id", factId, "p", projectId)).list(this::toFact);
        if (rows.isEmpty()) throw new NotFoundException(t("Fact '", "Fato '") + factId + t("' does not exist in the project", "' não existe no projeto"));
        return rows.getFirst();
    }

    /** Facts with a reveal point (revealAt), used to list secrets. */
    public List<Views.Fact> factsWithReveal(SimpleQueryRunner q, String projectId) {
        return q.run("MATCH (f:Fact {projectId:$p}) WHERE f.revealAt IS NOT NULL\n" + FACT_RETURN, Map.of("p", projectId)).list(this::toFact);
    }

    // ---------- directives ----------

    public List<Views.Directive> directives(SimpleQueryRunner q, String projectId) {
        return q.run("MATCH (d:Directive {projectId:$p}) RETURN d ORDER BY d.priority DESC, d.createdAt", Map.of("p", projectId))
                .list(r -> toDirective(r.get("d")));
    }

    public Views.Directive directive(SimpleQueryRunner q, String projectId, String id) {
        if (id == null) return null;
        var rows = q.run("MATCH (d:Directive {id:$id, projectId:$p}) RETURN d", params("id", id, "p", projectId)).list(r -> toDirective(r.get("d")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Views.Directive toDirective(Value d) {
        return new Views.Directive(str(d, "id"), str(d, "text"), d.get("when").asList(Value::asString, List.of()),
                d.get("bases").asList(Value::asString, List.of()), d.get("types").asList(Value::asString, List.of()),
                d.get("priority").asInt(0), d.get("enabled").asBoolean(true), "project");
    }

    // ---------- mapping ----------

    Views.Node toNode(Value n, Value refsValue, String parent, Detail detail) {
        String id = str(n, "id");
        String type = str(n, "typeName");
        String name = str(n, "name");
        Long words = n.get("wordCount").isNull() ? null : n.get("wordCount").asLong();
        if (detail == Detail.id) {
            return new Views.Node(id, type, null, null, name, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
        List<String> tags = n.get("tags").isNull() ? List.of() : n.get("tags").asList(Value::asString);
        String crit = str(n, "criticality");
        if (detail == Detail.summary) {
            return new Views.Node(id, type, str(n, "base"), str(n, "space"), name, str(n, "summary"), null, tags,
                    "normal".equals(crit) ? null : crit, null, null, null, null, null, null, n.get("version").asLong(0), null, null, null, words);
        }
        Map<String, List<String>> refs = new LinkedHashMap<>();
        if (refsValue != null && !refsValue.isNull()) {
            for (Object o : refsValue.asList()) {
                if (o instanceof Map<?, ?> m) refs.computeIfAbsent((String) m.get("k"), k -> new ArrayList<>()).add((String) m.get("id"));
            }
        }
        Map<String, String> attrMeta = new LinkedHashMap<>();
        json.readMap(str(n, "attrMeta")).forEach((k, v) -> attrMeta.put(k, Objects.toString(v)));
        return new Views.Node(id, type, str(n, "base"), str(n, "space"), name, str(n, "summary"), str(n, "description"), tags, crit,
                json.readMap(str(n, "attrs")), attrMeta, refs, parent,
                n.get("order").isNull() ? null : n.get("order").asDouble(), str(n, "sortKey"),
                n.get("version").asLong(0), str(n, "createdBy"), str(n, "updatedBy"), str(n, "updatedAt"), words);
    }

    Views.Edge toEdge(Record row) {
        Value r = row.get("r");
        List<String> tags = r.get("tags").isNull() ? List.of() : r.get("tags").asList(Value::asString);
        String crit = str(r, "criticality");
        return new Views.Edge(str(r, "id"), str(r, "name"), str(r, "base"), str(r, "kind"),
                row.get("from").asString(), str(row, "fromName"), row.get("to").asString(), str(row, "toName"),
                str(r, "description"), "normal".equals(crit) ? null : crit, tags, str(r, "fromUnit"), str(r, "toUnit"),
                r.get("version").asLong(0));
    }

    Views.Fact toFact(Record row) {
        Value f = row.get("f");
        List<Views.Ref> about = new ArrayList<>();
        for (Object o : row.get("about").asList()) {
            if (o instanceof Map<?, ?> m && m.get("id") != null) about.add(new Views.Ref((String) m.get("id"), (String) m.get("name")));
        }
        String origin = str(f, "originKind");
        if (!"manual".equals(origin)) origin = origin + ":" + str(f, "originId") + (str(f, "originKey") != null ? "." + str(f, "originKey") : "");
        String reveal = str(f, "revealAt");
        return new Views.Fact(str(f, "id"), str(f, "text"), str(f, "rigidity"), str(f, "criticality"), origin, about, f.get("version").asLong(0),
                reveal == null ? null : new Views.Ref(reveal, str(row, "revealAtName")));
    }

    static String str(Value v, String key) {
        return SchemaStore.str(v, key);
    }

    static String str(Record r, String key) {
        Value v = r.get(key);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static boolean notEmpty(Collection<?> c) {
        return c != null && !c.isEmpty();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
