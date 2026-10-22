package com.obliterateharness.harness;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.JsonCodec;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings graphs written by older versions up to date. Idempotent: running it again changes nothing.
 * <ol>
 *   <li>projects without a locale are Portuguese (they were created with the Portuguese packs);</li>
 *   <li>the narrative layer is removed (the platform became domain-neutral): the CONTAINS structure relation and its
 *       edges, unit order/word counts, time anchors on edges, secret reveal points on facts, style settings on projects,
 *       narrative system keys, and attribute roles/data types only the narrative engine used;</li>
 *   <li>nodes of the removed base kinds become ordinary ones (Unit → Note, Device → Concept), and relation types of the
 *       removed bases (STRUCTURE, SETUP_PAYOFF) become REFERENCE, so no data is lost;</li>
 *   <li>graph spaces get their new names (world → knowledge, characters → actors, story → knowledge, templates → analysis);</li>
 *   <li>projects that belonged to no context (the old narrative ones) move to a "Legacy projects" context, so they stay
 *       visible in the interface; it is created only when there is something to move.</li>
 * </ol>
 */
@Component
@Order(10)
public class StartupMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupMigration.class);

    private static final Map<String, String> SPACES = Map.of("world", "knowledge", "characters", "actors", "story", "knowledge", "templates", "analysis");
    private static final Map<String, String> BASES = Map.of("Unit", "Note", "Device", "Concept");
    private static final Set<String> NARRATIVE_ROLES = Set.of("manuscript", "word_count", "location", "secret");
    private static final List<String> NARRATIVE_SYSTEM_KEYS = List.of("template", "beat", "thread", "beat_of", "maps_to_beat",
            "plants", "pays", "about", "appears_in", "located_in");

    private final Graph graph;
    private final JsonCodec json;
    private final ProjectService projects;
    private final ContextService contexts;

    public StartupMigration(Graph graph, JsonCodec json, ProjectService projects, ContextService contexts) {
        this.graph = graph;
        this.json = json;
        this.projects = projects;
        this.contexts = contexts;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Long> done = new LinkedHashMap<>();
        graph.write(tx -> {
            done.put("locales", count(tx, "MATCH (p:Project) WHERE p.locale IS NULL SET p.locale = 'pt' RETURN count(p) AS c"));
            done.put("structureEdges", count(tx, "MATCH ()-[r:R {kind:'structure'}]->() DELETE r RETURN count(*) AS c"));
            done.put("containsRelTypes", count(tx, "MATCH (t:RelType) WHERE t.name = 'CONTAINS' DETACH DELETE t RETURN count(*) AS c"));
            done.put("unitFields", count(tx, """
                    MATCH (n:N) WHERE n.sortKey IS NOT NULL OR n.order IS NOT NULL OR n.wordCount IS NOT NULL
                    REMOVE n.sortKey, n.order, n.wordCount RETURN count(n) AS c"""));
            done.put("edgeAnchors", count(tx, """
                    MATCH ()-[r:R]->() WHERE r.fromUnit IS NOT NULL OR r.toUnit IS NOT NULL
                    REMOVE r.fromUnit, r.toUnit RETURN count(r) AS c"""));
            done.put("factReveals", count(tx, "MATCH (f:Fact) WHERE f.revealAt IS NOT NULL REMOVE f.revealAt RETURN count(f) AS c"));
            done.put("styleSettings", count(tx, """
                    MATCH (p:Project) WHERE p.styleSample IS NOT NULL OR p.styleReference IS NOT NULL
                    REMOVE p.styleSample, p.styleReference RETURN count(p) AS c"""));
            done.put("systemKeys", count(tx, """
                    MATCH (t) WHERE (t:NodeType OR t:RelType) AND t.systemKey IN $keys
                    REMOVE t.systemKey RETURN count(t) AS c""", Map.of("keys", NARRATIVE_SYSTEM_KEYS)));
            long bases = 0;
            for (var e : BASES.entrySet()) {
                bases += count(tx, "MATCH (n:N:" + e.getKey() + ") REMOVE n:" + e.getKey() + " SET n:" + e.getValue()
                        + ", n.base = $to RETURN count(n) AS c", Map.of("to", e.getValue()));
                bases += count(tx, "MATCH (t:NodeType {base:$from}) SET t.base = $to RETURN count(t) AS c", Map.of("from", e.getKey(), "to", e.getValue()));
            }
            done.put("baseKinds", bases);
            done.put("relBases", count(tx, """
                    MATCH (t:RelType) WHERE t.base IN ['STRUCTURE', 'SETUP_PAYOFF'] SET t.base = 'REFERENCE' RETURN count(t) AS c"""));
            count(tx, "MATCH ()-[r:R {kind:'rel'}]->() WHERE r.base IN ['STRUCTURE', 'SETUP_PAYOFF'] SET r.base = 'REFERENCE' RETURN count(r) AS c");
            done.put("relEndpoints", count(tx, """
                    MATCH (t:RelType) WHERE t.allowed CONTAINS '"@Unit"' OR t.allowed CONTAINS '"@Device"'
                    SET t.allowed = replace(replace(t.allowed, '"@Unit"', '"@Note"'), '"@Device"', '"@Concept"')
                    RETURN count(t) AS c"""));
            done.put("attributeDefs", migrateAttributes(tx));
            long spaces = 0;
            for (var e : SPACES.entrySet()) {
                spaces += count(tx, "MATCH (n:N {space:$from}) SET n.space = $to RETURN count(n) AS c", Map.of("from", e.getKey(), "to", e.getValue()));
            }
            done.put("spaces", spaces);
            return null;
        });
        done.put("projectsWithoutContext", adoptOrphans());
        done.values().removeIf(v -> v == 0);
        if (!done.isEmpty()) log.info("Migration to the domain-neutral graph: {}", done);
    }

    /** Projects with no context (created before contexts existed) move to a "Legacy projects" context. */
    private long adoptOrphans() {
        var orphans = projects.list().stream().filter(p -> p.context() == null).toList();
        if (orphans.isEmpty()) return 0;
        var ctx = I18n.with(Lang.pt, () -> contexts.create("Projetos legados",
                "Projetos criados antes dos contextos, trazidos para cá pela migração. Mova-os ou recrie-os num contexto que faça sentido.",
                "🗄️", null, "pt", null, null, null, Actor.system));
        for (var p : orphans) projects.setContext(p.id(), ctx.id(), false);
        return orphans.size();
    }

    /** Drops narrative-only roles and turns story_time attributes into plain short strings (values are kept). */
    private long migrateAttributes(org.neo4j.driver.SimpleQueryRunner tx) {
        var rows = tx.run("MATCH (t:NodeType) WHERE t.attributes CONTAINS '\"role\"' OR t.attributes CONTAINS 'story_time' "
                + "RETURN t.id AS id, t.attributes AS attrs").list();
        long changed = 0;
        for (var r : rows) {
            List<Map<String, Object>> attrs = json.convert(json.readList(r.get("attrs").asString("[]"), Object.class),
                    new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            boolean touched = false;
            for (Map<String, Object> a : attrs) {
                if (a.get("role") instanceof String role && NARRATIVE_ROLES.contains(role)) {
                    a.remove("role");
                    touched = true;
                }
                for (String k : List.of("dataType", "itemType")) {
                    if ("story_time".equalsIgnoreCase(String.valueOf(a.get(k)))) {
                        a.put(k, "string_short");
                        touched = true;
                    }
                }
            }
            if (touched) {
                tx.run("MATCH (t:NodeType {id:$id}) SET t.attributes = $a", Map.of("id", r.get("id").asString(), "a", json.write(attrs))).consume();
                changed++;
            }
        }
        return changed;
    }

    private static long count(org.neo4j.driver.SimpleQueryRunner tx, String cypher) {
        return count(tx, cypher, Map.of());
    }

    private static long count(org.neo4j.driver.SimpleQueryRunner tx, String cypher, Map<String, Object> params) {
        return tx.run(cypher, params).single().get("c").asLong();
    }
}
