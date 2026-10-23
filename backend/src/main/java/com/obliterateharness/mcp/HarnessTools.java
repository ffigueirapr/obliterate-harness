package com.obliterateharness.mcp;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Operation;
import com.obliterateharness.domain.Views;
import com.obliterateharness.domain.Views.Detail;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.GraphReader;
import com.obliterateharness.graph.GraphReader.NodeFilter;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.harness.ArchiveService;
import com.obliterateharness.harness.CloneService;
import com.obliterateharness.harness.ContextService;
import com.obliterateharness.harness.ExportService;
import com.obliterateharness.harness.GuidanceService;
import com.obliterateharness.harness.HarnessLibrary;
import com.obliterateharness.harness.LocalizationService;
import com.obliterateharness.harness.NarrativeService;
import com.obliterateharness.harness.ProfileService;
import com.obliterateharness.harness.TemplateService;
import com.obliterateharness.harness.TextDiff;
import com.obliterateharness.harness.UnitService;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ChangesetService;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.service.SessionService;
import com.obliterateharness.store.ChangesetRepository;
import com.obliterateharness.store.SessionRepository;
import com.obliterateharness.store.TextVersionRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * MCP tools for the LLM. High-level, compact output. Every write goes through a changeset (audited).
 * Each project tool answers in the project's language (its schema and content language).
 */
@Component
public class HarnessTools {

    public record NodeGetResult(List<Views.Node> nodes, List<Views.Edge> edges, List<Views.Fact> facts) {}

    public record ChangesetStatus(String id, String status, String title, String error, Object changes) {}

    /** @param downloadUrl for binary formats (docx, epub) and large outputs: fetch the file from the platform */
    public record ExportResult(String format, String filename, long words, int sections, String content, String downloadUrl) {}

    private final ProjectService projects;
    private final SessionService sessions;
    private final ChangesetService changesets;
    private final Graph graph;
    private final GraphReader reader;
    private final SchemaStore schema;
    private final HarnessLibrary library;
    private final ProfileService profiles;
    private final TemplateService templates;
    private final NarrativeService narrative;
    private final GuidanceService guidance;
    private final UnitService units;
    private final ExportService export;
    private final CloneService cloner;
    private final LocalizationService localization;
    private final ContextService contexts;
    private final ArchiveService archives;
    private final String publicUrl;

    public HarnessTools(ProjectService projects, SessionService sessions, ChangesetService changesets, Graph graph, GraphReader reader,
                        SchemaStore schema, HarnessLibrary library, ProfileService profiles, TemplateService templates, NarrativeService narrative,
                        GuidanceService guidance, UnitService units, ExportService export, CloneService cloner, LocalizationService localization,
                        ContextService contexts, ArchiveService archives,
                        @Value("${harness.public-url:http://localhost:8080}") String publicUrl) {
        this.projects = projects;
        this.sessions = sessions;
        this.changesets = changesets;
        this.graph = graph;
        this.reader = reader;
        this.schema = schema;
        this.library = library;
        this.profiles = profiles;
        this.templates = templates;
        this.narrative = narrative;
        this.guidance = guidance;
        this.units = units;
        this.export = export;
        this.cloner = cloner;
        this.localization = localization;
        this.contexts = contexts;
        this.archives = archives;
        this.publicUrl = publicUrl;
    }

    /** Runs a project tool in the project's language. */
    private <T> T in(String projectId, Supplier<T> work) {
        return I18n.with(projects.locale(projectId), work);
    }

    // ------------------------------------------------------------------ projects and sessions

    @McpTool(name = "project_list", description = "Lists the projects on the platform, with their context (domain) and language (locale). "
            + "Filter by contextId to see the projects of one context (see context_list).")
    public List<Views.Project> projectList(
            @McpToolParam(description = "Only projects of this context (e.g. narrative, corporate, ctx_...)", required = false) String contextId) {
        if (contextId == null || contextId.isBlank()) return projects.list();
        return projects.inContext(contextId);
    }

    // ------------------------------------------------------------------ contexts (domains)

    @McpTool(name = "context_list", description = "Lists the contexts (domains) this platform runs: built-in ones (narrative: stories and worlds; "
            + "corporate: a company's brain) and the ones created by people or LLMs. A context defines which node types, relations, rules and "
            + "directives its projects have. Start here to find where the information you need lives, then project_list(contextId).")
    public List<ContextService.ContextView> contextList() {
        return contexts.list(I18n.lang());
    }

    @McpTool(name = "context_get", description = "Details of one context: description, instructions for the AI, presets, tabs, and for custom contexts "
            + "the blueprintProjectId whose schema IS the context's definition (read it with schema_describe).")
    public ContextService.ContextView contextGet(@McpToolParam(description = "Context id") String contextId) {
        return contexts.get(contextId, I18n.lang());
    }

