package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Manuscript;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.Views;
import com.obliterateharness.domain.Views.Detail;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.GraphReader;
import com.obliterateharness.graph.JsonCodec;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.harness.SystemNames.Field;
import com.obliterateharness.service.ProjectService;
import org.neo4j.driver.SimpleQueryRunner;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

/**
 * High-level narrative reads: open hooks/clues and the context package for writing a unit.
 * The goal is to hand the LLM only what it needs, within a token budget.
 */
@Service
public class NarrativeService {

    /** @param clue true when the node is not a hook (Device) but was planted as a clue */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Thread(String id, String name, String type, String kind, String status, String summary,
                         List<Views.Ref> plantedIn, List<Views.Ref> paidIn, List<Views.Ref> about, Integer unitsSincePlanted,
                         boolean clue, String warning) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record UnitCard(String id, String type, String name, String summary) {}

    /** A child unit (e.g. a scene of the chapter) with its short attributes (goal, conflict, outcome...). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record PartCard(String id, String type, String name, String summary, Map<String, Object> attributes, Long wordCount) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record EntityInPlay(String id, String type, String base, String name, String summary, String criticality, List<String> state) {}

    /** The end of the previous unit's manuscript, for continuity of voice. */
    public record Tail(String unitId, String unitName, List<String> paragraphs) {}

    /** Prose sample to keep the project's voice. */
    public record StyleSample(String source, String text) {}

