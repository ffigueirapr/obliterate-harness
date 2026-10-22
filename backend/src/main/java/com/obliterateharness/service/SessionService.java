package com.obliterateharness.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Ids;
import com.obliterateharness.domain.Views;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.GraphReader;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.harness.ContextService;
import com.obliterateharness.harness.GuidanceService;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.store.ChangesetRepository;
import com.obliterateharness.store.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.obliterateharness.i18n.I18n.t;

/**
 * LLM work sessions and the compact briefing that orients the LLM at the start (or after losing context).
 * State lives in the platform, not in the context window.
 */
@Service
public class SessionService {

    /**
     * @param locale    language of the project's schema and content: write in it
     * @param localTime current time in the configured time zone (harness.timezone)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Briefing(
            String sessionId,
            Views.Project project,
            String locale,
            String localTime,
            String goal,
            Map<String, Long> stats,
            List<TypeLine> nodeTypes,
            List<String> relTypes,
            List<Views.Fact> foundationalFacts,
            long criticalFactCount,
            SessionRepository.Checkpoint lastCheckpoint,
            Map<String, String> sessionRefs,
            long pendingChangesets,
            List<String> guidance,
            List<String> rules,
            ContextService.ContextView context
    ) {}

    public record TypeLine(String name, String base, long count, List<String> attributes) {}

    /** Operating rules always returned in the briefing (the harness's minimal contract). */
    static List<String> rules() {
        return List.of(
                t("Before creating a node, look for duplicates with node_search.", "Antes de criar um nó, procure duplicatas com node_search."),
                t("Read before writing: update/delete need expectedVersion (node_get / edge_get; every change in propose_changes returns the new version).",
                        "Leia antes de escrever: update/delete exigem expectedVersion (node_get / edge_get; cada mudança em propose_changes devolve a versão nova)."),
                t("Foundational/critical facts are binding rules: a law can never be broken; a norm only with a stated reason.",
                        "Fatos foundational/critical são regras vinculantes: law nunca pode ser violada; norm só com justificativa explícita."),
                t("Prefer propose_changes with several related operations in one batch, using $x aliases; pass sessionId so aliases keep working in later batches. Use dryRun to check a big batch first.",
                        "Prefira propose_changes com várias operações relacionadas em lote, usando apelidos $x; passe sessionId para os apelidos valerem nos lotes seguintes. Use dryRun para conferir um lote grande antes."),
                t("Keep long content at its source and point to it with a link attribute (role link); the graph holds the summary.",
                        "Mantenha conteúdo longo na origem e aponte para ele com um atributo de link (role link); o grafo guarda o resumo."),
                t("When you finish a step, or before the context grows large, record session_checkpoint (summary, decisions, pending items).",
                        "Ao concluir uma etapa ou antes de o contexto ficar grande, registre session_checkpoint (resumo, decisões, pendências)."),
                t("If you lose the thread (context compacted), call session_resume instead of guessing what was done.",
                        "Se perder o fio (contexto compactado), chame session_resume em vez de supor o que já foi feito."));
    }

    private final Graph graph;
    private final GraphReader reader;
    private final SchemaStore schema;
    private final ProjectService projects;
    private final SessionRepository sessions;
    private final ChangesetRepository changesets;
    private final GuidanceService guidance;
    private final ContextService contexts;

    public SessionService(Graph graph, GraphReader reader, SchemaStore schema, ProjectService projects,
                          SessionRepository sessions, ChangesetRepository changesets, GuidanceService guidance, ContextService contexts) {
        this.contexts = contexts;
        this.guidance = guidance;
        this.graph = graph;
        this.reader = reader;
        this.schema = schema;
        this.projects = projects;
        this.sessions = sessions;
        this.changesets = changesets;
    }

    public Briefing begin(String projectId, String goal) {
        projects.get(projectId);
        String id = Ids.next("s");
        sessions.create(id, projectId, goal);
        return briefing(projectId, id, goal, null);
    }

    public Briefing resume(String projectId, String sessionId) {
        var session = sessionId != null ? sessions.get(projectId, sessionId)
                : sessions.latest(projectId).orElseThrow(() -> new DomainException(t("No session in this project; use session_begin",
                "Nenhuma sessão neste projeto; use session_begin")));
        sessions.touch(session.id());
        var last = sessions.checkpoints(session.id(), 1).stream().findFirst().orElse(null);
        return briefing(projectId, session.id(), session.goal(), last);
    }

    public SessionRepository.Checkpoint checkpoint(String projectId, String sessionId, String summary, List<String> decisions,
                                                   List<String> todos, List<String> focusNodeIds) {
        if (summary == null || summary.isBlank()) throw new DomainException(t("summary is required in the checkpoint", "summary é obrigatório no checkpoint"));
        sessions.get(projectId, sessionId);
        sessions.addCheckpoint(sessionId, projectId, summary, decisions, todos, focusNodeIds);
        return sessions.checkpoints(sessionId, 1).getFirst();
    }

    public List<SessionRepository.Checkpoint> history(String projectId, String sessionId, int limit) {
        sessions.get(projectId, sessionId);
        return sessions.checkpoints(sessionId, limit);
    }

    private Briefing briefing(String projectId, String sessionId, String goal, SessionRepository.Checkpoint last) {
        var project = projects.get(projectId);
        Lang lang = Lang.parse(project.locale(), Lang.pt);
        var context = contexts.ofProject(project, lang).orElse(null);
        List<String> g = new ArrayList<>(contexts.guidanceFor(project, lang));
        g.addAll(guidance.forEvent(projectId, "session", List.of(), List.of()));
        Map<String, String> refs = sessionId == null ? Map.of() : sessions.refs(sessionId);
        return graph.read(tx -> {
            var nodeTypes = schema.nodeTypes(tx, projectId);
            Map<String, Long> counts = new HashMap<>();
            tx.run("MATCH (n:N {projectId:$p}) RETURN n.typeId AS t, count(n) AS c", Map.of("p", projectId))
                    .forEachRemaining(r -> counts.put(r.get("t").asString(), r.get("c").asLong()));
            List<TypeLine> types = new ArrayList<>();
            for (var nt : nodeTypes) {
                types.add(new TypeLine(nt.name(), nt.base(), counts.getOrDefault(nt.id(), 0L),
                        nt.attrs().stream().map(a -> a.key() + ":" + a.dataType().toLowerCase() + (a.role() != null ? "(" + a.role() + ")" : "")).toList()));
            }
            var rels = schema.relTypes(tx, projectId).stream()
                    .map(r -> r.name() + " (" + r.base() + (r.systemKey() != null ? ", " + r.systemKey() : "") + ")").toList();
            Map<String, Long> stats = new HashMap<>();
            stats.put("nodes", counts.values().stream().mapToLong(Long::longValue).sum());
            stats.put("edges", tx.run("MATCH ()-[r:R {projectId:$p, kind:'rel'}]->() RETURN count(r) AS c", Map.of("p", projectId)).single().get("c").asLong());
            stats.put("facts", tx.run("MATCH (f:Fact {projectId:$p}) RETURN count(f) AS c", Map.of("p", projectId)).single().get("c").asLong());
            long critical = tx.run("MATCH (f:Fact:Critical {projectId:$p}) RETURN count(f) AS c", Map.of("p", projectId)).single().get("c").asLong();
            var foundational = reader.facts(tx, projectId, null, "foundational", null, 30);
            return new Briefing(sessionId, project, lang.name(), ZonedDateTime.now(projects.zone()).toString(), goal, stats, types, rels,
                    foundational, critical, last, refs, changesets.countPending(projectId), g, rules(), context);
        });
    }
}
