package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Ids;
import com.obliterateharness.domain.NotFoundException;
import com.obliterateharness.domain.Space;
import com.obliterateharness.domain.Views;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.store.ContextRepository;
import com.obliterateharness.store.ProjectDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Contexts: the domains the platform runs (a company's brain, a research base, anything the user or an LLM defines).
 * <p>
 * Built-in contexts come from YAML (harness/contexts) and point to profiles (harness/profiles) for their schema.
 * Contexts created by the user or by an LLM keep their definition in a <b>blueprint project</b>: a hidden project whose
 * schema (node types, relations, attributes), manual facts (rules) and directives are the context's definition. Editing
 * a context means editing its blueprint, with the same tools used for any project (schema editor, propose_changes).
 * A new project in a context is born with the context's definition.
 */
@Service
public class ContextService {
    private static final Logger log = LoggerFactory.getLogger(ContextService.class);
    private static final List<String> ALL_TABS = List.of("actors", "knowledge", "analysis");

    public record Preset(String id, String name, String description) {}

    /**
     * @param kind              builtin (read-only, from YAML) or custom (created by a person or an LLM)
     * @param blueprintProjectId custom contexts: the project that holds the definition; edit its schema to change the context
     * @param tabs              graph spaces shown as tabs, in order
     * @param spaceLabels       how this context names each space
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ContextView(String id, String name, String description, String icon, String color, String kind, String locale,
                              List<Preset> presets, String defaultPreset, String blueprintProjectId, String baseContext, String instructions,
                              Map<String, String> spaceLabels, List<String> tabs, int projectCount) {}

    /** Built-in context as written in YAML (after localization). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record BuiltinDef(String id, String name, String description, String icon, String color, List<String> presets,
                      String defaultPreset, List<String> tabs, Map<String, String> spaceLabels, String instructions) {}

    private final Map<String, Object> builtinTrees = new LinkedHashMap<>();
    private final JsonMapper converter = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final ContextRepository repo;
    private final ProjectService projects;
    private final ProfileService profiles;
    private final HarnessLibrary library;
    private final CloneService cloner;
    private final ProjectDataRepository projectData;

    public ContextService(ContextRepository repo, ProjectService projects, ProfileService profiles, HarnessLibrary library, CloneService cloner,
                          ProjectDataRepository projectData) {
        this.repo = repo;
        this.projects = projects;
        this.profiles = profiles;
        this.library = library;
        this.cloner = cloner;
        this.projectData = projectData;
        var yaml = YAMLMapper.builder().build();
        try {
            var rs = new ArrayList<>(List.of(new PathMatchingResourcePatternResolver().getResources("classpath:harness/contexts/*.yaml")));
            rs.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource r : rs) {
                try (InputStream in = r.getInputStream()) {
                    Object tree = yaml.readValue(in, Object.class);
                    builtinTrees.put(String.valueOf(((Map<?, ?>) tree).get("id")), tree);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Lang l : Lang.values()) builtinTrees.keySet().forEach(id -> builtin(id, l));
        log.info("Contexts: {} built-in", builtinTrees.size());
    }

    private BuiltinDef builtin(String id, Lang lang) {
        Object tree = builtinTrees.get(id);
        return tree == null ? null : converter.convertValue(HarnessLibrary.localize(tree, lang), BuiltinDef.class);
    }

    // ------------------------------------------------------------------ reading

    public List<ContextView> list(Lang lang) {
        Map<String, Integer> counts = new HashMap<>();
        for (var p : projects.list()) if (p.context() != null) counts.merge(p.context(), 1, Integer::sum);
        List<ContextView> out = new ArrayList<>();
        for (String id : builtinTrees.keySet()) out.add(view(builtin(id, lang), lang, counts.getOrDefault(id, 0)));
        for (var row : repo.list()) out.add(view(row, lang, counts.getOrDefault(row.id(), 0)));
        return out;
    }

    public ContextView get(String id, Lang lang) {
        int count = projects.inContext(id).size();
        BuiltinDef b = builtin(id, lang);
        if (b != null) return view(b, lang, count);
        return view(row(id), lang, count);
    }

    /** The context a project belongs to. */
    public Optional<ContextView> ofProject(Views.Project p, Lang lang) {
        if (p.context() == null) return Optional.empty();
        try {
            return Optional.of(get(p.context(), lang));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    private ContextRepository.Row row(String id) {
        return repo.get(id).orElseThrow(() -> new NotFoundException(t("Context '", "Contexto '") + id
                + t("' does not exist. Use context_list to see the contexts.", "' não existe. Use context_list para ver os contextos.")));
    }

    private ContextView view(BuiltinDef b, Lang lang, int count) {
        List<Preset> presets = new ArrayList<>();
        if (b.presets() != null) {
            for (String pid : b.presets()) {
                library.profile(pid, lang).ifPresent(p -> presets.add(new Preset(p.id(), p.name(), p.description())));
            }
        }
        return new ContextView(b.id(), b.name(), b.description(), b.icon(), b.color(), "builtin", null, presets, b.defaultPreset(), null, null,
                b.instructions(), normalizeLabels(b.spaceLabels()), normalizeTabs(b.tabs()), count);
    }

    private ContextView view(ContextRepository.Row r, Lang lang, int count) {
        return new ContextView(r.id(), r.name(), r.description(), r.icon(), r.color(), "custom", r.locale(), List.of(), null, r.blueprintProjectId(),
                r.baseContext(), r.instructions(), normalizeLabels(r.spaceLabels()), normalizeTabs(r.tabs()), count);
    }

    /** Tabs in current space names (contexts saved before the rename used world/characters/...), without repeats. */
    private static List<String> normalizeTabs(List<String> tabs) {
        if (tabs == null || tabs.isEmpty()) return ALL_TABS;
        var out = new java.util.LinkedHashSet<String>();
        for (String t : tabs) {
            Space s = Space.of(t);
            if (s != null) out.add(s.name());
        }
        return out.isEmpty() ? ALL_TABS : List.copyOf(out);
    }

    private static Map<String, String> normalizeLabels(Map<String, String> labels) {
        if (labels == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        labels.forEach((k, v) -> {
            Space s = Space.of(k);
            if (s != null) out.putIfAbsent(s.name(), v);
        });
        return out;
    }

    // ------------------------------------------------------------------ creating and editing contexts

    /**
     * Creates a context and its blueprint project.
     *
     * @param baseContext optional: a built-in or custom context to start from (its types, relations, rules and directives are copied)
     * @param basePreset  optional: for a built-in base with several presets, which one
     */
    public ContextView create(String name, String description, String icon, String color, String locale, String baseContext, String basePreset,
                              String instructions, Actor actor) {
        if (name == null || name.isBlank()) throw new DomainException(t("Context name is required", "Nome do contexto é obrigatório"));
        Lang lang = Lang.parse(locale, Lang.en);
        String id = Ids.next("ctx");
        String bpName = name.trim() + t(" · definition", " · definição");
        String blueprint;
        Map<String, String> labels = null;
        List<String> tabs = null;
        String baseId = baseContext == null || baseContext.isBlank() ? null : baseContext;
        BuiltinDef base = baseId == null ? null : builtin(baseId, lang);
        if (baseId != null && base == null) {
            // custom base: copy its blueprint (schema, rules, directives), translating if needed
            var b = row(baseId);
            String translate = b.locale().equals(lang.name()) ? null : lang.name();
            var copy = cloner.cloneProject(b.blueprintProjectId(), bpName, CloneService.Options.schemaOnly(), translate);
            blueprint = copy.projectId();
            projects.update(blueprint, null, description, null, null);
            labels = b.spaceLabels();
            tabs = b.tabs();
            if (instructions == null) instructions = b.instructions();
        } else {
            String profile = base == null ? null : basePreset != null && !basePreset.isBlank() ? basePreset : base.defaultPreset();
            if (base != null && profile != null && (base.presets() == null || !base.presets().contains(profile))) {
                throw new DomainException(t("Preset '", "Preset '") + profile + t("' is not part of context ", "' não faz parte do contexto ") + baseId
                        + ": " + base.presets());
            }
            var p = projects.create(bpName, description, profile, false, lang.name());
            blueprint = p.id();
            if (profile != null) {
                String prof = profile;
                I18n.with(lang, () -> profiles.apply(p.id(), prof, actor));
            }
            if (base != null) {
                labels = base.spaceLabels();
                tabs = base.tabs();
                if (instructions == null) instructions = base.instructions();
            }
        }
        projects.setContext(blueprint, id, true);
        repo.insert(new ContextRepository.Row(id, name.trim(), description, blank(icon) ? "🧩" : icon, color, lang.name(), blueprint, baseId,
                instructions, labels, tabs));
        return get(id, lang);
    }

    public ContextView update(String id, String name, String description, String icon, String color, String instructions,
                              Map<String, String> spaceLabels, List<String> tabs, Lang lang) {
        if (builtin(id, lang) != null) {
            throw new DomainException(t("Built-in contexts are read-only. Create a context based on it (context_create with baseContext) and change that one.",
                    "Contextos embutidos são somente leitura. Crie um contexto baseado nele (context_create com baseContext) e altere esse."));
        }
        var r = row(id);
        if (tabs != null) {
            if (tabs.isEmpty()) throw new DomainException(t("Keep at least one tab", "Mantenha pelo menos uma aba"));
            for (String s : tabs) if (Space.of(s) == null) throw new DomainException(t("Unknown tab '", "Aba desconhecida '") + s + "'. " + ALL_TABS);
            tabs = normalizeTabs(tabs);
        }
        if (spaceLabels != null) {
            for (String s : spaceLabels.keySet()) if (Space.of(s) == null) throw new DomainException(t("Unknown space '", "Espaço desconhecido '") + s + "'. " + ALL_TABS);
            spaceLabels = normalizeLabels(spaceLabels);
        }
        repo.update(id, blankToNull(name), description, blankToNull(icon), blankToNull(color), instructions, spaceLabels, tabs);
        if (name != null && !name.isBlank()) projects.update(r.blueprintProjectId(), name.trim() + t(" · definition", " · definição"), null, null, null);
        return get(id, lang);
    }

    /** Deletes a custom context and its blueprint. Refuses while projects still use it. */
    public void delete(String id, String confirmName, Lang lang) {
        if (builtin(id, lang) != null) throw new DomainException(t("Built-in contexts cannot be deleted", "Contextos embutidos não podem ser excluídos"));
        var r = row(id);
        if (!r.name().equals(confirmName)) {
            throw new DomainException(t("Confirmation does not match: pass the context's exact name in 'confirm'",
                    "Confirmação não confere: passe o nome exato do contexto em 'confirm'"));
        }
        int n = projects.inContext(id).size();
        if (n > 0) throw new DomainException(n + t(" project(s) still use this context; delete or move them first.",
                " projeto(s) ainda usam este contexto; exclua ou mova antes."));
        projectData.deleteProject(r.blueprintProjectId());
        projects.deleteGraph(r.blueprintProjectId());
        repo.delete(id);
    }

    // ------------------------------------------------------------------ projects in a context

    /**
     * Creates a project in a context: built-in contexts apply their profile (preset); custom contexts copy their blueprint's
     * schema, rules and directives.
     */
    public Views.Project createProject(String contextId, String name, String description, String locale, String preset,
                                       Boolean requireReviewForLlm, Actor actor) {
        if (name == null || name.isBlank()) throw new DomainException(t("Project name is required", "Nome do projeto é obrigatório"));
        Lang lang = Lang.parse(locale, Lang.en);
        BuiltinDef b = builtin(contextId, lang);
        if (b != null) {
            String profile = preset != null && !preset.isBlank() ? preset : b.defaultPreset();
            if (profile != null && b.presets() != null && !b.presets().contains(profile)) {
                throw new DomainException(t("Preset '", "Preset '") + profile + t("' is not part of context ", "' não faz parte do contexto ") + contextId + ": " + b.presets());
            }
            var p = projects.create(name, description, profile, requireReviewForLlm, lang.name());
            if (profile != null && library.profile(profile, lang).isPresent()) I18n.with(lang, () -> profiles.apply(p.id(), profile, actor));
            projects.setContext(p.id(), contextId, false);
            return projects.get(p.id());
        }
        var r = row(contextId);
        var copy = cloner.cloneProject(r.blueprintProjectId(), name, CloneService.Options.schemaOnly(), null);
        projects.update(copy.projectId(), null, description == null ? "" : description, null, requireReviewForLlm);
        projects.setContext(copy.projectId(), contextId, false);
        return projects.get(copy.projectId());
    }

    /** Context instructions for the LLM briefing of a project. */
    public List<String> guidanceFor(Views.Project p, Lang lang) {
        return ofProject(p, lang).map(c -> {
            List<String> g = new ArrayList<>();
            g.add(t("Context: ", "Contexto: ") + c.name() + (c.description() != null ? " — " + c.description() : ""));
            if (c.instructions() != null && !c.instructions().isBlank()) g.add(c.instructions());
            return g;
        }).orElse(List.of());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