    /**
     * Something the reader must not learn yet in this unit.
     *
     * @param kind     fact (fact with a later revealAt), character_secret (a character's secret attribute), tagged (node tagged secret)
     * @param revealAt unit where it may be revealed, when known
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Secret(String kind, String text, List<Views.Ref> about, Views.Ref revealAt) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record WritingContext(
            Views.Node unit,
            List<UnitCard> path,
            List<UnitCard> previous,
            Tail previousTail,
            UnitCard next,
            List<PartCard> parts,
            List<TemplateService.BeatStatus> beatsHere,
            List<TemplateService.BeatStatus> beatsExpectedHere,
            List<EntityInPlay> inPlay,
            List<Views.Fact> facts,
            List<Secret> secrets,
            List<Secret> revealedHere,
            List<Thread> openThreads,
            StyleSample style,
            List<String> guidance,
            int estimatedTokens,
            List<String> trimmed
    ) {}

    private final Graph graph;
    private final GraphReader reader;
    private final TemplateService templates;
    private final GuidanceService guidance;
    private final JsonCodec json;
    private final SystemNames names;
    private final SchemaStore schema;
    private final ProjectService projects;

    public NarrativeService(Graph graph, GraphReader reader, TemplateService templates, GuidanceService guidance, JsonCodec json,
                            SystemNames names, SchemaStore schema, ProjectService projects) {
        this.graph = graph;
        this.reader = reader;
        this.templates = templates;
        this.guidance = guidance;
        this.json = json;
        this.names = names;
        this.schema = schema;
        this.projects = projects;
    }

    // ------------------------------------------------------------------ hooks and clues

    public List<Thread> openThreads(String projectId, boolean includeClosed) {
        return graph.read(tx -> threads(tx, projectId, includeClosed));
    }

    List<Thread> threads(SimpleQueryRunner q, String projectId, boolean includeClosed) {
        String plants = names.relTypeId(q, projectId, Reserved.PLANTS);
        String pays = names.relTypeId(q, projectId, Reserved.PAYS);
        String about = names.relTypeId(q, projectId, Reserved.ABOUT);
        var rows = q.run("""
                MATCH (d:N {projectId:$p}) WHERE d:Device OR EXISTS { MATCH (:N)-[:R {relTypeId:$plants}]->(d) }
                OPTIONAL MATCH (u1:N)-[:R {relTypeId:$plants}]->(d)
                WITH d, collect(DISTINCT {id:u1.id, name:u1.name, key:u1.sortKey, type:u1.typeName}) AS planted
                OPTIONAL MATCH (u2:N)-[:R {relTypeId:$pays}]->(d)
                WITH d, planted, collect(DISTINCT {id:u2.id, name:u2.name}) AS paid
                OPTIONAL MATCH (d)-[:R {relTypeId:$about}]->(x:N)
                RETURN d, planted, paid, collect(DISTINCT {id:x.id, name:x.name}) AS about ORDER BY d.name
                """, params("p", projectId, "plants", plants, "pays", pays, "about", about)).list();
        List<Thread> out = new ArrayList<>();
        for (var r : rows) {
            Value d = r.get("d");
            boolean clue = !"Device".equals(d.get("base").asString(null));
            Map<String, Object> a = json.readMap(d.get("attrs").asString(null));
            Object st = clue ? null : Field.THREAD_STATUS.read(a);
            String status = st == null ? t("open", "aberto") : st.toString();
            List<Views.Ref> planted = refs(r.get("planted"));
            List<Views.Ref> paid = refs(r.get("paid"));
            boolean closed = Reserved.CLOSED_THREAD.contains(status.toLowerCase()) || (clue && !paid.isEmpty());
            String warning = null;
            if (!clue && !paid.isEmpty() && !closed) {
                warning = t("A unit pays it off but its status is still '", "Tem unidade que paga o gancho, mas o status ainda é '") + status + "'";
            }
            if (!clue && closed && Set.of("pago", "paid").contains(status.toLowerCase()) && paid.isEmpty()) {
                warning = t("Marked as paid, but no unit pays this hook off", "Marcado como pago, mas nenhuma unidade paga este gancho");
            }
            if (closed && !includeClosed && warning == null) continue;
            if (!paid.isEmpty() && !includeClosed && warning == null) continue;
            Integer since = null;
            String key = null, type = null;
            for (Object o : r.get("planted").asList()) {
                if (o instanceof Map<?, ?> m && m.get("key") != null) {
                    key = (String) m.get("key");
                    type = (String) m.get("type");
                }
            }
            if (key != null) {
                since = q.run("MATCH (u:N:Unit {projectId:$p, typeName:$t}) WHERE u.sortKey > $k RETURN count(u) AS c",
                        params("p", projectId, "t", type, "k", key)).single().get("c").asInt();
            }
            Object kind = clue ? null : Field.THREAD_KIND.read(a);
            out.add(new Thread(d.get("id").asString(), d.get("name").asString(), d.get("typeName").asString(),
                    kind == null ? null : kind.toString(), clue ? (paid.isEmpty() ? t("planted clue", "pista plantada") : t("paid clue", "pista paga")) : status,
                    d.get("summary").asString(null), planted, paid, refs(r.get("about")), since, clue, warning));
        }
        out.sort((x, y) -> Integer.compare(y.unitsSincePlanted() == null ? -1 : y.unitsSincePlanted(),
                x.unitsSincePlanted() == null ? -1 : x.unitsSincePlanted()));
        return out;
    }

    private static List<Views.Ref> refs(Value v) {
        List<Views.Ref> out = new ArrayList<>();
        for (Object o : v.asList()) {
            if (o instanceof Map<?, ?> m && m.get("id") != null) out.add(new Views.Ref((String) m.get("id"), (String) m.get("name")));
        }
        return out;
    }

    // ------------------------------------------------------------------ writing context

    /**
     * @param tailParagraphs how many closing paragraphs of the previous unit to include (default 3, 0 = none)
     * @param focus          optional emphasis; "secrets" puts the do-not-reveal list first in the guidance and never trims it
     */
    public WritingContext writingContext(String projectId, String unitId, Integer maxTokens, Integer tailParagraphs, List<String> focus) {
        int budget = maxTokens == null ? 6000 : Math.max(800, Math.min(maxTokens, 40000));
        int tail = tailParagraphs == null ? 3 : Math.max(0, Math.min(tailParagraphs, 12));
        var project = projects.get(projectId);
        var ctx = graph.read(tx -> build(tx, projectId, unitId, tail, project));
        Set<String> bases = new LinkedHashSet<>(), types = new LinkedHashSet<>();
        ctx.inPlay().forEach(e -> {
            if (e.base() != null) bases.add(e.base());
            types.add(e.type());
        });
        bases.add("Unit");
        types.add(ctx.unit().type());
        List<String> g = new ArrayList<>();
        boolean focusSecrets = focus != null && focus.stream().anyMatch(f -> f.equalsIgnoreCase("secrets"));
        if (!ctx.secrets().isEmpty()) {
            String line = ctx.secrets().size() + t(" secret(s) must NOT be revealed in this unit (see secrets).",
                    " segredo(s) NÃO podem ser revelados nesta unidade (veja secrets).");
            if (focusSecrets) g.add(0, line); else g.add(line);
        }
        if (!ctx.revealedHere().isEmpty()) {
            g.add(ctx.revealedHere().size() + t(" fact(s) are meant to be revealed in this unit (see revealedHere).",
                    " fato(s) devem ser revelados nesta unidade (veja revealedHere)."));
        }
        g.addAll(guidance.forEvent(projectId, "context", bases, types));
        long laws = ctx.facts().stream().filter(f -> "law".equals(f.rigidity())).count();
        if (laws > 0) {
            g.add(focusSecrets ? Math.min(1, g.size()) : 0, laws + t(" fact(s) with rigidity 'law' in play: they cannot be broken in this unit.",
                    " fato(s) com rigidez 'law' em jogo: não podem ser violados nesta unidade."));
        }
        if (!ctx.beatsExpectedHere().isEmpty()) {
            g.add(t("Beat expected around here: ", "Beat esperado por aqui: ") + ctx.beatsExpectedHere().stream().map(TemplateService.BeatStatus::name).toList()
                    + t(". If this unit fulfills it, link them with the maps-to-beat relation.", ". Se esta unidade cumpre o beat, ligue-a com a relação de mapear beat."));
        }
        long stale = ctx.openThreads().stream().filter(x -> x.unitsSincePlanted() != null && x.unitsSincePlanted() >= 5).count();
        if (stale > 0) {
            g.add(stale + t(" hook(s) open for 5+ units: consider paying them off or reminding the reader.",
                    " gancho(s) aberto(s) há 5+ unidades: considere pagar ou lembrar o leitor."));
        }
        var full = new WritingContext(ctx.unit(), ctx.path(), ctx.previous(), ctx.previousTail(), ctx.next(), ctx.parts(), ctx.beatsHere(),
                ctx.beatsExpectedHere(), ctx.inPlay(), ctx.facts(), ctx.secrets(), ctx.revealedHere(), ctx.openThreads(), ctx.style(), g, 0, List.of());
        return trim(full, budget);
    }

