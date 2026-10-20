package com.obliterateharness.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Ids;
import com.obliterateharness.domain.Operation;
import com.obliterateharness.domain.Views;
import com.obliterateharness.graph.ChangeExecutor;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.store.AuditRepository;
import com.obliterateharness.store.ChangesetRepository;
import com.obliterateharness.store.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.obliterateharness.i18n.I18n.t;

/**
 * The single write path. Humans apply directly; the LLM gets a pending proposal when the project requires review.
 * Proposals are validated right away (dry run with rollback), so the LLM gets errors immediately.
 *
 * <p>Modes: atomic (default — all or nothing), dry_run (validates everything, applies nothing, reports every error),
 * best_effort (applies the valid operations and returns the invalid ones).
 */
@Service
public class ChangesetService {

    public enum Status { pending, applied, rejected }

    public enum Mode {
        atomic, dry_run, best_effort;

        public static Mode parse(String v) {
            if (v == null || v.isBlank()) return atomic;
            for (Mode m : values()) if (m.name().equalsIgnoreCase(v)) return m;
            throw new DomainException(t("Invalid mode: '", "Modo inválido: '") + v + "'. " + t("Valid: atomic, dry_run, best_effort", "Válidos: atomic, dry_run, best_effort"));
        }
    }

    /**
     * @param status applied, pending, dry_run, partial (best_effort with failures) or nothing (best_effort with every op failing)
     * @param ids    aliases declared in this batch → ids (with a sessionId they stay valid for the rest of the session)
     * @param errors every invalid operation (dry_run / best_effort)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Proposal(String changesetId, String status, Map<String, String> ids, List<Summary> changes, String message,
                           List<String> guidance, List<OpError> errors) {
        public Proposal withGuidance(List<String> g) {
            return new Proposal(changesetId, status, ids, changes, message, g, errors);
        }
    }

    /** @param version the entity's version after the change — use it as expectedVersion on the next update/delete */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Summary(int index, String op, String kind, String id, String label, Long version) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record OpError(int index, String op, String ref, String kind, String message) {}

    private final Graph graph;
    private final ChangeExecutor executor;
    private final ChangesetRepository changesets;
    private final AuditRepository audit;
    private final ProjectService projects;
    private final SessionRepository sessions;

    public ChangesetService(Graph graph, ChangeExecutor executor, ChangesetRepository changesets, AuditRepository audit, ProjectService projects,
                            SessionRepository sessions) {
        this.graph = graph;
        this.executor = executor;
        this.changesets = changesets;
        this.audit = audit;
        this.projects = projects;
        this.sessions = sessions;
    }

    public Proposal propose(String projectId, Actor actor, String sessionId, String title, List<Operation> ops) {
        return propose(projectId, actor, sessionId, title, ops, Mode.atomic);
    }

    public Proposal propose(String projectId, Actor actor, String sessionId, String title, List<Operation> ops, Mode mode) {
        var project = projects.get(projectId);
        Map<String, String> known = sessionId == null ? Map.of() : sessions.refs(sessionId);
        var prepared = executor.prepare(ops, known);
        boolean review = actor == Actor.llm && project.requireReviewForLlm();

        if (mode == Mode.dry_run) {
            var outcomes = graph.dryRun(tx -> executor.applyCollecting(tx, projectId, actor, prepared.ops(), prepared.errors()));
            var errs = errors(outcomes);
            return new Proposal(null, "dry_run", prepared.newRefs(), okSummaries(outcomes), errs.isEmpty()
                    ? t("Every operation is valid. Nothing was written; send again without dryRun to apply.",
                    "Todas as operações são válidas. Nada foi gravado; envie de novo sem dryRun para aplicar.")
                    : errs.size() + t(" invalid operation(s). Nothing was written.", " operação(ões) inválida(s). Nada foi gravado."), null, errs);
        }

        List<Operation> toApply = prepared.ops();
        List<Integer> originalIndex = new ArrayList<>();
        for (int i = 0; i < toApply.size(); i++) originalIndex.add(i);
        List<OpError> failed = List.of();
        if (mode == Mode.best_effort) {
            var outcomes = graph.dryRun(tx -> executor.applyCollecting(tx, projectId, actor, prepared.ops(), prepared.errors()));
            failed = errors(outcomes);
            Set<Integer> bad = failed.stream().map(OpError::index).collect(Collectors.toSet());
            toApply = new ArrayList<>();
            originalIndex = new ArrayList<>();
            for (int i = 0; i < prepared.ops().size(); i++) {
                if (bad.contains(i)) continue;
                toApply.add(prepared.ops().get(i));
                originalIndex.add(i);
            }
            if (toApply.isEmpty()) {
                return new Proposal(null, "nothing", Map.of(), List.of(), t("No operation was valid; nothing was written.",
                        "Nenhuma operação era válida; nada foi gravado."), null, failed);
            }
        } else {
            prepared.throwIfErrors();
        }

        final List<Operation> batch = toApply;
        final List<Integer> idx = originalIndex;
        Map<String, String> appliedRefs = new LinkedHashMap<>();
        for (Operation op : batch) if (op.ref() != null) appliedRefs.put(op.ref(), op.id());
        String id = Ids.next("cs");
        String partialNote = failed.isEmpty() ? "" : " " + failed.size() + t(" invalid operation(s) were skipped (see errors).", " operação(ões) inválida(s) ficaram de fora (veja errors).");

        if (!review) {
            var results = remap(graph.write(tx -> executor.apply(tx, projectId, actor, batch)), idx);
            changesets.insert(new ChangesetRepository.Row(id, projectId, actor.name(), sessionId, title, Status.applied.name(), batch,
                    summarize(results), null, null, OffsetDateTime.now(), actor.name()));
            audit.record(projectId, id, actor.name(), results);
            if (sessionId != null && !appliedRefs.isEmpty()) sessions.saveRefs(sessionId, appliedRefs);
            return new Proposal(id, failed.isEmpty() ? Status.applied.name() : "partial", appliedRefs, summarize(results),
                    t("Applied.", "Aplicado.") + partialNote, null, failed);
        }
        var results = remap(graph.dryRun(tx -> executor.apply(tx, projectId, actor, batch)), idx);
        changesets.insert(new ChangesetRepository.Row(id, projectId, actor.name(), sessionId, title, Status.pending.name(), batch,
                summarize(results), null, null, null, null));
        return new Proposal(id, Status.pending.name(), appliedRefs, summarize(results),
                t("Valid proposal, pending human review. The ids above only become usable after approval; until then, do not treat these changes as canon.",
                        "Proposta válida e pendente de revisão humana. Os ids acima só valem depois da aprovação; até lá, não conte com essas mudanças como canônicas.")
                        + partialNote, null, failed);
    }

