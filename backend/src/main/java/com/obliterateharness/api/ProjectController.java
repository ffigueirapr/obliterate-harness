package com.obliterateharness.api;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Operation;
import com.obliterateharness.domain.Views;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.harness.CloneService;
import com.obliterateharness.harness.ContextService;
import com.obliterateharness.harness.HarnessLibrary;
import com.obliterateharness.harness.LocalizationService;
import com.obliterateharness.harness.ProfileService;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ChangesetService;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.store.AuditRepository;
import com.obliterateharness.store.ChangesetRepository;
import com.obliterateharness.store.ProjectDataRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.obliterateharness.i18n.I18n.t;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    /**
     * @param locale  language of the project's schema and content (en or pt); default English
     * @param context context to create the project in (its types, rules and directives come along); required on creation
     * @param profile preset inside a built-in context; default: the context's default preset
     */
    public record ProjectInput(String name, String description, String profile, Boolean requireReviewForLlm, String locale, String context) {}

    /** @param mode atomic (default), dry_run or best_effort */
    public record ChangesetInput(String title, List<Operation> operations, String mode) {}

    public record RejectInput(String reason) {}

    public record CloneInput(String newName, Boolean includeNodes, Boolean includeFacts, Boolean includeDirectives, String translateTo) {}

    public record LocalizeInput(String locale) {}

    private final ProjectService projects;
    private final ChangesetService changesets;
    private final AuditRepository audit;
    private final Graph graph;
    private final SchemaStore schema;
    private final ProfileService profiles;
    private final HarnessLibrary library;
    private final ProjectDataRepository projectData;
    private final CloneService cloner;
    private final LocalizationService localization;
    private final ContextService contexts;

    public ProjectController(ProjectService projects, ChangesetService changesets, AuditRepository audit, Graph graph, SchemaStore schema,
                             ProfileService profiles, HarnessLibrary library, ProjectDataRepository projectData, CloneService cloner,
                             LocalizationService localization, ContextService contexts) {
        this.projects = projects;
        this.changesets = changesets;
        this.audit = audit;
        this.graph = graph;
        this.schema = schema;
        this.profiles = profiles;
        this.library = library;
        this.projectData = projectData;
        this.cloner = cloner;
        this.localization = localization;
        this.contexts = contexts;
    }

    /** @param all also return contexts' definition (blueprint) projects */
    @GetMapping
    public List<Views.Project> list(@RequestParam(required = false) Boolean all) {
        return projects.list(Boolean.TRUE.equals(all));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Views.Project create(@RequestBody ProjectInput in) {
        if (in.context() == null || in.context().isBlank()) {
            throw new DomainException(t("'context' is required: a project is created inside a context (GET /api/contexts)",
                    "'context' é obrigatório: o projeto é criado dentro de um contexto (GET /api/contexts)"));
        }
        Lang lang = Lang.parse(in.locale(), Lang.en);
        return contexts.createProject(in.context(), in.name(), in.description(), lang.name(), in.profile(), in.requireReviewForLlm(), Actor.human);
    }

    @GetMapping("/{pid}")
    public Views.Project get(@PathVariable String pid) {
        return projects.get(pid);
    }

    @PatchMapping("/{pid}")
    public Views.Project update(@PathVariable String pid, @RequestBody ProjectInput in) {
        if (in.locale() != null && !Lang.parse(in.locale(), Lang.en).name().equals(projects.get(pid).locale())) {
            throw new DomainException(t("To change the project's language use POST /localize (it converts the schema too)",
                    "Para mudar o idioma do projeto use POST /localize (ele converte o schema também)"));
        }
        return projects.update(pid, in.name(), in.description(), in.profile(), in.requireReviewForLlm());
    }

    /** Copies the project (optionally translating the copy's schema). */
    @PostMapping("/{pid}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public CloneService.Result cloneProject(@PathVariable String pid, @RequestBody CloneInput in) {
        return cloner.cloneProject(pid, in.newName(), new CloneService.Options(
                in.includeNodes() == null || in.includeNodes(), in.includeFacts() == null || in.includeFacts(),
                in.includeDirectives() == null || in.includeDirectives()), in.translateTo());
    }

    /** Converts the project's schema (types, attributes, enums, relations) to another language. */
    @PostMapping("/{pid}/localize")
    public LocalizationService.Report localize(@PathVariable String pid, @RequestBody LocalizeInput in) {
        Lang target = Lang.parse(in.locale(), null);
        if (target == null) throw new DomainException(t("locale must be en or pt", "locale deve ser en ou pt"));
        return localization.localize(pid, target);
    }

    /**
     * Deletes the whole project. Needs ?confirm= with the project's exact name, so nothing is deleted by mistake.
     * There is no MCP tool for this: only the user deletes projects.
     */
    @DeleteMapping("/{pid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String pid, @RequestParam String confirm) {
        var p = projects.get(pid);
        if (!p.name().equals(confirm)) {
            throw new DomainException(t("Confirmation does not match: pass the project's exact name in 'confirm'",
                    "Confirmação não confere: informe o nome exato do projeto em 'confirm'"));
        }
        projects.deleteGraph(pid);
        projectData.deleteProject(pid);
    }

    @GetMapping("/{pid}/schema")
    public Views.SchemaSummary schema(@PathVariable String pid) {
        projects.get(pid);
        return graph.read(tx -> {
            Map<String, Long> counts = new HashMap<>();
            tx.run("MATCH (n:N {projectId:$p}) RETURN n.typeName AS t, count(n) AS c", Map.of("p", pid))
                    .forEachRemaining(r -> counts.put(r.get("t").asString(), r.get("c").asLong()));
            return new Views.SchemaSummary(schema.nodeTypes(tx, pid), schema.relTypes(tx, pid), schema.objectTypes(tx, pid), counts);
        });
    }

    // ---------- changesets ----------

    @PostMapping("/{pid}/changesets")
    public ChangesetService.Proposal propose(@PathVariable String pid, @RequestBody ChangesetInput in) {
        return changesets.propose(pid, Actor.human, null, in.title(), in.operations(), ChangesetService.Mode.parse(in.mode()));
    }

    @GetMapping("/{pid}/changesets")
    public List<ChangesetRepository.Row> listChangesets(@PathVariable String pid, @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "50") int limit) {
        return changesets.list(pid, status, limit);
    }

    @GetMapping("/{pid}/changesets/{id}")
    public ChangesetRepository.Row getChangeset(@PathVariable String pid, @PathVariable String id) {
        return changesets.get(pid, id);
    }

    @PostMapping("/{pid}/changesets/{id}/approve")
    public ChangesetService.Proposal approve(@PathVariable String pid, @PathVariable String id) {
        return changesets.approve(pid, id, Actor.human.name());
    }

    @PostMapping("/{pid}/changesets/{id}/reject")
    public void reject(@PathVariable String pid, @PathVariable String id, @RequestBody(required = false) RejectInput in) {
        changesets.reject(pid, id, in == null ? null : in.reason(), Actor.human.name());
    }

    @GetMapping("/{pid}/audit")
    public List<AuditRepository.Entry> audit(@PathVariable String pid, @RequestParam(required = false) String entityId,
                                             @RequestParam(defaultValue = "100") int limit) {
        return audit.list(pid, entityId, limit);
    }
}