    private WritingContext build(SimpleQueryRunner q, String projectId, String unitId, int tailParagraphs, Views.Project project) {
        Views.Node unit = reader.node(q, projectId, unitId, Detail.full);
        if (unit.sortKey() == null) throw new DomainException("'" + unit.name() + t("' is not a narrative unit (base Unit)", "' não é uma unidade narrativa (base Unit)"));
        var cache = schema.cache(q, projectId);
        NodeTypeDef unitType = cache.requireNodeType(unit.type());

        List<UnitCard> path = q.run("""
                MATCH p = (root:N)-[:R*1..10 {kind:'structure'}]->(u:N {id:$id})
                WHERE NOT ()-[:R {kind:'structure'}]->(root)
                UNWIND nodes(p)[0..-1] AS a
                RETURN a.id AS id, a.typeName AS type, a.name AS name, a.summary AS summary
                """, Map.of("id", unitId)).list(r -> card(r.get("id"), r.get("type"), r.get("name"), r.get("summary")));

        List<UnitCard> previous = new ArrayList<>(q.run("""
                MATCH (u:N:Unit {projectId:$p, typeName:$type}) WHERE u.sortKey < $key
                RETURN u.id AS id, u.typeName AS type, u.name AS name, u.summary AS summary ORDER BY u.sortKey DESC LIMIT 3
                """, params("p", projectId, "type", unit.type(), "key", unit.sortKey()))
                .list(r -> card(r.get("id"), r.get("type"), r.get("name"), r.get("summary"))));
        java.util.Collections.reverse(previous);
        var nextRows = q.run("""
                MATCH (u:N:Unit {projectId:$p, typeName:$type}) WHERE u.sortKey > $key
                RETURN u.id AS id, u.typeName AS type, u.name AS name, u.summary AS summary ORDER BY u.sortKey LIMIT 1
                """, params("p", projectId, "type", unit.type(), "key", unit.sortKey()))
                .list(r -> card(r.get("id"), r.get("type"), r.get("name"), r.get("summary")));

        // closing paragraphs of the previous unit, for continuity of voice
        Tail tail = null;
        if (tailParagraphs > 0 && !previous.isEmpty()) {
            var prev = reader.node(q, projectId, previous.getLast().id(), Detail.full);
            String text = Manuscript.textAttr(unitType).map(a -> prev.attributes() == null ? null : prev.attributes().get(a.key()))
                    .map(Object::toString).orElse(null);
            if (text != null && !text.isBlank()) tail = new Tail(prev.id(), prev.name(), lastParagraphs(text, tailParagraphs));
        }

        // parts of the unit (e.g. the chapter's scenes) with their short attributes
        List<PartCard> parts = new ArrayList<>();
        for (var child : reader.childUnits(q, projectId, unitId, Detail.full)) {
            NodeTypeDef ct = cache.nodeType(child.type()).orElse(null);
            String manuscriptKey = ct == null ? null : Manuscript.textAttr(ct).map(a -> a.key()).orElse(null);
            Map<String, Object> shortAttrs = new LinkedHashMap<>();
            if (child.attributes() != null) {
                child.attributes().forEach((k, v) -> {
                    if (k.equals(manuscriptKey)) return;
                    String s = String.valueOf(v);
                    shortAttrs.put(k, s.length() > 300 ? s.substring(0, 299) + "…" : v);
                });
            }
            parts.add(new PartCard(child.id(), child.type(), child.name(), child.summary(), shortAttrs, child.wordCount()));
        }

        // beats: mapped here and expected at this unit's position
        List<TemplateService.BeatStatus> here = new ArrayList<>(), expected = new ArrayList<>();
        String tplType = names.nodeTypeId(q, projectId, Reserved.TEMPLATE);
        var tpls = q.run("MATCH (t:N {projectId:$p, typeId:$tpl}) RETURN t.id AS id, t.attrs AS attrs", params("p", projectId, "tpl", tplType))
                .list(r -> Map.entry(r.get("id").asString(), json.readMap(r.get("attrs").asString(null))));
        for (var tp : tpls) {
            if (!unit.type().equals(Field.UNIT_TYPE.read(tp.getValue()))) continue;
            var cov = templates.coverage(q, projectId, tp.getKey());
            boolean inScope = cov.unitsWithoutBeat().stream().anyMatch(r -> r.id().equals(unitId))
                    || cov.beats().stream().flatMap(b -> b.units().stream()).anyMatch(u -> u.id().equals(unitId));
            if (!inScope) continue;
            double myPos = cov.beats().stream().flatMap(b -> b.units().stream()).filter(u -> u.id().equals(unitId))
                    .mapToDouble(TemplateService.UnitPos::position).findFirst().orElse(position(q, projectId, unit, cov.unitsPlanned()));
            double window = Math.max(TemplateService.TOLERANCE / 2, cov.unitsPlanned() <= 1 ? 1 : 1.0 / (cov.unitsPlanned() - 1));
            for (var b : cov.beats()) {
                if (b.units().stream().anyMatch(u -> u.id().equals(unitId))) here.add(b);
                else if (b.units().isEmpty() && Math.abs(b.expected() - myPos) <= window) expected.add(b);
            }
        }

        // entities in play: linked to the unit (not structure), except template machinery and other units
        String beatType = names.nodeTypeId(q, projectId, Reserved.BEAT);
        var inPlayIds = q.run("""
                MATCH (u:N {id:$id})-[r:R]-(x:N) WHERE r.kind <> 'structure' AND NOT x.typeId IN [$tpl, $beat] AND NOT x:Unit
                RETURN DISTINCT x.id AS id LIMIT 60
                """, params("id", unitId, "tpl", tplType, "beat", beatType)).list(r -> r.get("id").asString());
        Map<String, String> unitNames = new LinkedHashMap<>();
        q.run("MATCH (u:N:Unit {projectId:$p}) RETURN u.id AS id, u.name AS name, u.sortKey AS k", Map.of("p", projectId))
                .forEachRemaining(r -> unitNames.put(r.get("id").asString(), r.get("name").asString()));
        List<EntityInPlay> inPlay = new ArrayList<>();
        var inPlayNodes = reader.nodes(q, projectId, inPlayIds, Detail.full);
        for (var n : inPlayNodes) {
            var st = reader.stateAt(q, projectId, n.id(), unitId);
            List<String> state = new ArrayList<>();
            for (var e : st.active()) {
                if (e.to().equals(unitId) || e.from().equals(unitId)) continue;
                boolean out = e.from().equals(n.id());
                state.add((out ? e.relType() + " → " + e.toName() : e.relType() + " ← " + e.fromName()) + anchor(e, unitNames)
                        + (e.description() != null ? " (" + e.description() + ")" : ""));
            }
            for (var e : st.ended()) state.add(t("[ended] ", "[encerrado] ") + e.fromName() + " " + e.relType() + " " + e.toName() + anchor(e, unitNames));
            for (var e : st.notYet()) state.add(t("[not yet] ", "[ainda não] ") + e.fromName() + " " + e.relType() + " " + e.toName() + anchor(e, unitNames));
            inPlay.add(new EntityInPlay(n.id(), n.type(), n.base(), n.name(), n.summary(), n.criticality(), state));
        }

        List<String> aboutIds = new ArrayList<>(inPlayIds);
        aboutIds.add(unitId);
        Map<String, Views.Fact> facts = new LinkedHashMap<>();
        reader.facts(q, projectId, aboutIds, "critical", null, 80).forEach(f -> facts.put(f.id(), f));
        reader.facts(q, projectId, null, "foundational", null, 30).forEach(f -> facts.putIfAbsent(f.id(), f));

        // secrets: facts revealed later, character secrets, nodes tagged secret
        Map<String, String> sortKeys = new LinkedHashMap<>();
        q.run("MATCH (u:N:Unit {projectId:$p}) RETURN u.id AS id, u.sortKey AS k", Map.of("p", projectId))
                .forEachRemaining(r -> sortKeys.put(r.get("id").asString(), r.get("k").asString(null)));
        List<Secret> secrets = new ArrayList<>(), revealedHere = new ArrayList<>();
        for (var f : reader.factsWithReveal(q, projectId)) {
            String rk = sortKeys.get(f.revealAt().id());
            if (rk == null) continue;
            if (f.revealAt().id().equals(unitId)) revealedHere.add(new Secret("fact", f.text(), f.about(), f.revealAt()));
            else if (rk.compareTo(unit.sortKey()) > 0 && !unit.sortKey().startsWith(rk)) secrets.add(new Secret("fact", f.text(), f.about(), f.revealAt()));
            facts.remove(f.id());
        }
        for (var n : inPlayNodes) {
            NodeTypeDef nt = cache.nodeType(n.type()).orElse(null);
            var secretAttr = nt == null ? null : nt.attrWithRole("secret").or(() -> nt.attr("secret").or(() -> nt.attr("segredo"))).orElse(null);
            if (secretAttr != null && n.attributes() != null && n.attributes().get(secretAttr.key()) instanceof String s && !s.isBlank()) {
                secrets.add(new Secret("character_secret", n.name() + ": " + s, List.of(new Views.Ref(n.id(), n.name())), null));
            }
            if (n.tags() != null && (n.tags().contains("secret") || n.tags().contains("segredo"))) {
                secrets.add(new Secret("tagged", n.name() + (n.summary() != null ? ": " + n.summary() : ""), List.of(new Views.Ref(n.id(), n.name())), null));
            }
        }

        StyleSample style = styleSample(q, project, unit, unitType);
        List<Thread> threads = threads(q, projectId, false);
        return new WritingContext(unit, path, previous, tail, nextRows.isEmpty() ? null : nextRows.getFirst(), parts, here, expected, inPlay,
                new ArrayList<>(facts.values()), secrets, revealedHere, threads, style, List.of(), 0, List.of());
    }

