package com.obliterateharness.harness;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Brings projects created by older versions up to date. Idempotent and additive only:
 * <ol>
 *   <li>projects without a locale are Portuguese (they were created with the Portuguese packs);</li>
 *   <li>engine types/relations get their system key, found by their old names;</li>
 *   <li>projects with a known profile get what the packs gained (e.g. the about relation, clues in plants/pays,
 *       scene location) — recorded as a changeset by "system".</li>
 * </ol>
 */
@Component
@Order(10)
public class StartupMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupMigration.class);
    static final int SCHEMA_VERSION = 2;

    private final Graph graph;
    private final ProjectService projects;
    private final ProfileService profiles;
    private final HarnessLibrary library;

    public StartupMigration(Graph graph, ProjectService projects, ProfileService profiles, HarnessLibrary library) {
        this.graph = graph;
        this.projects = projects;
        this.profiles = profiles;
        this.library = library;
    }

    @Override
    public void run(ApplicationArguments args) {
        graph.write(tx -> {
            long locales = tx.run("MATCH (p:Project) WHERE p.locale IS NULL SET p.locale = 'pt' RETURN count(p) AS c").single().get("c").asLong();
            long nodeKeys = tx.run("""
                    MATCH (t:NodeType) WHERE t.systemKey IS NULL AND t.nameLower IN keys($m)
                    WITH t, $m[t.nameLower] AS key
                    WHERE NOT EXISTS { MATCH (o:NodeType {projectId:t.projectId, systemKey:key}) }
                    SET t.systemKey = key RETURN count(t) AS c
                    """, Map.of("m", Reserved.LEGACY_NODE_TYPES)).single().get("c").asLong();
            long relKeys = tx.run("""
                    MATCH (t:RelType) WHERE t.systemKey IS NULL AND t.nameLower IN keys($m)
                    WITH t, $m[t.nameLower] AS key
                    WHERE NOT EXISTS { MATCH (o:RelType {projectId:t.projectId, systemKey:key}) }
                    SET t.systemKey = key RETURN count(t) AS c
                    """, Map.of("m", Reserved.LEGACY_REL_TYPES)).single().get("c").asLong();
            if (locales + nodeKeys + relKeys > 0) log.info("Migration: {} locales, {} node system keys, {} relation system keys", locales, nodeKeys, relKeys);
            return null;
        });
        var pending = graph.read(tx -> tx.run("""
                MATCH (p:Project) WHERE coalesce(p.harnessSchema, 1) < $v RETURN p.id AS id, p.profile AS profile, p.locale AS locale
                """, Map.of("v", SCHEMA_VERSION)).list(r -> new String[]{r.get("id").asString(), r.get("profile").asString(null), r.get("locale").asString(null)}));
        for (String[] p : pending) {
            Lang lang = Lang.parse(p[2], Lang.pt);
            try {
                if (p[1] != null && library.profile(p[1], lang).isPresent()) {
                    I18n.with(lang, () -> profiles.apply(p[0], p[1], Actor.system));
                }
                graph.write(tx -> tx.run("MATCH (x:Project {id:$id}) SET x.harnessSchema = $v", Map.of("id", p[0], "v", SCHEMA_VERSION)).consume());
            } catch (RuntimeException e) {
                log.warn("Migration of project {} failed (it keeps working with its current schema): {}", p[0], e.getMessage());
            }
        }
        if (!pending.isEmpty()) log.info("Migration: {} project(s) brought to harness schema {}", pending.size(), SCHEMA_VERSION);
    }
}
