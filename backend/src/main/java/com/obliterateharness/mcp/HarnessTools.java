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
import com.obliterateharness.harness.CloneService;
import com.obliterateharness.harness.ContextService;
import com.obliterateharness.harness.GuidanceService;
import com.obliterateharness.harness.LocalizationService;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ChangesetService;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.service.SessionService;
import com.obliterateharness.store.ChangesetRepository;
import com.obliterateharness.store.SessionRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

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

    private final ProjectService projects;
    private final SessionService sessions;
    private final ChangesetService changesets;
    private final Graph graph;
    private final GraphReader reader;
    private final SchemaStore schema;
    private final GuidanceService guidance;
    private final CloneService cloner;
    private final LocalizationService localization;
    private final ContextService contexts;

    public HarnessTools(ProjectService projects, SessionService sessions, ChangesetService changesets, Graph graph, GraphReader reader,
                        SchemaStore schema, GuidanceService guidance, CloneService cloner, LocalizationService localization,
                        ContextService contexts) {
        this.projects = projects;
        this.sessions = sessions;
        this.changesets = changesets;
        this.graph = graph;
        this.reader = reader;
        this.schema = schema;
        this.guidance = guidance;
        this.cloner = cloner;
        this.localization = localization;
        this.contexts = contexts;
    }

    /** Runs a project tool in the project's language. */
    private <T> T in(String projectId, Supplier<T> work) {
        return I18n.with(projects.locale(projectId), work);
    }

    // ------------------------------------------------------------------ projects and sessions

    @McpTool(name = "project_list", description = "Lists the projects on the platform, with their context (domain) and language (locale). "
            + "Filter by contextId to see the projects of one context (see context_list).")
    public List<Views.Project> projectList(
            @McpToolParam(description = "Only projects of this context (e.g. corporate, ctx_...)", required = false) String contextId) {
        if (contextId == null || contextId.isBlank()) return projects.list();
        return projects.inContext(contextId);
    }

    // ------------------------------------------------------------------ contexts (domains)

    @McpTool(name = "context_list", description = "Lists the contexts (domains) this platform runs: built-in ones (corporate: a company's brain) "
            + "and the ones created by people or LLMs. A context defines which node types, relations, rules and directives its projects have. "
            + "Start here to find where the information you need lives, then project_list(contextId).")
    public List<ContextService.ContextView> contextList() {
        return contexts.list(I18n.lang());
    }

    @McpTool(name = "context_get", description = "Details of one context: description, instructions for the AI, presets, tabs, and for custom contexts "
            + "the blueprintProjectId whose schema IS the context's definition (read it with schema_describe).")
    public ContextService.ContextView contextGet(@McpToolParam(description = "Context id") String contextId) {
        return contexts.get(contextId, I18n.lang());
    }

    @McpTool(name = "context_create", description = "Creates a new context (domain), e.g. a company's brain for a specific area, a research "
            + "knowledge base, a product catalog. Only create one if the user asked. It returns blueprintProjectId: DEFINE the context by editing that "
            + "project's schema with propose_changes (upsert_node_type, upsert_rel_type with allowed pairs, create_fact for rules, "
            + "upsert_directive for how the AI must behave). Every project later created in the context starts with that definition. "
            + "Start from a base (baseContext: corporate or another context id) to copy its types and adapt them, or leave it empty "
            + "to design from scratch.")
    public ContextService.ContextView contextCreate(
            @McpToolParam(description = "Context name") String name,
            @McpToolParam(description = "What the context is for", required = false) String description,
            @McpToolParam(description = "Emoji icon", required = false) String icon,
            @McpToolParam(description = "Language of the schema: en (default) or pt", required = false) String locale,
            @McpToolParam(description = "Context to start from: corporate or a custom context id; empty = blank", required = false) String baseContext,
            @McpToolParam(description = "Instructions every AI working in this context receives in its briefing", required = false) String instructions) {
        return contexts.create(name, description, icon, null, locale, baseContext, null, instructions, Actor.llm);
    }

    @McpTool(name = "context_update", description = "Changes a custom context's settings: name, description, icon, instructions for the AI, "
            + "tabs (graph spaces shown: actors, knowledge, analysis) and the label of each space. "
            + "To change its node types and relations, edit the blueprint project's schema with propose_changes.")
    public ContextService.ContextView contextUpdate(
            @McpToolParam(description = "Context id") String contextId,
            @McpToolParam(description = "New name", required = false) String name,
            @McpToolParam(description = "New description", required = false) String description,
            @McpToolParam(description = "Emoji icon", required = false) String icon,
            @McpToolParam(description = "Instructions for the AI", required = false) String instructions,
            @McpToolParam(description = "Tabs in order, e.g. [actors, knowledge, analysis]", required = false) List<String> tabs,
            @McpToolParam(description = "Label per space, e.g. actors -> People and accounts", required = false) Map<String, String> spaceLabels) {
        return contexts.update(contextId, name, description, icon, null, instructions, spaceLabels, tabs, I18n.lang());
    }

    @McpTool(name = "project_create", description = "Creates a new project inside a context (contextId; see context_list): it is born with the "
            + "context's node types, relations, rules and directives. Only create one if the user asked; usually the project already exists "
            + "(see project_list).")
    public Views.Project projectCreate(
            @McpToolParam(description = "Project name") String name,
            @McpToolParam(description = "Context id: corporate or a custom one (see context_list)") String contextId,
            @McpToolParam(description = "Short description", required = false) String description,
            @McpToolParam(description = "Preset inside a built-in context (see context_get); default: the context's default", required = false) String preset,
            @McpToolParam(description = "Language of the schema and content: en (default) or pt", required = false) String locale) {
        if (contextId == null || contextId.isBlank()) {
            throw new DomainException(I18n.t("contextId is required: pick one with context_list (or create one with context_create)",
                    "contextId é obrigatório: escolha um com context_list (ou crie com context_create)"));
        }
        Lang lang = Lang.parse(locale, Lang.en);
        return I18n.with(lang, () -> contexts.createProject(contextId, name, description, lang.name(), preset, null, Actor.llm));
    }

    @McpTool(name = "project_clone", description = "Copies a whole project (schema, nodes, edges, facts, directives) with new ids and returns "
            + "the old→new id map. Optionally translates the copy's schema (translateTo: en|pt) — types, attributes, enums, relations; "
            + "names/summaries/texts stay as they are for you to translate.")
    public CloneService.Result projectClone(
            @McpToolParam(description = "Id of the project to copy") String projectId,
            @McpToolParam(description = "Name of the copy") String newName,
            @McpToolParam(description = "Copy nodes and edges; default true (false = schema, rules and directives only)", required = false) Boolean includeNodes,
            @McpToolParam(description = "Copy manual facts; default true", required = false) Boolean includeFacts,
            @McpToolParam(description = "Copy the project's directives; default true", required = false) Boolean includeDirectives,
            @McpToolParam(description = "Translate the copy's schema to en or pt", required = false) String translateTo) {
        return in(projectId, () -> cloner.cloneProject(projectId, newName, new CloneService.Options(includeNodes == null || includeNodes,
                includeFacts == null || includeFacts, includeDirectives == null || includeDirectives), translateTo));
    }

    @McpTool(name = "project_localize", description = "Converts a project's schema to another language (en|pt): types, attribute keys and labels, "
            + "enum values, relations and object types that came from the context's preset. Data is migrated with it; user-made types are kept. "
            + "Content is not translated.")
    public LocalizationService.Report projectLocalize(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Target language: en or pt") String locale) {
        Lang target = Lang.parse(locale, null);
        if (target == null) throw new DomainException("locale must be en or pt");
        return I18n.with(target, () -> localization.localize(projectId, target));
    }

    @McpTool(name = "session_begin", description = "ALWAYS call first when starting work on a project. Opens a work session and returns the briefing: "
            + "language (write in it), context instructions, schema (types and attributes), stats, foundational facts (rules that cannot be "
            + "broken), last checkpoint, directives and operating rules.")
    public SessionService.Briefing sessionBegin(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Goal of this session (e.g. 'file the new pricing policy', 'answer questions about product X')") String goal) {
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
            @McpToolParam(description = "Decisions made with the user", required = false) List<String> decisions,
            @McpToolParam(description = "Pending items and next steps", required = false) List<String> todos,
            @McpToolParam(description = "Ids of the nodes in focus now", required = false) List<String> focusNodeIds) {
        return in(projectId, () -> sessions.checkpoint(projectId, sessionId, summary, decisions, todos, focusNodeIds));
    }

    // ------------------------------------------------------------------ reading

    @McpTool(name = "schema_describe", description = "Returns the project's full schema: node types (base, typed attributes, roles), "
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
            @McpToolParam(description = "Filter by type names (e.g. ['Team'])", required = false) List<String> types,
            @McpToolParam(description = "Filter by base kind (Agent, Place, Object, Ability, Concept, Event, Media, Note)", required = false) List<String> bases,
            @McpToolParam(description = "Filter by tags", required = false) List<String> tags,
            @McpToolParam(description = "Filter by space (actors, knowledge, analysis)", required = false) List<String> spaces,
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

    @McpTool(name = "edge_get", description = "Reads edges by id, with their current version (needed to update/delete an edge) and description.")
    public List<Views.Edge> edgeGet(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Edge ids") List<String> ids) {
        return in(projectId, () -> graph.read(tx -> reader.edgesById(tx, projectId, ids)));
    }

    @McpTool(name = "graph_focus", description = "Focus on a node: the node and everything connected to it, with each one's distance in hops. "
            + "Default: only direct connections. allConnected=true brings everything connected, even indirectly (long chains). "
            + "Use it to understand the context of a client, product or rule without loading the whole project.")
    public Views.Focus graphFocus(
            @McpToolParam(description = "Project id") String projectId,
            @McpToolParam(description = "Id of the center node") String nodeId,
            @McpToolParam(description = "Max hops (default 1 = direct only)", required = false) Integer hops,
            @McpToolParam(description = "true = everything connected, even indirectly (ignores hops)", required = false) Boolean allConnected,
            @McpToolParam(description = "Only follow these relation types", required = false) List<String> relTypes,
            @McpToolParam(description = "Max nodes (default 100)", required = false) Integer limit) {
        return in(projectId, () -> {
            int h = Boolean.TRUE.equals(allConnected) ? GraphReader.ALL_LEVELS : (hops == null ? 1 : hops);
            return graph.read(tx -> reader.focus(tx, projectId, nodeId, h, relTypes, limit == null ? 100 : Math.min(limit, 1000), Detail.summary));
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

    @McpTool(name = "facts_list", description = "Critical/foundational facts: binding rules and information that must not be forgotten or broken. "
            + "Filter by the nodes in play (about) before answering or writing about them.")
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
            - Use 'ref' ("$name") to alias what you create and reference it in later ops (from, to, about, node_ref attributes).
              With sessionId, aliases stay valid in later batches of the same session.
            - update/delete need 'expectedVersion' (from node_get / edge_get, or the 'version' returned for each change).
            - Mark binding rules with criticality/attributeCriticality 'critical' or 'foundational'.
            - Directives: upsert_directive with 'id' edits an existing one; definition.enabled=false turns it off without deleting.
            - dryRun=true validates everything and lists ALL errors without writing. mode=best_effort applies the valid ops and returns the invalid ones.
            - Long content stays at its source: store a summary and a link attribute (role link).
            Example: [{"op":"create_node","ref":"$billing","type":"Product","name":"Billing","summary":"Charges clients monthly","attributes":{"status":"available"}},
             {"op":"create_edge","relType":"DEPENDS_ON","from":"$billing","to":"n_xxxx"}]
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

    // ------------------------------------------------------------------ directives

    @McpTool(name = "directives_list", description = "Active directives of the project (from the context's preset and from the project) with their "
            + "triggers. Create/edit project directives with propose_changes (upsert_directive; pass 'id' to edit, definition.enabled=false to turn off).")
    public List<Views.Directive> directivesList(@McpToolParam(description = "Project id") String projectId) {
        return in(projectId, () -> guidance.all(projectId));
    }
}