    /** The project's style sample, or an excerpt of the reference project's manuscript at a similar position. */
    private StyleSample styleSample(SimpleQueryRunner q, Views.Project project, Views.Node unit, NodeTypeDef unitType) {
        if (project.styleSample() != null && !project.styleSample().isBlank()) {
            return new StyleSample(t("project style sample", "amostra de estilo do projeto"), cut(project.styleSample(), 2000));
        }
        if (project.styleReference() == null) return null;
        String ref = project.styleReference();
        var refUnits = q.run("""
                MATCH (u:N:Unit {projectId:$p}) WHERE u.wordCount > 0 RETURN u.id AS id ORDER BY u.sortKey
                """, Map.of("p", ref)).list(r -> r.get("id").asString());
        if (refUnits.isEmpty()) return null;
        long before = q.run("MATCH (u:N:Unit {projectId:$p, typeName:$t}) WHERE u.sortKey < $k RETURN count(u) AS c",
                params("p", project.id(), "t", unit.type(), "k", unit.sortKey())).single().get("c").asLong();
        long total = q.run("MATCH (u:N:Unit {projectId:$p, typeName:$t}) RETURN count(u) AS c", params("p", project.id(), "t", unit.type()))
                .single().get("c").asLong();
        double rel = total <= 1 ? 0 : (double) before / (total - 1);
        String pick = refUnits.get((int) Math.round(rel * (refUnits.size() - 1)));
        var n = reader.node(q, ref, pick, Detail.full);
        var refType = schema.cache(q, ref).nodeType(n.type()).orElse(null);
        if (refType == null || n.attributes() == null) return null;
        return Manuscript.textAttr(refType).map(a -> n.attributes().get(a.key())).map(Object::toString)
                .map(text -> new StyleSample(t("excerpt of ", "trecho de ") + n.name() + " (" + ref + ")", cut(text, 1500))).orElse(null);
    }

