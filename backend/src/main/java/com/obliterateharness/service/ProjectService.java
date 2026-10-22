package com.obliterateharness.service;

import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Ids;
import com.obliterateharness.domain.NotFoundException;
import com.obliterateharness.domain.Views;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.i18n.Lang;
import org.neo4j.driver.SimpleQueryRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

@Service
public class ProjectService {
    private final Graph graph;
    private final ZoneId zone;

    public ProjectService(Graph graph, @Value("${harness.timezone:UTC}") String timezone) {
        this.graph = graph;
        this.zone = ZoneId.of(timezone);
    }

    public Views.Project create(String name, String description, String profile, Boolean requireReviewForLlm, String locale) {
        if (name == null || name.isBlank()) throw new DomainException(t("Project name is required", "Nome do projeto é obrigatório"));
        String id = Ids.next("p");
        String now = Instant.now().toString();
        String lang = Lang.parse(locale, Lang.en).name();
        graph.write(tx -> {
            tx.run("""
                    CREATE (p:Project {id:$id, name:$name, description:$description, profile:$profile,
                                       requireReviewForLlm:$review, createdAt:$now, locale:$locale})
                    CREATE (t:RelType {id:$rt, projectId:$id, name:'CONTAINS', nameLower:'contains', base:'STRUCTURE',
                                       description:'Narrative structure (parent contains child). System relation.',
                                       symmetric:false, temporal:false, allowed:'[{"from":"@Unit","to":"@Unit"}]'})
                    """, params("id", id, "name", name.trim(), "description", description, "profile", profile,
                    "review", requireReviewForLlm != null && requireReviewForLlm, "now", now, "rt", "rt_contains_" + id, "locale", lang)).consume();
            return null;
        });
        return get(id);
    }

    public Views.Project get(String id) {
        return graph.read(tx -> find(tx, id));
    }

    public Views.Project find(SimpleQueryRunner q, String id) {
        var rows = q.run("MATCH (p:Project {id:$id}) RETURN p", Map.of("id", id)).list(r -> toProject(r.get("p")));
        if (rows.isEmpty()) {
            throw new NotFoundException(t("Project '", "Projeto '") + id + t("' does not exist. Use project_list to see the projects.",
                    "' não existe. Use project_list para ver os projetos."));
        }
        return rows.getFirst();
    }

    /** Language of the project's schema and content. Projects from before locales existed are Portuguese. */
    public Lang locale(String id) {
        return Lang.parse(get(id).locale(), Lang.pt);
    }

    /** Projects people work in (context definition projects are left out). */
    public List<Views.Project> list() {
        return list(false);
    }

    public List<Views.Project> list(boolean includeBlueprints) {
        return graph.read(tx -> tx.run("""
                MATCH (p:Project) WHERE $all OR coalesce(p.blueprint, false) = false
                RETURN p ORDER BY p.createdAt DESC
                """, Map.of("all", includeBlueprints)).list(r -> toProject(r.get("p"))));
    }

    /** Projects of a context. Projects without a context belong to the narrative context. */
    public List<Views.Project> inContext(String contextId) {
        return list().stream().filter(p -> contextId.equals(p.context() == null ? NARRATIVE_CONTEXT : p.context())).toList();
    }

    public static final String NARRATIVE_CONTEXT = "narrative";

    /** Ties a project to a context; blueprint marks the context's definition project. */
    public void setContext(String id, String contextId, boolean blueprint) {
        graph.write(tx -> {
            tx.run("MATCH (p:Project {id:$id}) SET p.context = $c, p.blueprint = $b",
                    params("id", id, "c", contextId, "b", blueprint ? Boolean.TRUE : null)).consume();
            return null;
        });
    }

    public Views.Project update(String id, String name, String description, String profile, Boolean requireReviewForLlm) {
        return update(id, name, description, profile, requireReviewForLlm, null, null);
    }

    /** Updates settings. Locale is not changed here: use localization, which also converts the schema. */
    public Views.Project update(String id, String name, String description, String profile, Boolean requireReviewForLlm,
                                String styleSample, String styleReference) {
        var current = get(id);
        String newName = name == null ? null : name.trim();
        if (newName != null) {
            if (newName.isEmpty()) throw new DomainException(t("Project name cannot be empty", "O nome do projeto não pode ficar vazio"));
            String wanted = newName;
            if (!Boolean.TRUE.equals(current.blueprint())
                    && list().stream().anyMatch(p -> !p.id().equals(id) && p.name().trim().equalsIgnoreCase(wanted))) {
                throw new DomainException(t("There is already a project named '", "Já existe um projeto chamado '") + wanted + "'");
            }
        }
        if (styleReference != null && !styleReference.isBlank()) {
            if (styleReference.equals(id)) throw new DomainException(t("A project cannot be its own style reference", "Um projeto não pode ser a própria referência de estilo"));
            get(styleReference);
        }
        graph.write(tx -> {
            tx.run("""
                    MATCH (p:Project {id:$id})
                    SET p.name = coalesce($name, p.name), p.description = coalesce($description, p.description),
                        p.profile = coalesce($profile, p.profile), p.requireReviewForLlm = coalesce($review, p.requireReviewForLlm),
                        p.styleSample = CASE WHEN $style IS NULL THEN p.styleSample WHEN $style = '' THEN null ELSE $style END,
                        p.styleReference = CASE WHEN $ref IS NULL THEN p.styleReference WHEN $ref = '' THEN null ELSE $ref END
                    """, params("id", id, "name", newName, "description", description, "profile", profile, "review", requireReviewForLlm,
                    "style", styleSample, "ref", styleReference)).consume();
            return null;
        });
        return get(id);
    }

    public void setLocale(String id, Lang locale) {
        graph.write(tx -> {
            tx.run("MATCH (p:Project {id:$id}) SET p.locale = $l", Map.of("id", id, "l", locale.name())).consume();
            return null;
        });
    }

    /** Deletes the whole project from the graph (nodes, facts, schema, directives). Postgres is cleaned by the caller. */
    public void deleteGraph(String id) {
        get(id);
        graph.write(tx -> {
            // in batches, so large projects don't exhaust memory
            long deleted;
            do {
                deleted = tx.run("""
                        MATCH (x) WHERE x.projectId = $id AND (x:N OR x:Fact OR x:Directive OR x:NodeType OR x:RelType OR x:ObjectType)
                        WITH x LIMIT 5000 DETACH DELETE x RETURN count(*) AS c
                        """, Map.of("id", id)).single().get("c").asLong();
            } while (deleted > 0);
            tx.run("MATCH (p:Project {id:$id}) DETACH DELETE p", Map.of("id", id)).consume();
            return null;
        });
    }

    /** Formats a stored UTC instant in the configured time zone (harness.timezone), with its offset. */
    public String localTime(String isoInstant) {
        if (isoInstant == null) return null;
        try {
            return Instant.parse(isoInstant).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (RuntimeException e) {
            return isoInstant;
        }
    }

    public ZoneId zone() {
        return zone;
    }

    private Views.Project toProject(org.neo4j.driver.Value p) {
        return new Views.Project(p.get("id").asString(), p.get("name").asString(), p.get("description").asString(null),
                p.get("profile").asString(null), p.get("requireReviewForLlm").asBoolean(false), localTime(p.get("createdAt").asString(null)),
                p.get("locale").asString(null), p.get("styleSample").asString(null), p.get("styleReference").asString(null),
                p.get("context").asString(null), p.get("blueprint").asBoolean(false) ? Boolean.TRUE : null);
    }
}
