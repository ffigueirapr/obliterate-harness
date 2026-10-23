package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.AttrDef;
import com.obliterateharness.domain.BaseKind;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Ids;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.ObjectTypeDef;
import com.obliterateharness.domain.RelTypeDef;
import com.obliterateharness.domain.Views;
import com.obliterateharness.graph.ChangeExecutor;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.store.AuditRepository;
import com.obliterateharness.store.ContextRepository;
import com.obliterateharness.store.ProjectDataRepository;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

/**
 * Backup and migration: exports projects (and the custom contexts they belong to) to a versioned JSON archive and imports it
 * back as new projects.
 * <p>
 * The archive ({@value #FORMAT}, version {@value #VERSION}) is meant to be read by other platforms too: every record carries
 * its original id and plain JSON values (attributes are objects, not encoded strings). Properties this version does not name
 * explicitly travel in each record's {@code extra}, so nothing is lost on a round trip. It does not carry relational history
 * (changesets, audit, sessions, text drafts) nor the narrative template library.
 * <p>
 * Import creates new projects with new ids, remapping every internal reference (edges, time anchors, facts, node_ref
 * attributes, style reference). All graph writes happen in one transaction; context rows (Postgres) are written after it and,
 * if that fails, the imported projects are removed again: all or nothing.
 */
@Service
public class ArchiveService {
    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

    public static final String FORMAT = "obliterate-archive";
    public static final int VERSION = 1;

    // ------------------------------------------------------------------ archive format (version 1)

    /**
     * @param scope    project (one project and its context) or all (every project and every custom context)
     * @param contexts contexts the projects belong to: custom ones carry their full definition, built-in ones only a reference
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Archive(String format, Integer version, String exportedAt, String generator, String scope,
                          List<ContextEntry> contexts, List<ProjectEntry> projects) {}

    /**
     * @param kind       builtin (exists on every Obliterate Harness instance; only id and name) or custom
     * @param definition custom contexts: the definition project (schema, rules as manual facts, directives)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextEntry(String id, String kind, String name, String description, String icon, String color, String locale,
                               String baseContext, String instructions, Map<String, String> spaceLabels, List<String> tabs,
                               ProjectEntry definition) {}

    /**
     * @param context id of the context (see the archive's contexts); null = narrative (projects from before contexts)
     * @param counts  how many records of each kind, to check an import
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectEntry(String id, String name, String description, String context, String locale, String profile, String createdAt,
                               Settings settings, Schema schema, List<NodeEntry> nodes, List<EdgeEntry> edges, List<FactEntry> facts,
                               List<DirectiveEntry> directives, Map<String, Integer> counts) {}

    /** @param styleReference id of another project (kept only when that project is in the same archive) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Settings(Boolean requireReviewForLlm, String styleSample, String styleReference) {}

    /** The system structure relation CONTAINS is not listed: every project has it. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Schema(List<NodeTypeDef> nodeTypes, List<RelTypeDef> relTypes, List<ObjectTypeDef> objectTypes) {}

    /**
     * @param attributes attribute values by key (node_ref attributes are not here: see refs)
     * @param refs       node_ref attributes: attribute key → node ids of this project. A read-only view of the attr edges, which are
     *                   what an import uses
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeEntry(String id, String typeId, String type, String base, String space, String name, String summary, String description,
                            List<String> tags, String criticality, Map<String, Object> attributes, Map<String, List<String>> refs,
                            Map<String, String> attributeCriticality,
                            Double order, Long version, String createdAt, String updatedAt, String createdBy, String updatedBy,
                            Map<String, Object> extra) {}

    /**
     * @param kind    rel (a typed relation), structure (parent contains child) or attr (mirror of a node_ref attribute)
     * @param attrKey attr edges: the attribute they mirror
     * @param fromUnit time anchor: unit (node id) where the relation starts to hold; toUnit where it ends
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EdgeEntry(String id, String kind, String relTypeId, String relType, String base, String from, String to, String attrKey,
                            String description, String criticality, List<String> tags, String fromUnit, String toUnit, Long version,
                            String createdAt, String updatedAt, String createdBy, Map<String, Object> extra) {}

    /**
     * @param originKind manual (written by someone) or node/attr/edge (mirrored from the criticality of that record, originId)
     * @param about      node ids the fact is about
     * @param revealAt   unit (node id) where the fact stops being a secret
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FactEntry(String id, String text, String rigidity, String criticality, String originKind, String originId, String originKey,
                            List<String> about, String revealAt, Long version, String createdAt, String updatedAt, String createdBy,
                            Map<String, Object> extra) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DirectiveEntry(String id, String text, List<String> when, List<String> bases, List<String> types, Integer priority,
                                 Boolean enabled, Long version, String createdAt, String updatedAt, Map<String, Object> extra) {}

    // ------------------------------------------------------------------ import result

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ImportResult(List<ImportedProject> projects, List<ImportedContext> contexts, List<String> notes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportedProject(String sourceId, String projectId, String name, String context, Map<String, Integer> counts) {}

    /** @param action created (recreated from the archive), existing (same id) or matched (an existing context with the same name) */
    public record ImportedContext(String sourceId, String contextId, String name, String action) {}

    /** @param name new name for the project (single-project archives only) */
    public record ImportOptions(Boolean recreateContexts, String name) {
        boolean recreate() {
            return recreateContexts == null || recreateContexts;
        }
    }