    static List<String> lastParagraphs(String text, int n) {
        String[] paras = text.trim().split("\\n\\s*\\n");
        if (paras.length == 1) paras = text.trim().split("\\n");
        List<String> all = new ArrayList<>();
        for (String p : paras) if (!p.isBlank()) all.add(p.trim());
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private double position(SimpleQueryRunner q, String projectId, Views.Node unit, int planned) {
        long before = q.run("MATCH (u:N:Unit {projectId:$p, typeName:$t}) WHERE u.sortKey < $k RETURN count(u) AS c",
                params("p", projectId, "t", unit.type(), "k", unit.sortKey())).single().get("c").asLong();
        return planned <= 1 ? 0 : (double) before / (planned - 1);
    }

    private static String anchor(Views.Edge e, Map<String, String> units) {
        if (e.fromUnit() == null && e.toUnit() == null) return "";
        String from = e.fromUnit() == null ? "" : t("from ", "de ") + units.getOrDefault(e.fromUnit(), e.fromUnit());
        String to = e.toUnit() == null ? "" : (from.isEmpty() ? "" : " ") + t("until ", "até ") + units.getOrDefault(e.toUnit(), e.toUnit());
        return " [" + from + to + "]";
    }

    private static UnitCard card(Value id, Value type, Value name, Value summary) {
        return new UnitCard(id.asString(), type.asString(null), name.asString(), summary.asString(null));
    }

    /** Cuts the less essential parts until it fits the budget (estimate: 4 characters per token). Secrets are never cut. */
    private WritingContext trim(WritingContext c, int budget) {
        List<String> trimmed = new ArrayList<>();
        WritingContext cur = c;
        int tokens = estimate(cur);
        if (tokens > budget && cur.unit().description() != null) {
            var u = cur.unit();
            cur = withUnit(cur, new Views.Node(u.id(), u.type(), u.base(), u.space(), u.name(), u.summary(), null, u.tags(), u.criticality(),
                    u.attributes(), u.attributeCriticality(), u.refs(), u.parent(), u.order(), u.sortKey(), u.version(), u.createdBy(),
                    u.updatedBy(), u.updatedAt(), u.wordCount()));
            trimmed.add(t("the unit's long description (use node_get)", "descrição longa da unidade (use node_get)"));
            tokens = estimate(cur);
        }
        if (tokens > budget && cur.unit().attributes() != null) {
            // the unit's own manuscript is usually what is being written: drop it from the package
            var u = cur.unit();
            Map<String, Object> attrs = new LinkedHashMap<>();
            u.attributes().forEach((k, v) -> attrs.put(k, v instanceof String s && s.length() > 400 ? s.substring(0, 399) + "…" : v));
            cur = withUnit(cur, new Views.Node(u.id(), u.type(), u.base(), u.space(), u.name(), u.summary(), u.description(), u.tags(), u.criticality(),
                    attrs, u.attributeCriticality(), u.refs(), u.parent(), u.order(), u.sortKey(), u.version(), u.createdBy(), u.updatedBy(),
                    u.updatedAt(), u.wordCount()));
            trimmed.add(t("long texts of the unit's attributes, cut to 400 characters (use unit_text_get)",
                    "textos longos dos atributos da unidade, cortados em 400 caracteres (use unit_text_get)"));
            tokens = estimate(cur);
        }
        if (tokens > budget && cur.style() != null && cur.style().text().length() > 600) {
            cur = with(cur, b -> b.style = new StyleSample(c.style().source(), cut(c.style().text(), 600)));
            trimmed.add(t("style sample, cut to 600 characters", "amostra de estilo, cortada em 600 caracteres"));
            tokens = estimate(cur);
        }
        if (tokens > budget && cur.previousTail() != null && cur.previousTail().paragraphs().size() > 1) {
            var tl = cur.previousTail();
            cur = with(cur, b -> b.tail = new Tail(tl.unitId(), tl.unitName(), tl.paragraphs().subList(tl.paragraphs().size() - 1, tl.paragraphs().size())));
            trimmed.add(t("previous unit's ending, reduced to its last paragraph", "fim da unidade anterior, reduzido ao último parágrafo"));
            tokens = estimate(cur);
        }
        if (tokens > budget && cur.openThreads().size() > 5) {
            var th = cur.openThreads().subList(0, 5);
            cur = with(cur, b -> b.threads = th);
            trimmed.add(t("open hooks beyond the 5 oldest (use open_threads)", "ganchos abertos além dos 5 mais antigos (use open_threads)"));
            tokens = estimate(cur);
        }
        if (tokens > budget) {
            var shorter = cur.inPlay().stream().map(e -> new EntityInPlay(e.id(), e.type(), e.base(), e.name(), e.summary(), e.criticality(),
                    e.state().size() > 4 ? e.state().subList(0, 4) : e.state())).toList();
            cur = with(cur, b -> b.inPlay = shorter);
            trimmed.add(t("detailed state of entities beyond 4 lines each (use state_at)", "estado detalhado das entidades além de 4 linhas cada (use state_at)"));
            tokens = estimate(cur);
        }
        if (tokens > budget && cur.previous().size() > 1) {
            var prev = cur.previous().subList(cur.previous().size() - 1, cur.previous().size());
            cur = with(cur, b -> b.previous = prev);
            trimmed.add(t("previous units beyond the immediately previous one", "unidades anteriores além da imediatamente anterior"));
            tokens = estimate(cur);
        }
        if (tokens > budget && cur.parts() != null && !cur.parts().isEmpty()) {
            var slim = cur.parts().stream().map(p -> new PartCard(p.id(), p.type(), p.name(), p.summary(), null, p.wordCount())).toList();
            cur = with(cur, b -> b.parts = slim);
            trimmed.add(t("attributes of the unit's parts (use node_get)", "atributos das partes da unidade (use node_get)"));
            tokens = estimate(cur);
        }
        if (tokens > budget) {
            // facts: keep law and foundational first
            var facts = cur.facts().stream().sorted((a, b) -> Integer.compare(rank(a), rank(b))).toList();
            while (tokens > budget && facts.size() > 10) {
                var f = facts.subList(0, Math.max(10, facts.size() * 3 / 4));
                facts = f;
                cur = with(cur, b -> b.facts = f);
                tokens = estimate(cur);
            }
            trimmed.add(t("lower-priority facts (use facts_list with about)", "fatos de menor prioridade (use facts_list com about)"));
        }
        var out = cur;
        return with(out, b -> {
            b.tokens = estimate(out);
            b.trimmed = trimmed;
        });
    }

    private static int rank(Views.Fact f) {
        int r = "law".equals(f.rigidity()) ? 0 : "norm".equals(f.rigidity()) ? 2 : 4;
        return r + ("foundational".equals(f.criticality()) ? 0 : 1);
    }

    /** Mutable copy used while trimming. */
    private static final class Builder {
        Views.Node unit;
        List<UnitCard> previous;
        Tail tail;
        List<PartCard> parts;
        List<EntityInPlay> inPlay;
        List<Views.Fact> facts;
        List<Thread> threads;
        StyleSample style;
        int tokens;
        List<String> trimmed;
    }

    private static WritingContext with(WritingContext c, java.util.function.Consumer<Builder> change) {
        Builder b = new Builder();
        b.unit = c.unit();
        b.previous = c.previous();
        b.tail = c.previousTail();
        b.parts = c.parts();
        b.inPlay = c.inPlay();
        b.facts = c.facts();
        b.threads = c.openThreads();
        b.style = c.style();
        b.tokens = c.estimatedTokens();
        b.trimmed = c.trimmed();
        change.accept(b);
        return new WritingContext(b.unit, c.path(), b.previous, b.tail, c.next(), b.parts, c.beatsHere(), c.beatsExpectedHere(), b.inPlay, b.facts,
                c.secrets(), c.revealedHere(), b.threads, b.style, c.guidance(), b.tokens, b.trimmed);
    }

    private static WritingContext withUnit(WritingContext c, Views.Node unit) {
        return with(c, b -> b.unit = unit);
    }

    private int estimate(WritingContext c) {
        return json.write(c).length() / 4;
    }
}