    public Proposal approve(String projectId, String changesetId, String decidedBy) {
        var row = changesets.get(projectId, changesetId);
        if (!Status.pending.name().equals(row.status())) {
            throw new DomainException("Changeset " + changesetId + t(" is ", " está ") + row.status());
        }
        try {
            var results = graph.write(tx -> executor.apply(tx, projectId, Actor.valueOf(row.actor()) == Actor.llm ? Actor.llm : Actor.human, row.operations()));
            changesets.decide(changesetId, Status.applied.name(), summarize(results), null, decidedBy);
            audit.record(projectId, changesetId, row.actor(), results);
            if (row.sessionId() != null) {
                Map<String, String> refs = new LinkedHashMap<>();
                for (Operation op : row.operations()) if (op.ref() != null) refs.put(op.ref(), op.id());
                if (!refs.isEmpty()) sessions.saveRefs(row.sessionId(), refs);
            }
            return new Proposal(changesetId, Status.applied.name(), refIds(row.operations()), summarize(results),
                    t("Approved and applied.", "Aprovado e aplicado."), null, null);
        } catch (DomainException e) {
            changesets.setError(changesetId, e.getMessage());
            throw new DomainException(t("Could not apply (did the graph change since the proposal?): ", "Não foi possível aplicar (o grafo mudou desde a proposta?): ")
                    + e.getMessage());
        }
    }

    public void reject(String projectId, String changesetId, String reason, String decidedBy) {
        var row = changesets.get(projectId, changesetId);
        if (!Status.pending.name().equals(row.status())) {
            throw new DomainException("Changeset " + changesetId + t(" is ", " está ") + row.status());
        }
        changesets.decide(changesetId, Status.rejected.name(), row.result(), reason, decidedBy);
    }

    public ChangesetRepository.Row get(String projectId, String id) {
        return changesets.get(projectId, id);
    }

    public List<ChangesetRepository.Row> list(String projectId, String status, int limit) {
        return changesets.list(projectId, status, limit);
    }

    private static List<ChangeExecutor.OpResult> remap(List<ChangeExecutor.OpResult> results, List<Integer> originalIndex) {
        return results.stream().map(r -> new ChangeExecutor.OpResult(originalIndex.get(r.index()), r.op(), r.entityKind(), r.entityId(), r.ref(),
                r.before(), r.after())).toList();
    }

    private static List<OpError> errors(List<ChangeExecutor.OpOutcome> outcomes) {
        return outcomes.stream().filter(o -> !o.ok()).map(o -> new OpError(o.index(), o.op(), o.ref(), o.errorKind(), o.error())).toList();
    }

    private static List<Summary> okSummaries(List<ChangeExecutor.OpOutcome> outcomes) {
        return summarize(outcomes.stream().filter(ChangeExecutor.OpOutcome::ok).map(ChangeExecutor.OpOutcome::result).toList());
    }

    private static Map<String, String> refIds(List<Operation> ops) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Operation op : ops) if (op.ref() != null) m.put(op.ref(), op.id());
        return m;
    }

    private static List<Summary> summarize(List<ChangeExecutor.OpResult> results) {
        return results.stream().map(r -> new Summary(r.index(), r.op(), r.entityKind(), r.entityId(), label(r), version(r))).toList();
    }

    private static Long version(ChangeExecutor.OpResult r) {
        return switch (r.after()) {
            case Views.Node n -> n.version();
            case Views.Edge e -> e.version();
            case Views.Fact f -> f.version();
            case null, default -> null;
        };
    }

    private static String label(ChangeExecutor.OpResult r) {
        Object o = r.after() != null ? r.after() : r.before();
        return switch (o) {
            case Views.Node n -> n.name() + " (" + n.type() + ")";
            case Views.Edge e -> e.fromName() + " —" + e.relType() + "→ " + e.toName();
            case Views.Fact f -> f.text().length() > 80 ? f.text().substring(0, 80) + "…" : f.text();
            case Views.Directive d -> d.text().length() > 80 ? d.text().substring(0, 80) + "…" : d.text();
            case com.obliterateharness.domain.NodeTypeDef nt -> nt.name() + " (" + nt.base() + ")";
            case com.obliterateharness.domain.RelTypeDef rt -> rt.name() + " (" + rt.base() + ")";
            case com.obliterateharness.domain.ObjectTypeDef ot -> ot.name();
            case null, default -> null;
        };
    }
}