    private static final Set<String> NODE_KEYS = Set.of("id", "projectId", "typeId", "typeName", "base", "space", "name", "summary", "description",
            "tags", "criticality", "attrs", "attrMeta", "attrsText", "order", "version", "createdAt", "updatedAt", "createdBy", "updatedBy");
    private static final Set<String> EDGE_KEYS = Set.of("id", "projectId", "kind", "relTypeId", "name", "base", "attrKey", "description",
            "criticality", "tags", "fromUnit", "toUnit", "version", "createdAt", "updatedAt", "createdBy");
    private static final Set<String> FACT_KEYS = Set.of("id", "projectId", "text", "rigidity", "criticality", "originKind", "originId", "originKey",
            "revealAt", "version", "createdAt", "updatedAt", "createdBy");
    private static final Set<String> DIRECTIVE_KEYS = Set.of("id", "projectId", "text", "when", "bases", "types", "priority", "enabled", "version",
            "createdAt", "updatedAt");

    private final Graph graph;
    private final SchemaStore schema;
    private final ProjectService projects;
    private final ContextService contexts;
    private final ContextRepository contextRepo;
    private final ProjectDataRepository projectData;
    private final AuditRepository audit;
    private final JsonMapper mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public ArchiveService(Graph graph, SchemaStore schema, ProjectService projects, ContextService contexts, ContextRepository contextRepo,
                          ProjectDataRepository projectData, AuditRepository audit) {
        this.graph = graph;
        this.schema = schema;
        this.projects = projects;
        this.contexts = contexts;
        this.contextRepo = contextRepo;
        this.projectData = projectData;
        this.audit = audit;
    }

    // ================================================================== export

    /** One project and its context (a custom context goes with its definition). */
    public Archive exportProject(String projectId) {
        var p = projects.get(projectId);
        if (Boolean.TRUE.equals(p.blueprint())) {
            throw new DomainException(t("This is a context's definition project: export a project of that context, or everything (GET /api/export)",
                    "Este é o projeto de definição de um contexto: exporte um projeto desse contexto, ou tudo (GET /api/export)"));
        }
        List<ContextEntry> ctx = new ArrayList<>();
        contextEntry(p.context() == null ? ProjectService.NARRATIVE_CONTEXT : p.context()).ifPresent(ctx::add);
        return new Archive(FORMAT, VERSION, Instant.now().toString(), "obliterate-harness", "project", ctx, List.of(projectEntry(p)));
    }

    /** Every project people work in and every custom context (with its definition). */
    public Archive exportAll() {
        List<ContextEntry> ctx = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ProjectEntry> entries = new ArrayList<>();
        for (var p : projects.list()) {
            String c = p.context() == null ? ProjectService.NARRATIVE_CONTEXT : p.context();
            if (seen.add(c)) contextEntry(c).ifPresent(ctx::add);
            entries.add(projectEntry(p));
        }
        for (var row : contextRepo.list()) if (seen.add(row.id())) contextEntry(row.id()).ifPresent(ctx::add);
        return new Archive(FORMAT, VERSION, Instant.now().toString(), "obliterate-harness", "all", ctx, entries);
    }

    public String toJson(Archive a) {
        return mapper.writeValueAsString(a);
    }

    /** File name for an archive, e.g. obliterate-serasa-consumidor-2026-10-21.json. */
    public static String filename(Archive a) {
        String base = "project".equals(a.scope()) && !a.projects().isEmpty() ? slug(a.projects().getFirst().name()) : "all";
        return "obliterate-" + base + "-" + a.exportedAt().substring(0, 10) + ".json";
    }