    @McpTool(name = "context_create", description = "Creates a new context (domain), e.g. a company's brain for a specific area, a research "
            + "knowledge base, a game world. Only create one if the user asked. It returns blueprintProjectId: DEFINE the context by editing that "
            + "project's schema with propose_changes (upsert_node_type, upsert_rel_type with allowed pairs, create_fact for rules, "
            + "upsert_directive for how the AI must behave). Every project later created in the context starts with that definition. "
            + "Start from a base (baseContext: corporate, narrative or another context id) to copy its types and adapt them, or leave it empty "
            + "to design from scratch.")
    public ContextService.ContextView contextCreate(
            @McpToolParam(description = "Context name") String name,
            @McpToolParam(description = "What the context is for", required = false) String description,
            @McpToolParam(description = "Emoji icon", required = false) String icon,
            @McpToolParam(description = "Language of the schema: en (default) or pt", required = false) String locale,
            @McpToolParam(description = "Context to start from: corporate, narrative or a custom context id; empty = blank", required = false) String baseContext,
            @McpToolParam(description = "For narrative bases: which preset (livro, manga, filme_serie...)", required = false) String basePreset,
            @McpToolParam(description = "Instructions every AI working in this context receives in its briefing", required = false) String instructions) {
        return contexts.create(name, description, icon, null, locale, baseContext, basePreset, instructions, Actor.llm);
    }

    @McpTool(name = "context_update", description = "Changes a custom context's settings: name, description, icon, instructions for the AI, "
            + "tabs (graph spaces shown: world, characters, story, analysis, templates) and the label of each space. "
            + "To change its node types and relations, edit the blueprint project's schema with propose_changes.")
    public ContextService.ContextView contextUpdate(
            @McpToolParam(description = "Context id") String contextId,
            @McpToolParam(description = "New name", required = false) String name,
            @McpToolParam(description = "New description", required = false) String description,
            @McpToolParam(description = "Emoji icon", required = false) String icon,
            @McpToolParam(description = "Instructions for the AI", required = false) String instructions,
            @McpToolParam(description = "Tabs in order, e.g. [characters, world, analysis]", required = false) List<String> tabs,
            @McpToolParam(description = "Label per space, e.g. characters -> People and accounts", required = false) Map<String, String> spaceLabels) {
        return contexts.update(contextId, name, description, icon, null, instructions, spaceLabels, tabs, I18n.lang());
    }

    @McpTool(name = "project_create", description = "Creates a new project inside a context (contextId; see context_list): it is born with the "
            + "context's node types, relations, rules and directives. For the narrative context, profile picks the preset (livro, manga...). "
            + "Only create one if the user asked; usually the project already exists (see project_list).")
    public Views.Project projectCreate(
            @McpToolParam(description = "Project name") String name,
            @McpToolParam(description = "Short description", required = false) String description,
            @McpToolParam(description = "Context id: narrative, corporate or a custom one (see context_list)") String contextId,
            @McpToolParam(description = "Preset/profile inside the context (narrative: livro, light_novel, manga, visual_novel, jogo, rpg_mesa, filme_serie, publicidade); default: the context's default", required = false) String profile,
            @McpToolParam(description = "Language of the schema and content: en (default) or pt", required = false) String locale) {
        if (contextId == null || contextId.isBlank()) {
            throw new DomainException(I18n.t("contextId is required: pick one with context_list (or create one with context_create)",
                    "contextId é obrigatório: escolha um com context_list (ou crie com context_create)"));
        }
        Lang lang = Lang.parse(locale, Lang.en);
        return I18n.with(lang, () -> contexts.createProject(contextId, name, description, lang.name(), profile, null, Actor.llm));
    }

    @McpTool(name = "project_clone", description = "Copies a whole project (schema, nodes, edges with time anchors, facts, directives, text history) "
            + "with new ids and returns the old→new id map. Optionally translates the copy's schema (translateTo: en|pt) — types, attributes, enums, "
            + "relations, templates; names/summaries/texts stay as they are for you to translate.")
    public CloneService.Result projectClone(
            @McpToolParam(description = "Id of the project to copy") String projectId,
            @McpToolParam(description = "Name of the copy") String newName,
            @McpToolParam(description = "Copy narrative units (chapters, scenes...); default true", required = false) Boolean includeUnits,
            @McpToolParam(description = "Copy manual facts; default true", required = false) Boolean includeFacts,
            @McpToolParam(description = "Copy the project's directives; default true", required = false) Boolean includeDirectives,
            @McpToolParam(description = "Copy applied templates and beats; default true", required = false) Boolean includeTemplates,
            @McpToolParam(description = "Translate the copy's schema to en or pt", required = false) String translateTo) {
        return in(projectId, () -> cloner.cloneProject(projectId, newName, new CloneService.Options(includeUnits == null || includeUnits,
                includeFacts == null || includeFacts, includeDirectives == null || includeDirectives, includeTemplates == null || includeTemplates), translateTo));
    }

    @McpTool(name = "project_localize", description = "Converts a project's schema to another language (en|pt): types, attribute keys and labels, enum values, "
            + "relations, object types, applied templates and beats. Data is migrated with it; user-made types are kept. Content is not translated.")
    public LocalizationService.Report projectLocalize(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Target language: en or pt") String locale) {
        Lang target = Lang.parse(locale, null);
        if (target == null) throw new com.obliterateharness.domain.DomainException("locale must be en or pt");
        return I18n.with(target, () -> localization.localize(projectId, target));
    }

    @McpTool(name = "project_update", description = "Renames a project and/or changes its description. Only do it if the user asked. "
            + "The name must not be empty nor repeat another project's name.")
    public Views.Project projectUpdate(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "New name", required = false) String name,
            @McpToolParam(description = "New description", required = false) String description) {
        return in(projectId, () -> projects.update(projectId, name, description, null, null));
    }

    /**
     * @param archive     the obliterate-archive JSON, when it is small enough to come inline
     * @param downloadUrl always: GET it to have the whole file (also what to keep as a backup)
     */
    public record ArchiveExportResult(String format, int version, String scope, String filename, int bytes, List<ArchiveSummary> projects,
                                      List<String> contexts, String archive, String downloadUrl, String note) {}

    public record ArchiveSummary(String id, String name, String context, Map<String, Integer> counts) {}

    @McpTool(name = "project_export", description = "Exports one project (projectId) or every project (all=true) to an obliterate-archive JSON "
            + "(versioned format: contexts with their definition, project metadata and settings, schema, nodes, edges, facts, directives; ids preserved). "
            + "For backup or migration to another instance. Returns a summary with counts and a downloadUrl; the archive itself comes inline "
            + "only when it is small (up to ~200 KB) and inline is not false. Re-import with project_import.")
    public ArchiveExportResult projectExport(
            @McpToolParam(description = "Project id (omit with all=true)", required = false) String projectId,
            @McpToolParam(description = "Export every project and custom context", required = false) Boolean all,
            @McpToolParam(description = "Return the archive inline when small (default true); false = summary and URL only", required = false) Boolean inline) {
        boolean everything = Boolean.TRUE.equals(all);
        if (!everything && (projectId == null || projectId.isBlank())) {
            throw new DomainException(I18n.t("Pass projectId, or all=true", "Informe projectId, ou all=true"));
        }
        var a = everything ? archives.exportAll() : in(projectId, () -> archives.exportProject(projectId));
        String text = archives.toJson(a);
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        boolean give = (inline == null || inline) && bytes <= 200_000;
        String url = publicUrl + (everything ? "/api/export" : "/api/projects/" + projectId + "/export");
        return new ArchiveExportResult(a.format(), a.version(), a.scope(), ArchiveService.filename(a), bytes,
                a.projects().stream().map(p -> new ArchiveSummary(p.id(), p.name(), p.context(), p.counts())).toList(),
                a.contexts().stream().map(c -> c.id() + " (" + c.kind() + ")").toList(), give ? text : null, url,
                give ? null : I18n.t("Archive too large to return inline: download it from downloadUrl.",
                        "Arquivo grande demais para vir inline: baixe de downloadUrl."));
    }

    @McpTool(name = "project_import", description = "Imports an obliterate-archive JSON (from project_export or GET /api/export) as NEW projects: "
            + "new ids, every internal reference remapped. Custom contexts missing here are recreated from the archive (recreateContexts, "
            + "default true). All or nothing: an invalid archive (format/version) fails with the reason and nothing is written. "
            + "Only import if the user asked.")
    public ArchiveService.ImportResult projectImport(
            @McpToolParam(description = "The archive JSON text") String archive,
            @McpToolParam(description = "Recreate missing custom contexts from the archive; default true", required = false) Boolean recreateContexts,
            @McpToolParam(description = "New name for the project (single-project archives)", required = false) String name) {
        return archives.importArchive(archives.parse(archive), new ArchiveService.ImportOptions(recreateContexts, name), Actor.llm);
    }

    @McpTool(name = "project_settings", description = "Updates project settings used when writing: styleSample (a prose sample whose voice writing_context "
            + "passes on) and styleReference (id of another project, e.g. a dissected book, whose manuscript is used as a style reference). "
            + "Empty string clears a setting.")
    public Views.Project projectSettings(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Prose sample to keep the voice", required = false) String styleSample,
            @McpToolParam(description = "Id of a reference project for style", required = false) String styleReference,
            @McpToolParam(description = "New description", required = false) String description) {
        return in(projectId, () -> projects.update(projectId, null, description, null, null, styleSample, styleReference));
    }

    @McpTool(name = "session_begin", description = "ALWAYS call first when starting work on a project. Opens a work session and returns the briefing: "
            + "language (write in it), schema (types and attributes), stats, narrative structure, foundational facts (rules that cannot be broken), "
            + "last checkpoint, open hooks, directives and operating rules.")
    public SessionService.Briefing sessionBegin(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Goal of this session (e.g. 'write chapter 3', 'mirror the structure of <work>')") String goal) {
        return in(projectId, () -> sessions.begin(projectId, goal));
    }

    @McpTool(name = "session_resume", description = "Resumes work after losing context (compaction, new conversation). Returns the updated briefing, "
            + "the session's last checkpoint and its aliases. Use it instead of guessing what was done.")
    public SessionService.Briefing sessionResume(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Session id; empty = the project's most recent", required = false) String sessionId) {
        return in(projectId, () -> sessions.resume(projectId, sessionId));
    }

    @McpTool(name = "session_checkpoint", description = "Records the session's progress in the platform: summary of what was done, decisions and pending items. "
            + "Do it at the end of each step and before the context grows large — it is what lets you resume without hallucinating.")
    public SessionRepository.Checkpoint sessionCheckpoint(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Session id (from session_begin)") String sessionId,
            @McpToolParam(description = "Objective summary of what was done so far") String summary,
            @McpToolParam(description = "Creative/structural decisions made with the user", required = false) List<String> decisions,
            @McpToolParam(description = "Pending items and next steps", required = false) List<String> todos,
            @McpToolParam(description = "Ids of the nodes in focus now", required = false) List<String> focusNodeIds) {
        return in(projectId, () -> sessions.checkpoint(projectId, sessionId, summary, decisions, todos, focusNodeIds));
    }

    // ------------------------------------------------------------------ reading

    @McpTool(name = "schema_describe", description = "Returns the project's full schema: node types (base, typed attributes, roles, system keys), "
            + "relation types (allowed pairs) and custom object types. Check it before creating nodes/edges.")
    public Views.SchemaSummary schemaDescribe(@McpToolParam(description = "Project id") String projectId) {
        return in(projectId, () -> {
            projects.get(projectId);
            return graph.read(tx -> new Views.SchemaSummary(schema.nodeTypes(tx, projectId), schema.relTypes(tx, projectId),
                    schema.objectTypes(tx, projectId), null));
        });
    }

    @McpTool(name = "node_search", description = "Searches nodes by text (name, summary, description, attributes) with filters. "
            + "Without 'query', lists by the filters. Use before creating something to avoid duplicates.")
    public List<Views.Node> nodeSearch(
            @McpToolParam(description = "Text to search", required = false) String query,
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Filter by type names (e.g. ['Character'])", required = false) List<String> types,
            @McpToolParam(description = "Filter by base kind (Agent, Place, Object, Ability, Concept, Event, Unit, Device, Media, Note)", required = false) List<String> bases,
            @McpToolParam(description = "Filter by tags", required = false) List<String> tags,
            @McpToolParam(description = "Filter by space (world, characters, story, analysis, templates)", required = false) List<String> spaces,
            @McpToolParam(description = "critical or foundational to get only flagged nodes", required = false) String minCriticality,
            @McpToolParam(description = "Max results (default 20)", required = false) Integer limit,
            @McpToolParam(description = "id, summary (default) or full", required = false) String detail) {
        return in(projectId, () -> {
            var f = new NodeFilter(types, bases, tags, spaces, minCriticality);
            int lim = limit == null ? 20 : Math.min(limit, 200);
            return graph.read(tx -> reader.search(tx, projectId, query, f, lim, Detail.parse(detail)));
        });
    }

    @McpTool(name = "node_get", description = "Reads nodes by id with every attribute and the current version (needed to edit). "
            + "Optionally includes their edges (with versions) and the critical facts linked to them.")
    public NodeGetResult nodeGet(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Node ids") List<String> ids,
            @McpToolParam(description = "Include edges (default true)", required = false) Boolean includeEdges,
            @McpToolParam(description = "Include linked facts (default true)", required = false) Boolean includeFacts) {
        return in(projectId, () -> graph.read(tx -> new NodeGetResult(
                reader.nodes(tx, projectId, ids, Detail.full),
                Boolean.FALSE.equals(includeEdges) ? null : reader.edgesOf(tx, projectId, ids),
                Boolean.FALSE.equals(includeFacts) ? null : reader.facts(tx, projectId, ids, null, null, 100))));
    }

    @McpTool(name = "edge_get", description = "Reads edges by id, with their current version (needed to update/delete an edge), anchors and description.")
    public List<Views.Edge> edgeGet(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Edge ids") List<String> ids) {
        return in(projectId, () -> graph.read(tx -> reader.edgesById(tx, projectId, ids)));
    }

    @McpTool(name = "graph_focus", description = "Focus on a node: the node and everything connected to it, with each one's distance in hops. "
            + "Default: only direct connections. allConnected=true brings everything connected, even indirectly (long chains). "
            + "Use it to understand the context of a character, place or chapter without loading the whole project.")
    public Views.Focus graphFocus(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Id of the center node") String nodeId,
            @McpToolParam(description = "Max hops (default 1 = direct only)", required = false) Integer hops,
            @McpToolParam(description = "true = everything connected, even indirectly (ignores hops)", required = false) Boolean allConnected,
            @McpToolParam(description = "Only follow these relation types", required = false) List<String> relTypes,
            @McpToolParam(description = "Also follow structure (chapter inside work). Default: yes for direct, no for indirect", required = false) Boolean includeStructure,
            @McpToolParam(description = "Max nodes (default 100)", required = false) Integer limit) {
        return in(projectId, () -> {
            boolean all = Boolean.TRUE.equals(allConnected);
            int h = all ? GraphReader.ALL_LEVELS : (hops == null ? 1 : hops);
            boolean structure = includeStructure != null ? includeStructure : (!all && h == 1);
            return graph.read(tx -> reader.focus(tx, projectId, nodeId, h, relTypes, structure, limit == null ? 100 : Math.min(limit, 1000), Detail.summary));
        });
    }

    @McpTool(name = "graph_path", description = "Shortest path between two nodes: answers 'how does A relate to B?'.")
    public Views.Subgraph graphPath(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Id of node A") String fromId,
            @McpToolParam(description = "Id of node B") String toId,
            @McpToolParam(description = "Max hops (default 6)", required = false) Integer maxHops) {
        return in(projectId, () -> graph.read(tx -> reader.path(tx, projectId, fromId, toId, maxHops == null ? 6 : maxHops)));
    }

    @McpTool(name = "state_at", description = "State of an entity's relations at a point of the narrative: what holds (active), what already ended (ended) "
            + "and what has not started yet (notYet) at that unit. E.g. who has the key in chapter 10? Did the character already know the secret? "
            + "Use it before writing a scene with that entity.")
    public Views.StateAt stateAt(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Entity id (character, item...)") String entityId,
            @McpToolParam(description = "Narrative unit id (chapter, scene...)") String unitId) {
        return in(projectId, () -> graph.read(tx -> reader.stateAt(tx, projectId, entityId, unitId)));
    }

    @McpTool(name = "story_outline", description = "Narrative structure as a tree (work > arc > chapter > scene ...), with summaries and word counts.")
    public List<Views.OutlineItem> storyOutline(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Depth (default 3)", required = false) Integer depth) {
        return in(projectId, () -> {
            projects.get(projectId);
            return graph.read(tx -> reader.outline(tx, projectId, depth == null ? 3 : depth));
        });
    }

    @McpTool(name = "facts_list", description = "Critical/foundational facts: rules and information that must not be forgotten or broken. "
            + "Filter by the nodes in play (about) before writing or validating a scene. Facts with revealAt are secrets until that unit.")
    public List<Views.Fact> factsList(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Only facts about these nodes", required = false) List<String> about,
            @McpToolParam(description = "critical (default) or foundational", required = false) String minCriticality,
            @McpToolParam(description = "Text search in the facts", required = false) String query,
            @McpToolParam(description = "Max (default 50)", required = false) Integer limit) {
        return in(projectId, () -> {
            projects.get(projectId);
            return graph.read(tx -> reader.facts(tx, projectId, about, minCriticality == null ? "critical" : minCriticality, query,
                    limit == null ? 50 : Math.min(limit, 300)));
        });
    }

    // ------------------------------------------------------------------ writing

    @McpTool(name = "propose_changes", description = """
            The way to write to the graph. Takes a list of operations applied in order, in one transaction.
            Ops: create_node, update_node, delete_node, create_edge, update_edge, delete_edge, create_fact, update_fact, delete_fact,
            upsert_node_type, upsert_rel_type, upsert_object_type, delete_node_type, delete_rel_type, upsert_directive, delete_directive.
            - Use 'ref' ("$name") to alias what you create and reference it in later ops (from, to, parent, about, cast, node_ref attributes).
              With sessionId, aliases stay valid in later batches of the same session.
            - update/delete need 'expectedVersion' (from node_get / edge_get, or the 'version' returned for each change).
            - Units accept 'parent', 'order' and 'cast' (ids of who appears in the unit). Relations accept anchors fromUnit/toUnit.
            - Mark important rules with criticality/attributeCriticality 'critical' or 'foundational'; facts may have revealAt (secret until that unit).
            - Directives: upsert_directive with 'id' edits an existing one; definition.enabled=false turns it off without deleting.
            - dryRun=true validates everything and lists ALL errors without writing. mode=best_effort applies the valid ops and returns the invalid ones.
            - Long text (prose, script): prefer unit_text_set / unit_text_append.
            Example: [{"op":"create_node","ref":"$ana","type":"Character","name":"Ana","summary":"A repentant thief","attributes":{"age":27}},
             {"op":"create_edge","relType":"OWNS","from":"$ana","to":"n_xxxx","fromUnit":"n_ch3"}]
            If the project requires review, the result is 'pending' until the user approves it in the UI.""")
    public ChangesetService.Proposal proposeChanges(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Short title of what this change does") String title,
            @McpToolParam(description = "List of operations") List<Operation> operations,
            @McpToolParam(description = "Current session id (keeps $aliases valid across batches)", required = false) String sessionId,
            @McpToolParam(description = "true = validate only, report every error, write nothing", required = false) Boolean dryRun,
            @McpToolParam(description = "atomic (default: all or nothing) or best_effort (apply the valid ones)", required = false) String mode) {
        return in(projectId, () -> {
            var m = Boolean.TRUE.equals(dryRun) ? ChangesetService.Mode.dry_run : ChangesetService.Mode.parse(mode);
            var result = changesets.propose(projectId, Actor.llm, sessionId, title, operations, m);
            Set<String> types = new HashSet<>();
            operations.forEach(o -> {
                if (o.type() != null) types.add(o.type());
            });
            var g = guidance.forEvent(projectId, "write", List.of(), types);
            return g.isEmpty() ? result : result.withGuidance(g);
        });
    }

    @McpTool(name = "changeset_status", description = "Status of a changeset (pending, applied, rejected) and the rejection reason, if any.")
    public ChangesetStatus changesetStatus(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Changeset id") String changesetId) {
        return in(projectId, () -> {
            ChangesetRepository.Row r = changesets.get(projectId, changesetId);
            return new ChangesetStatus(r.id(), r.status(), r.title(), r.error(), r.result());
        });
    }

    // ------------------------------------------------------------------ manuscript

    @McpTool(name = "unit_text_set", description = "Writes the manuscript (prose/script) of a unit — replaces it, or appends with mode=append. "
            + "The server keeps a version of each draft and computes the word count. Much cheaper and safer than putting the text in update_node.")
    public UnitService.TextWrite unitTextSet(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Unit id (chapter, scene...)") String unitId,
            @McpToolParam(description = "The text") String text,
            @McpToolParam(description = "replace (default) or append", required = false) String mode,
            @McpToolParam(description = "Attribute to write (default: the type's manuscript attribute)", required = false) String attribute,
            @McpToolParam(description = "Note about this draft (e.g. 'first pass', 'after review')", required = false) String note,
            @McpToolParam(description = "Current session id", required = false) String sessionId) {
        return in(projectId, () -> units.setText(projectId, unitId, text, mode, attribute, null, note, Actor.llm, sessionId));
    }

    @McpTool(name = "unit_text_append", description = "Appends a chunk to a unit's manuscript (write long chapters in parts). "
            + "Separated from the existing text by a blank line unless 'separator' is given.")
    public UnitService.TextWrite unitTextAppend(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Unit id") String unitId,
            @McpToolParam(description = "Chunk of text to append") String text,
            @McpToolParam(description = "Separator before the chunk (default: blank line)", required = false) String separator,
            @McpToolParam(description = "Current session id", required = false) String sessionId) {
        return in(projectId, () -> units.setText(projectId, unitId, text, "append", null, separator, null, Actor.llm, sessionId));
    }

    @McpTool(name = "unit_text_get", description = "Reads a unit's manuscript (current, or a saved version).")
    public UnitService.TextRead unitTextGet(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Unit id") String unitId,
            @McpToolParam(description = "Saved version number (default: current text)", required = false) Integer version) {
        return in(projectId, () -> units.getText(projectId, unitId, version, null));
    }

    @McpTool(name = "unit_text_history", description = "Saved drafts of a unit's manuscript (version, words, who, when, note), without the text.")
    public List<TextVersionRepository.Version> unitTextHistory(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Unit id") String unitId) {
        return in(projectId, () -> units.history(projectId, unitId));
    }

    @McpTool(name = "unit_text_diff", description = "Line diff between two drafts of a unit's manuscript (to omitted = current text).")
    public TextDiff.Result unitTextDiff(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Unit id") String unitId,
            @McpToolParam(description = "From version") Integer from,
            @McpToolParam(description = "To version (default: current text)", required = false) Integer to) {
        return in(projectId, () -> units.diff(projectId, unitId, from, to));
    }

    @McpTool(name = "unit_cast_set", description = "Sets who appears in a unit (its cast) in one call, instead of one appears-in edge at a time. "
            + "mode=replace (default) makes the list the whole cast; mode=add only adds.")
    public ChangesetService.Proposal unitCastSet(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Unit id") String unitId,
            @McpToolParam(description = "Ids (or session $aliases) of the nodes in the unit") List<String> nodeIds,
            @McpToolParam(description = "replace (default) or add", required = false) String mode,
            @McpToolParam(description = "Current session id", required = false) String sessionId) {
        return in(projectId, () -> units.setCast(projectId, unitId, nodeIds, mode, Actor.llm, sessionId));
    }

    @McpTool(name = "unit_validate", description = "Checks a unit's text against the canon. Returns deterministic findings (characters mentioned but not "
            + "in the cast, cast members not mentioned, capitalized names not in the graph, characters marked dead who appear) and a checklist for "
            + "you to judge: laws/norms in play, secrets that must not be revealed yet, state of the entities at that point, validate directives.")
    public UnitService.Validation unitValidate(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Unit id") String unitId,
            @McpToolParam(description = "Text to check instead of the stored manuscript (e.g. a draft before saving)", required = false) String text) {
        return in(projectId, () -> units.validate(projectId, unitId, text));
    }

    @McpTool(name = "manuscript_export", description = "Compiles the manuscript in narrative order (whole work, or one unit and its parts). "
            + "md/txt come back inline (if not too large); html, docx and epub come back as a download URL.")
    public ExportResult manuscriptExport(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "md (default), txt, html, docx or epub", required = false) String format,
            @McpToolParam(description = "Only this unit and its descendants (e.g. one volume)", required = false) String rootUnitId,
            @McpToolParam(description = "Only these unit types become sections (e.g. ['Chapter'])", required = false) List<String> unitTypes) {
        return in(projectId, () -> {
            String f = format == null ? "md" : format.toLowerCase();
            var compiled = export.compile(projectId, rootUnitId, unitTypes);
            var file = export.export(projectId, f, rootUnitId, unitTypes);
            StringBuilder url = new StringBuilder(publicUrl).append("/api/projects/").append(projectId).append("/export?format=").append(f);
            if (rootUnitId != null) url.append("&rootUnitId=").append(rootUnitId);
            if (unitTypes != null) unitTypes.forEach(u -> url.append("&unitTypes=").append(java.net.URLEncoder.encode(u, StandardCharsets.UTF_8)));
            boolean inline = (f.equals("md") || f.equals("txt")) && file.content().length <= 200_000;
            return new ExportResult(f, file.filename(), compiled.words(), compiled.sections().size(),
                    inline ? new String(file.content(), StandardCharsets.UTF_8) : null, url.toString());
        });
    }

    // ------------------------------------------------------------------ profiles, templates, hooks, directives

    @McpTool(name = "profile_list", description = "Available production profiles (book, light novel, manga, visual novel, game, tabletop RPG, film/series, "
            + "advertising). Each one brings types, unit structure, suggested templates and media-specific directives.")
    public List<HarnessLibrary.ProfileSummary> profileList(
            @McpToolParam(description = "Language of the listing: en (default) or pt", required = false) String locale) {
        return library.profiles(Lang.parse(locale, Lang.en));
    }

    @McpTool(name = "profile_apply", description = "Applies a production profile to an existing project, in the project's language: adds the missing types "
            + "and relations (removes nothing) and starts using the profile's directives.")
    public ChangesetService.Proposal profileApply(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Profile id (profile_list)") String profileId) {
        return in(projectId, () -> profiles.apply(projectId, profileId, Actor.llm));
    }

    @McpTool(name = "template_list", description = "Structure templates: built-in (Three acts, Hero's journey, Save the Cat, Kishōtenketsu, horror, ad spot, "
            + "quest, one-shot...) and custom ones (custom=true, e.g. derived from a dissected work). scope=obra measures the whole work; "
            + "scope=unidade measures the parts of one unit.")
    public List<HarnessLibrary.TemplateSummary> templateList(
            @McpToolParam(description = "Language of the listing: en (default) or pt", required = false) String locale) {
        return library.templates(Lang.parse(locale, Lang.en));
    }

    @McpTool(name = "template_apply", description = "Applies a template (built-in or custom) to the project: creates a Template node with its Beats "
            + "(expected position, function, criteria). Then link each unit to the beat it fulfills with create_edge using the maps-to-beat relation "
            + "(unit → beat). Only apply it if the user agrees.")
    public ChangesetService.Proposal templateApply(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Template id (template_list)") String templateId,
            @McpToolParam(description = "Unit type measured (e.g. Chapter); default from the profile", required = false) String unitType,
            @McpToolParam(description = "Planned total of those units (improves position estimates)", required = false) Integer plannedUnits,
            @McpToolParam(description = "For unit-scope templates: id of the unit whose parts are measured", required = false) String scopeUnitId) {
        return in(projectId, () -> templates.apply(projectId, templateId, unitType, plannedUnits, scopeUnitId, Actor.llm));
    }

    @McpTool(name = "template_derive", description = "Turns an existing work into a reusable template: one beat per unit of the chosen type (e.g. each "
            + "chapter of a dissected book), with position, function (summary/goal) and criteria (beats it fulfilled, hooks it planted/paid, its "
            + "scenes). Apply it to another project with template_apply and compare with milestone_coverage.")
    public HarnessLibrary.TemplateDef templateDerive(
            @McpToolParam(description = "Id of the project to derive from (e.g. the dissected work)") String fromProjectId,
            @McpToolParam(description = "Unit type that becomes beats (e.g. Chapter / Capítulo)") String unitType,
            @McpToolParam(description = "Template name (default: 'Structure of <project>')", required = false) String name,
            @McpToolParam(description = "Description", required = false) String description,
            @McpToolParam(description = "Include the unit's parts (e.g. scenes) in each beat's criteria", required = false) Boolean includeChildren) {
        return in(fromProjectId, () -> templates.derive(fromProjectId, unitType, name, description, Boolean.TRUE.equals(includeChildren)));
    }

    @McpTool(name = "template_upsert", description = "Creates or replaces a custom template. definition: {id?, name, description?, scope: obra|unidade, "
            + "source?, beats: [{key?, name, position (0..1), function?, criteria?, devices?, required?}]}. Built-in templates cannot be changed.")
    public HarnessLibrary.TemplateDef templateUpsert(@McpToolParam(description = "Template definition") HarnessLibrary.TemplateDef definition) {
        return templates.upsert(definition);
    }

    @McpTool(name = "template_delete", description = "Deletes a custom template (applied copies in projects are kept).")
    public String templateDelete(@McpToolParam(description = "Custom template id") String templateId) {
        templates.delete(templateId);
        return "ok";
    }

    @McpTool(name = "milestone_coverage", description = "Coverage of the applied templates: beats in place, off position, missing (the story is past the "
            + "point) or future, and units without a beat. Use it to see what is missing to close the story.")
    public List<TemplateService.Coverage> milestoneCoverage(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Template node id; empty = all", required = false) String templateNodeId) {
        return in(projectId, () -> templateNodeId == null || templateNodeId.isBlank() ? templates.coverageAll(projectId)
                : List.of(templates.coverage(projectId, templateNodeId)));
    }

    @McpTool(name = "open_threads", description = "Open hooks (foreshadowing, Chekhov's gun, mysteries, promises) and planted clues (any node planted with "
            + "the plants relation): where they were planted, how many units ago, what they are about (about relation) and consistency warnings. "
            + "Pay off or remind the oldest before opening new ones.")
    public List<NarrativeService.Thread> openThreads(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Include paid/abandoned", required = false) Boolean includeClosed) {
        return in(projectId, () -> narrative.openThreads(projectId, Boolean.TRUE.equals(includeClosed)));
    }

    @McpTool(name = "writing_context", description = "Context package for WRITING (or revising) a narrative unit, within a token budget: the unit, where "
            + "it sits in the structure, summaries of the previous units, the closing paragraphs of the previous one (voice continuity), the unit's "
            + "parts (scenes with goal/conflict/outcome), beats it fulfills or should fulfill, entities in play with their state at that point in "
            + "time, critical facts/laws, SECRETS that must not be revealed yet, open hooks, a style sample and directives. Use it before writing "
            + "instead of loading the project.")
    public NarrativeService.WritingContext writingContext(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Unit id (chapter, scene, take...)") String unitId,
            @McpToolParam(description = "Approximate budget in tokens (default 6000)", required = false) Integer maxTokens,
            @McpToolParam(description = "How many closing paragraphs of the previous unit to include (default 3, 0 = none)", required = false) Integer tailParagraphs,
            @McpToolParam(description = "Emphasis, e.g. ['secrets'] to put the do-not-reveal list first", required = false) List<String> focus) {
        return in(projectId, () -> narrative.writingContext(projectId, unitId, maxTokens, tailParagraphs, focus));
    }

    @McpTool(name = "directives_list", description = "Active directives of the project (from the profile and from the project) with their triggers. "
            + "Create/edit project directives with propose_changes (upsert_directive; pass 'id' to edit, definition.enabled=false to turn off).")
    public List<Views.Directive> directivesList(@McpToolParam(description = "Project id") String projectId) {
        return in(projectId, () -> guidance.all(projectId));
    }
}