    private java.util.Optional<ContextEntry> contextEntry(String id) {
        var row = contextRepo.get(id);
        if (row.isPresent()) {
            var r = row.get();
            ProjectEntry def = null;
            try {
                def = projectEntry(projects.get(r.blueprintProjectId()));
            } catch (RuntimeException e) {
                log.warn("Context {} has no readable definition project {}", id, r.blueprintProjectId(), e);
            }
            return java.util.Optional.of(new ContextEntry(r.id(), "custom", r.name(), r.description(), r.icon(), r.color(), r.locale(),
                    r.baseContext(), r.instructions(), r.spaceLabels(), r.tabs(), def));
        }
        try {
            var v = contexts.get(id, I18n.lang());
            return java.util.Optional.of(new ContextEntry(v.id(), "builtin", v.name(), null, null, null, null, null, null, null, null, null));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    private ProjectEntry projectEntry(Views.Project p) {
        String pid = p.id();
        return graph.read(tx -> {
            var nts = schema.nodeTypes(tx, pid).stream().map(nt -> new NodeTypeDef(nt.id(), null, nt.name(), nt.base(), nt.description(), nt.color(),
                    nt.strict(), nt.attributes(), nt.systemKey())).toList();
            var rts = schema.relTypes(tx, pid).stream().filter(rt -> !SchemaStore.CONTAINS.equals(rt.name()))
                    .map(rt -> new RelTypeDef(rt.id(), null, rt.name(), rt.base(), rt.description(), rt.symmetric(), rt.inverseName(), rt.temporal(),
                            rt.allowed(), rt.systemKey())).toList();
            var ots = schema.objectTypes(tx, pid).stream().map(ot -> new ObjectTypeDef(ot.id(), null, ot.name(), ot.description(), ot.fields())).toList();

            Map<String, Map<String, List<String>>> refs = new HashMap<>();
            tx.run("""
                    MATCH (a:N {projectId:$p})-[r:R {kind:'attr'}]->(b:N) RETURN a.id AS a, r.attrKey AS k, b.id AS b ORDER BY r.createdAt, r.id
                    """, Map.of("p", pid)).forEachRemaining(r -> refs.computeIfAbsent(r.get("a").asString(), x -> new LinkedHashMap<>())
                    .computeIfAbsent(r.get("k").asString(), x -> new ArrayList<>()).add(r.get("b").asString()));
            List<NodeEntry> nodes = new ArrayList<>();
            for (var r : tx.run("MATCH (n:N {projectId:$p}) RETURN n ORDER BY n.createdAt, n.id", Map.of("p", pid)).list()) {
                Map<String, Object> n = r.get("n").asMap();
                nodes.add(new NodeEntry(s(n, "id"), s(n, "typeId"), s(n, "typeName"), s(n, "base"), s(n, "space"), s(n, "name"), s(n, "summary"),
                        s(n, "description"), strings(n.get("tags")), s(n, "criticality"), readMap(s(n, "attrs")), refs.get(s(n, "id")),
                        stringMap(readMap(s(n, "attrMeta"))),
                        n.get("order") instanceof Number o ? o.doubleValue() : null, longOf(n.get("version")), s(n, "createdAt"), s(n, "updatedAt"),
                        s(n, "createdBy"), s(n, "updatedBy"), extra(n, NODE_KEYS)));
            }

            List<EdgeEntry> edges = new ArrayList<>();
            for (var r : tx.run("MATCH (a:N {projectId:$p})-[r:R]->(b:N) RETURN a.id AS a, b.id AS b, r ORDER BY r.createdAt, r.id",
                    Map.of("p", pid)).list()) {
                Map<String, Object> e = r.get("r").asMap();
                String kind = s(e, "kind") == null ? "rel" : s(e, "kind");
                edges.add(new EdgeEntry(s(e, "id"), kind, s(e, "relTypeId"), s(e, "name"), s(e, "base"), r.get("a").asString(), r.get("b").asString(),
                        s(e, "attrKey"), s(e, "description"), s(e, "criticality"), strings(e.get("tags")), s(e, "fromUnit"), s(e, "toUnit"),
                        longOf(e.get("version")), s(e, "createdAt"), s(e, "updatedAt"), s(e, "createdBy"), extra(e, EDGE_KEYS)));
            }

            List<FactEntry> facts = new ArrayList<>();
            for (var r : tx.run("""
                    MATCH (f:Fact {projectId:$p}) OPTIONAL MATCH (f)-[:ABOUT]->(n:N)
                    RETURN f, collect(n.id) AS about ORDER BY f.createdAt, f.id
                    """, Map.of("p", pid)).list()) {
                Map<String, Object> f = r.get("f").asMap();
                facts.add(new FactEntry(s(f, "id"), s(f, "text"), s(f, "rigidity"), s(f, "criticality"), s(f, "originKind"), s(f, "originId"),
                        s(f, "originKey"), r.get("about").asList(Value::asString), s(f, "revealAt"), longOf(f.get("version")), s(f, "createdAt"),
                        s(f, "updatedAt"), s(f, "createdBy"), extra(f, FACT_KEYS)));
            }

            List<DirectiveEntry> dirs = new ArrayList<>();
            for (var r : tx.run("MATCH (d:Directive {projectId:$p}) RETURN d ORDER BY d.createdAt, d.id", Map.of("p", pid)).list()) {
                Map<String, Object> d = r.get("d").asMap();
                dirs.add(new DirectiveEntry(s(d, "id"), s(d, "text"), strings(d.get("when")), strings(d.get("bases")), strings(d.get("types")),
                        d.get("priority") instanceof Number n ? n.intValue() : null, d.get("enabled") instanceof Boolean b ? b : null,
                        longOf(d.get("version")), s(d, "createdAt"), s(d, "updatedAt"), extra(d, DIRECTIVE_KEYS)));
            }

            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("nodeTypes", nts.size());
            counts.put("relTypes", rts.size());
            counts.put("objectTypes", ots.size());
            counts.put("nodes", nodes.size());
            counts.put("edges", edges.size());
            counts.put("facts", facts.size());
            counts.put("directives", dirs.size());
            return new ProjectEntry(pid, p.name(), p.description(), p.context(), p.locale(), p.profile(), p.createdAt(),
                    new Settings(p.requireReviewForLlm(), p.styleSample(), p.styleReference()), new Schema(nts, rts, ots), nodes, edges, facts, dirs, counts);
        });
    }

    // ================================================================== import

    /** Reads an archive from JSON text, checking format and version before anything else. */
    public Archive parse(String text) {
        if (text == null || text.isBlank()) throw new DomainException(t("The archive is empty", "O arquivo está vazio"));
        JsonNode tree;
        try {
            tree = mapper.readTree(text);
        } catch (JacksonException e) {
            throw new DomainException(t("The archive is not valid JSON: ", "O arquivo não é um JSON válido: ") + e.getOriginalMessage());
        }
        return parse(tree);
    }

    public Archive parse(JsonNode tree) {
        if (tree == null || !tree.isObject()) throw new DomainException(t("The archive must be a JSON object", "O arquivo deve ser um objeto JSON"));
        JsonNode format = tree.get("format");
        if (format == null || !FORMAT.equals(format.asString())) {
            throw new DomainException(t("Not an Obliterate Harness archive: expected \"format\": \"", "Não é um arquivo do Obliterate Harness: esperado \"format\": \"")
                    + FORMAT + "\"" + (format == null ? "" : t(", found \"", ", veio \"") + format.asString() + "\""));
        }
        JsonNode version = tree.get("version");
        if (version == null || !version.isIntegralNumber()) {
            throw new DomainException(t("The archive has no version (\"version\": 1)", "O arquivo não tem versão (\"version\": 1)"));
        }
        if (version.asInt() > VERSION) {
            throw new DomainException(t("Archive version ", "Versão de arquivo ") + version.asInt() + t(" is newer than this platform supports (",
                    " é mais nova do que esta plataforma suporta (") + VERSION + t("). Update the platform.", "). Atualize a plataforma."));
        }
        if (version.asInt() < 1) throw new DomainException(t("Invalid archive version: ", "Versão de arquivo inválida: ") + version.asInt());
        try {
            return mapper.treeToValue(tree, Archive.class);
        } catch (JacksonException e) {
            throw new DomainException(t("The archive does not follow format version 1: ", "O arquivo não segue o formato versão 1: ") + e.getOriginalMessage());
        }
    }

    /** Planned work for one project of the archive. */
    private record Plan(ProjectEntry entry, String newId, String name, String context, boolean blueprint) {}

    /** A custom context to recreate: its row is written after the graph transaction. */
    private record NewContext(ContextEntry entry, String newId, Plan blueprint) {}

    public ImportResult importArchive(Archive a, ImportOptions options, Actor actor) {
        ImportOptions opt = options == null ? new ImportOptions(null, null) : options;
        if (a.projects() == null || a.projects().isEmpty()) {
            if (a.contexts() == null || a.contexts().stream().noneMatch(c -> "custom".equals(c.kind()))) {
                throw new DomainException(t("The archive has no projects nor custom contexts to import", "O arquivo não tem projetos nem contextos customizados para importar"));
            }
        }
        if (opt.name() != null && !opt.name().isBlank() && a.projects() != null && a.projects().size() > 1) {
            throw new DomainException(t("'name' only applies to archives with one project", "'name' só vale para arquivos com um projeto"));
        }
        List<ProjectEntry> entries = a.projects() == null ? List.of() : a.projects();
        for (var e : entries) validate(e, t("project", "projeto"));

        // ---- contexts: which exist, which to recreate
        List<String> notes = new ArrayList<>();
        List<ImportedContext> ctxOut = new ArrayList<>();
        Map<String, String> ctxMap = new HashMap<>();
        List<NewContext> newContexts = new ArrayList<>();
        var known = contexts.list(I18n.lang());
        Set<String> takenNames = new HashSet<>();
        for (var p : projects.list(true)) takenNames.add(p.name().toLowerCase(Locale.ROOT));
        for (var c : a.contexts() == null ? List.<ContextEntry>of() : a.contexts()) {
            if (c.id() == null) throw new DomainException(t("A context in the archive has no id", "Um contexto do arquivo não tem id"));
            var byId = known.stream().filter(k -> k.id().equals(c.id())).findFirst();
            if (byId.isPresent()) {
                ctxMap.put(c.id(), c.id());
                ctxOut.add(new ImportedContext(c.id(), c.id(), byId.get().name(), "existing"));
                continue;
            }
            if ("builtin".equals(c.kind())) continue; // reported below, if a project needs it
            var byName = known.stream().filter(k -> "custom".equals(k.kind()) && c.name() != null && k.name().equalsIgnoreCase(c.name())).findFirst();
            if (byName.isPresent()) {
                ctxMap.put(c.id(), byName.get().id());
                ctxOut.add(new ImportedContext(c.id(), byName.get().id(), byName.get().name(), "matched"));
                notes.add(t("Context '", "Contexto '") + c.name() + t("' already exists here (same name): the projects use it, its definition was not changed.",
                        "' já existe aqui (mesmo nome): os projetos usam ele, a definição não foi alterada."));
                continue;
            }
            if (!opt.recreate()) continue;
            if (c.name() == null || c.name().isBlank()) throw new DomainException(t("Context ", "Contexto ") + c.id() + t(" has no name", " não tem nome"));
            if (c.definition() == null) {
                throw new DomainException(t("Context '", "Contexto '") + c.name() + t("' has no definition in the archive: it cannot be recreated",
                        "' não tem definição no arquivo: não dá para recriar"));
            }
            validate(c.definition(), t("definition of context ", "definição do contexto ") + c.name());
            String newId = Ids.next("ctx");
            ctxMap.put(c.id(), newId);
            String bpName = unique(c.name().trim() + t(" · definition", " · definição"), takenNames);
            newContexts.add(new NewContext(c, newId, new Plan(c.definition(), Ids.next("p"), bpName, newId, true)));
            ctxOut.add(new ImportedContext(c.id(), newId, c.name().trim(), "created"));
        }

        // ---- projects
        List<Plan> plans = new ArrayList<>();
        for (var e : entries) {
            String ctx = e.context();
            String target = null;
            if (ctx != null) {
                target = ctxMap.get(ctx);
                if (target == null) {
                    String name = a.contexts() == null ? ctx : a.contexts().stream().filter(c -> ctx.equals(c.id())).map(ContextEntry::name).findFirst().orElse(ctx);
                    throw new DomainException(t("Project '", "Projeto '") + e.name() + t("' belongs to context '", "' pertence ao contexto '") + name
                            + (opt.recreate() ? t("', which does not exist here and is not defined in the archive.", "', que não existe aqui e não está definido no arquivo.")
                            : t("', which does not exist here. Import with recreateContexts=true to recreate it from the archive.",
                            "', que não existe aqui. Importe com recreateContexts=true para recriá-lo a partir do arquivo.")));
                }
            }
            String wanted = opt.name() != null && !opt.name().isBlank() ? opt.name().trim() : e.name().trim();
            String name = unique(wanted, takenNames);
            if (!name.equals(wanted)) {
                notes.add(t("A project named '", "Já existe um projeto chamado '") + wanted + t("' already exists: imported as '", "': importado como '") + name + "'.");
            }
            plans.add(new Plan(e, Ids.next("p"), name, target, false));
        }

        // ---- graph: everything in one transaction
        List<Plan> all = new ArrayList<>();
        newContexts.forEach(n -> all.add(n.blueprint()));
        all.addAll(plans);
        for (Plan p : all) requireNarrativeWhereUsed(p, newContexts);
        Map<String, String> projectIds = new HashMap<>();
        all.forEach(p -> projectIds.put(p.entry().id(), p.newId()));
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        String now = Instant.now().toString();
        graph.write(tx -> {
            for (Plan p : all) counts.put(p.newId(), writeProject(tx, p, projectIds, now));
            return null;
        });

        // ---- relational side: context rows and audit; undo the graph if it fails
        try {
            for (var n : newContexts) {
                var c = n.entry();
                contextRepo.insert(new ContextRepository.Row(n.newId(), c.name().trim(), c.description(), c.icon() == null ? "🧩" : c.icon(), c.color(),
                        Lang.parse(c.locale(), Lang.parse(c.definition().locale(), Lang.en)).name(), n.blueprint().newId(),
                        c.baseContext() != null && contexts.list(I18n.lang()).stream().anyMatch(k -> k.id().equals(c.baseContext())) ? c.baseContext() : null,
                        c.instructions(), c.spaceLabels(), c.tabs()));
            }
        } catch (RuntimeException e) {
            for (var n : newContexts) {
                try {
                    contextRepo.delete(n.newId());
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            for (Plan p : all) {
                try {
                    projects.deleteGraph(p.newId());
                    projectData.deleteProject(p.newId());
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            throw e;
        }
        List<ImportedProject> out = new ArrayList<>();
        for (Plan p : all) contexts.dropStructureIfNotNarrative(p.newId());
        for (Plan p : all) {
            audit.record(p.newId(), null, actor.name(), List.of(new ChangeExecutor.OpResult(0, "import", "project", p.newId(), null,
                    Map.of("sourceId", p.entry().id(), "archiveExportedAt", String.valueOf(a.exportedAt())), Map.of("counts", counts.get(p.newId())))));
            if (!p.blueprint()) out.add(new ImportedProject(p.entry().id(), p.newId(), p.name(), p.context(), counts.get(p.newId())));
        }
        return new ImportResult(out, ctxOut, notes);
    }

    /**
     * The narrative engine's building blocks (base kinds Unit/Device, relation bases STRUCTURE/SETUP_PAYOFF, the story_time
     * data type) only exist in the narrative context and contexts based on it: a project that uses them cannot land in
     * another context. Checked before anything is written.
     */
    private void requireNarrativeWhereUsed(Plan plan, List<NewContext> newContexts) {
        String used = narrativePiece(plan.entry().schema());
        if (used == null || plan.context() == null) return; // no context = narrative (projects from before contexts)
        boolean narrative = newContexts.stream().filter(n -> n.newId().equals(plan.context())).findFirst()
                .map(n -> contexts.narrativeContext(n.entry().baseContext()))
                .orElseGet(() -> contexts.narrativeContext(plan.context()));
        if (narrative) return;
        throw new DomainException(t("Project '", "Projeto '") + plan.entry().name() + t("' uses ", "' usa ") + used
                + t(", which belongs to the narrative engine, but its context is not narrative. The narrative engine only exists in the narrative "
                        + "context (or a context based on it): import the project into one of those.",
                ", que faz parte do motor narrativo, mas o contexto dele não é narrativo. O motor narrativo só existe no contexto narrativo "
                        + "(ou num contexto baseado nele): importe o projeto num deles."));
    }

    /** The first narrative-only piece of a schema (e.g. "base kind 'Unit' (Chapter)"), or null when there is none. */
    private static String narrativePiece(Schema s) {
        if (s == null) return null;
        for (NodeTypeDef nt : s.nodeTypes() == null ? List.<NodeTypeDef>of() : s.nodeTypes()) {
            if ("Unit".equalsIgnoreCase(nt.base()) || "Device".equalsIgnoreCase(nt.base())) {
                return t("base kind '", "tipo-base '") + nt.base() + "' (" + nt.name() + ")";
            }
            if (usesStoryTime(nt.attributes())) return "dataType 'story_time' (" + nt.name() + ")";
        }
        for (RelTypeDef rt : s.relTypes() == null ? List.<RelTypeDef>of() : s.relTypes()) {
            if ("STRUCTURE".equalsIgnoreCase(rt.base()) || "SETUP_PAYOFF".equalsIgnoreCase(rt.base())) {
                return t("relation base '", "base de relação '") + rt.base() + "' (" + rt.name() + ")";
            }
        }
        for (ObjectTypeDef ot : s.objectTypes() == null ? List.<ObjectTypeDef>of() : s.objectTypes()) {
            if (usesStoryTime(ot.fields())) return "dataType 'story_time' (" + ot.name() + ")";
        }
        return null;
    }

    private static boolean usesStoryTime(List<AttrDef> attrs) {
        return attrs != null && attrs.stream().anyMatch(a -> "story_time".equalsIgnoreCase(a.dataType()) || "story_time".equalsIgnoreCase(a.itemType()));
    }

    /** Checks a project entry before writing anything, so errors name what is wrong. */
    private void validate(ProjectEntry e, String what) {
        if (e.id() == null || e.name() == null || e.name().isBlank()) {
            throw new DomainException(t("A ", "Um ") + what + t(" in the archive has no id or name", " do arquivo não tem id ou nome"));
        }
        String who = what + " '" + e.name() + "': ";
        Set<String> typeIds = new HashSet<>(), relIds = new HashSet<>(), nodeIds = new HashSet<>();
        if (e.schema() != null) {
            for (var nt : list(e.schema().nodeTypes())) {
                if (nt.id() == null || nt.name() == null) throw new DomainException(who + t("node type without id or name", "tipo de nó sem id ou nome"));
                nt.baseKind();
                typeIds.add(nt.id());
            }
            for (var rt : list(e.schema().relTypes())) {
                if (rt.id() == null || rt.name() == null) throw new DomainException(who + t("relation type without id or name", "tipo de relação sem id ou nome"));
                rt.relBase();
                relIds.add(rt.id());
            }
        }
        for (var n : list(e.nodes())) {
            if (n.id() == null || n.name() == null) throw new DomainException(who + t("node without id or name", "nó sem id ou nome"));
            if (!nodeIds.add(n.id())) throw new DomainException(who + t("repeated node id ", "id de nó repetido ") + n.id());
            if (!typeIds.contains(n.typeId())) {
                throw new DomainException(who + t("node '", "nó '") + n.name() + t("' has type ", "' tem tipo ") + n.typeId()
                        + t(", which is not in the archive's schema", ", que não está no schema do arquivo"));
            }
        }
        for (var ed : list(e.edges())) {
            if (!nodeIds.contains(ed.from()) || !nodeIds.contains(ed.to())) {
                throw new DomainException(who + t("edge ", "aresta ") + ed.id() + t(" points to a node that is not in the archive", " aponta para um nó que não está no arquivo"));
            }
            if ("rel".equals(ed.kind() == null ? "rel" : ed.kind()) && !relIds.contains(ed.relTypeId())) {
                throw new DomainException(who + t("edge ", "aresta ") + ed.id() + t(" has relation type ", " tem tipo de relação ") + ed.relTypeId()
                        + t(", which is not in the archive's schema", ", que não está no schema do arquivo"));
            }
        }
        for (var f : list(e.facts())) {
            if (f.text() == null) throw new DomainException(who + t("fact without text", "fato sem texto") + " (" + f.id() + ")");
        }
        for (var d : list(e.directives())) {
            if (d.text() == null) throw new DomainException(who + t("directive without text", "diretiva sem texto") + " (" + d.id() + ")");
        }
    }

    /** Writes one project with new ids. Returns the counts written. */
    private Map<String, Integer> writeProject(TransactionContext tx, Plan plan, Map<String, String> projectIds, String now) {
        ProjectEntry e = plan.entry();
        String pid = plan.newId();
        Settings st = e.settings() == null ? new Settings(null, null, null) : e.settings();
        String styleRef = st.styleReference() == null ? null : projectIds.get(st.styleReference());
        tx.run("""
                CREATE (p:Project {id:$id, name:$name, description:$description, profile:$profile, requireReviewForLlm:$review,
                                   createdAt:$now, locale:$locale, styleSample:$style, styleReference:$ref, context:$context, blueprint:$blueprint,
                                   importedFrom:$from, importedAt:$now})
                CREATE (t:RelType {id:$rt, projectId:$id, name:'CONTAINS', nameLower:'contains', base:'STRUCTURE',
                                   description:'Narrative structure (parent contains child). System relation.',
                                   symmetric:false, temporal:false, allowed:'[{"from":"@Unit","to":"@Unit"}]'})
                """, params("id", pid, "name", plan.name(), "description", e.description(), "profile", e.profile(),
                "review", Boolean.TRUE.equals(st.requireReviewForLlm()), "now", now, "locale", Lang.parse(e.locale(), Lang.en).name(),
                "style", st.styleSample(), "ref", styleRef, "context", plan.context(), "blueprint", plan.blueprint() ? Boolean.TRUE : null,
                "from", e.id(), "rt", "rt_contains_" + pid)).consume();

        // ---- schema
        Schema sc = e.schema() == null ? new Schema(List.of(), List.of(), List.of()) : e.schema();
        Map<String, NodeTypeDef> types = new HashMap<>();
        for (var nt : list(sc.nodeTypes())) {
            var copy = new NodeTypeDef(Ids.next("nt"), pid, nt.name(), nt.base(), nt.description(), nt.color(), nt.strict(), nt.attributes(), nt.systemKey());
            schema.saveNodeType(tx, copy);
            types.put(nt.id(), copy);
        }
        Map<String, RelTypeDef> rels = new HashMap<>();
        for (var rt : list(sc.relTypes())) {
            var copy = new RelTypeDef(Ids.next("rt"), pid, rt.name(), rt.base(), rt.description(), rt.symmetric(), rt.inverseName(), rt.temporal(),
                    rt.allowed(), rt.systemKey());
            schema.saveRelType(tx, copy);
            rels.put(rt.id(), copy);
        }
        for (var ot : list(sc.objectTypes())) schema.saveObjectType(tx, new ObjectTypeDef(Ids.next("ot"), pid, ot.name(), ot.description(), ot.fields()));

        // ---- nodes
        Map<String, String> ids = new HashMap<>();
        for (var n : list(e.nodes())) ids.put(n.id(), Ids.next("n"));
        for (var n : list(e.nodes())) {
            NodeTypeDef type = types.get(n.typeId());
            Map<String, Object> props = new HashMap<>(n.extra() == null ? Map.of() : n.extra());
            props.keySet().removeAll(NODE_KEYS);
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = n.attributes() == null ? new LinkedHashMap<>() : (Map<String, Object>) remap(n.attributes(), ids);
            props.put("id", ids.get(n.id()));
            props.put("projectId", pid);
            props.put("typeId", type.id());
            props.put("typeName", type.name());
            props.put("base", type.baseKind().name());
            // old space names (world, characters) become the current ones; unknown or missing → the base's default
            var space = com.obliterateharness.domain.Space.of(n.space());
            props.put("space", (space != null ? space : com.obliterateharness.domain.Space.defaultFor(type.baseKind())).name());
            props.put("name", n.name());
            props.put("summary", n.summary());
            props.put("description", n.description());
            props.put("tags", n.tags() == null ? List.of() : n.tags());
            props.put("criticality", n.criticality() == null ? "normal" : n.criticality());
            props.put("attrs", mapper.writeValueAsString(attrs));
            props.put("attrMeta", mapper.writeValueAsString(n.attributeCriticality() == null ? Map.of() : n.attributeCriticality()));
            props.put("attrsText", attrsText(attrs));
            props.put("order", n.order());
            props.put("version", n.version() == null ? 1L : n.version());
            props.put("createdAt", n.createdAt() == null ? now : n.createdAt());
            props.put("updatedAt", n.updatedAt() == null ? now : n.updatedAt());
            props.put("createdBy", n.createdBy() == null ? Actor.system.name() : n.createdBy());
            props.put("updatedBy", n.updatedBy() == null ? Actor.system.name() : n.updatedBy());
            props.values().removeIf(java.util.Objects::isNull);
            tx.run("CREATE (n:N:" + BaseKind.parse(type.base()).name() + ") SET n = $props", Map.of("props", props)).consume();
        }

        // ---- edges
        Map<String, String> edgeIds = new HashMap<>();
        int droppedAnchors = 0;
        for (var ed : list(e.edges())) {
            String a = ids.get(ed.from()), b = ids.get(ed.to());
            String kind = ed.kind() == null ? "rel" : ed.kind();
            String nid = switch (kind) {
                case "structure" -> "e_s_" + b;
                case "attr" -> "e_" + a + "_" + ed.attrKey() + "_" + b;
                default -> Ids.next("e");
            };
            edgeIds.put(ed.id(), nid);
            Map<String, Object> props = new HashMap<>(ed.extra() == null ? Map.of() : ed.extra());
            props.keySet().removeAll(EDGE_KEYS);
            props.put("id", nid);
            props.put("projectId", pid);
            props.put("kind", kind);
            RelTypeDef rt = ed.relTypeId() == null ? null : rels.get(ed.relTypeId());
            props.put("relTypeId", rt == null ? null : rt.id());
            props.put("name", rt != null ? rt.name() : ed.relType());
            props.put("base", rt != null ? rt.relBase().name() : ed.base());
            props.put("attrKey", ed.attrKey());
            props.put("description", ed.description());
            props.put("criticality", ed.criticality() == null ? "normal" : ed.criticality());
            props.put("tags", ed.tags() == null ? List.of() : ed.tags());
            Map<String, String> anchors = new HashMap<>();
            anchors.put("fromUnit", ed.fromUnit());
            anchors.put("toUnit", ed.toUnit());
            for (var anchor : anchors.entrySet()) {
                if (anchor.getValue() == null) continue;
                if (ids.containsKey(anchor.getValue())) props.put(anchor.getKey(), ids.get(anchor.getValue()));
                else droppedAnchors++;
            }
            props.put("version", ed.version() == null ? 1L : ed.version());
            props.put("createdAt", ed.createdAt() == null ? now : ed.createdAt());
            props.put("updatedAt", ed.updatedAt());
            props.put("createdBy", ed.createdBy());
            props.values().removeIf(java.util.Objects::isNull);
            tx.run("MATCH (a:N {id:$a}), (b:N {id:$b}) CREATE (a)-[r:R]->(b) SET r = $props", params("a", a, "b", b, "props", props)).consume();
        }

        // ---- facts (mirrored ones follow their origin's new id)
        int factCount = 0;
        for (var f : list(e.facts())) {
            String kind = f.originKind() == null ? "manual" : f.originKind();
            String origin = f.originId();
            String nid;
            String newOrigin = origin;
            if ("manual".equals(kind)) {
                nid = Ids.next("f");
            } else if ("edge".equals(kind)) {
                if (!edgeIds.containsKey(origin)) continue;
                newOrigin = edgeIds.get(origin);
                nid = "f_" + newOrigin;
            } else {
                if (!ids.containsKey(origin)) continue;
                newOrigin = ids.get(origin);
                nid = "f_" + newOrigin + "__" + ("attr".equals(kind) ? f.originKey() : "node");
            }
            Map<String, Object> props = new HashMap<>(f.extra() == null ? Map.of() : f.extra());
            props.keySet().removeAll(FACT_KEYS);
            props.put("id", nid);
            props.put("projectId", pid);
            props.put("text", f.text());
            props.put("rigidity", f.rigidity());
            String crit = f.criticality() == null ? "normal" : f.criticality();
            props.put("criticality", crit);
            props.put("originKind", kind);
            props.put("originId", newOrigin);
            props.put("originKey", f.originKey());
            if (f.revealAt() != null && ids.containsKey(f.revealAt())) props.put("revealAt", ids.get(f.revealAt()));
            props.put("version", f.version() == null ? 1L : f.version());
            props.put("createdAt", f.createdAt() == null ? now : f.createdAt());
            props.put("updatedAt", f.updatedAt() == null ? now : f.updatedAt());
            props.put("createdBy", f.createdBy());
            props.values().removeIf(java.util.Objects::isNull);
            String extra = "foundational".equals(crit) ? ":Foundational" : "critical".equals(crit) ? ":Critical" : "";
            List<String> about = list(f.about()).stream().filter(ids::containsKey).map(ids::get).toList();
            tx.run("CREATE (f:Fact" + extra + ") SET f = $props WITH f UNWIND $about AS nid MATCH (n:N {id:nid}) MERGE (f)-[:ABOUT]->(n)",
                    params("props", props, "about", about)).consume();
            factCount++;
        }

        // ---- directives
        for (var d : list(e.directives())) {
            Map<String, Object> props = new HashMap<>(d.extra() == null ? Map.of() : d.extra());
            props.keySet().removeAll(DIRECTIVE_KEYS);
            props.put("id", Ids.next("dr"));
            props.put("projectId", pid);
            props.put("text", d.text());
            props.put("when", d.when() == null ? List.of("always") : d.when());
            props.put("bases", d.bases() == null ? List.of() : d.bases());
            props.put("types", d.types() == null ? List.of() : d.types());
            props.put("priority", d.priority() == null ? 0 : d.priority());
            props.put("enabled", d.enabled() == null || d.enabled());
            props.put("version", d.version() == null ? 1L : d.version());
            props.put("createdAt", d.createdAt() == null ? now : d.createdAt());
            props.put("updatedAt", d.updatedAt() == null ? now : d.updatedAt());
            tx.run("CREATE (d:Directive) SET d = $props", Map.of("props", props)).consume();
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("nodeTypes", types.size());
        counts.put("relTypes", rels.size());
        counts.put("objectTypes", list(sc.objectTypes()).size());
        counts.put("nodes", ids.size());
        counts.put("edges", edgeIds.size());
        counts.put("facts", factCount);
        counts.put("directives", list(e.directives()).size());
        if (droppedAnchors > 0) counts.put("droppedAnchors", droppedAnchors);
        return counts;
    }

    // ------------------------------------------------------------------ helpers

    /** Replaces node ids of the archive by the new ones anywhere inside an attribute value (node_ref, lists, composites). */
    private static Object remap(Object v, Map<String, String> ids) {
        if (v instanceof String s) return ids.getOrDefault(s, s);
        if (v instanceof List<?> l) return l.stream().map(x -> remap(x, ids)).toList();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), remap(x, ids)));
            return out;
        }
        return v;
    }

    /** Same text index ChangeExecutor keeps for searching attributes. */
    private static String attrsText(Map<String, Object> attrs) {
        StringBuilder sb = new StringBuilder();
        attrs.forEach((k, v) -> {
            if (v instanceof String || v instanceof Number || v instanceof Boolean) sb.append(k).append(": ").append(v).append("; ");
        });
        return sb.toString();
    }

    private static String unique(String name, Set<String> taken) {
        String out = name;
        for (int i = 2; taken.contains(out.toLowerCase(Locale.ROOT)); i++) out = name + " (" + i + ")";
        taken.add(out.toLowerCase(Locale.ROOT));
        return out;
    }

    private static String slug(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (n.length() > 48) n = n.substring(0, 48).replaceAll("-$", "");
        return n.isEmpty() ? "project" : n;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.readValue(json, LinkedHashMap.class);
        return m;
    }

    private static Map<String, String> stringMap(Map<String, Object> m) {
        if (m.isEmpty()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, String.valueOf(v)));
        return out;
    }

    private static Map<String, Object> extra(Map<String, Object> props, Set<String> known) {
        Map<String, Object> out = new LinkedHashMap<>();
        props.forEach((k, v) -> {
            if (!known.contains(k) && v != null) out.put(k, v);
        });
        return out.isEmpty() ? null : out;
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static Long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static List<String> strings(Object v) {
        if (!(v instanceof List<?> l)) return null;
        return l.stream().map(String::valueOf).toList();
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }
}
