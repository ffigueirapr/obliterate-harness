package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.ObjectTypeDef;
import com.obliterateharness.domain.RelTypeDef;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.store.CustomTemplateRepository;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Harness library, loaded from bilingual YAML on the classpath: production profiles (harness/profiles) and
 * narrative structure templates (harness/templates). Any text can be written as {en: ..., pt: ...}; the same file
 * resolves to either language, so both versions always line up (used to translate an existing project's schema).
 * User-made templates (custom_template table) are merged in.
 */
@Component
public class HarnessLibrary {
    private static final Logger log = LoggerFactory.getLogger(HarnessLibrary.class);
    private static final Set<String> LANG_KEYS = Set.of("en", "pt");

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record DirectiveDef(String id, String text, List<String> when, List<String> bases, List<String> types, Integer priority) {}

    /** Production profile: schema pack + unit structure + suggested templates + manual + directives. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ProfileDef(
            String id,
            String name,
            String description,
            @JsonProperty("abstract") Boolean isAbstract,
            List<String> include,
            List<String> units,
            String templateUnit,
            List<String> suggestedTemplates,
            List<ObjectTypeDef> objectTypes,
            List<NodeTypeDef> nodeTypes,
            List<RelTypeDef> relTypes,
            String manual,
            List<DirectiveDef> directives,
            String context
    ) {
        /** Listed among the production profiles (narrative presets): not abstract, and not owned by another context. */
        public boolean listed() {
            return !Boolean.TRUE.equals(isAbstract) && (context == null || "narrative".equals(context));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record BeatDef(
            @McpToolParam(required = false, description = "Stable key (default: from the name)") String key,
            @McpToolParam(required = true, description = "Beat name") String name,
            @McpToolParam(required = true, description = "Expected position in the work, 0 to 1") double position,
            @McpToolParam(required = false, description = "Dramatic function") String function,
            @McpToolParam(required = false, description = "Acceptance criteria") String criteria,
            @McpToolParam(required = false, description = "Expected devices") List<String> devices,
            @McpToolParam(required = false, description = "Required beat (default true)") Boolean required) {}

    /**
     * Structure template (Three acts, Save the Cat...): beats with expected position (0..1) and acceptance criteria.
     *
     * @param custom true for user-made templates (created by hand or derived from a work)
     * @param locale language of a custom template's texts
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record TemplateDef(
            @McpToolParam(required = false, description = "Id (omit to create a new one)") String id,
            @McpToolParam(required = true, description = "Template name") String name,
            @McpToolParam(required = false, description = "Description") String description,
            @McpToolParam(required = false, description = "obra (whole work, default) or unidade (parts of one unit)") String scope,
            @McpToolParam(required = false, description = "Profiles it suits") List<String> media,
            @McpToolParam(required = false, description = "Source (e.g. the work it came from)") String source,
            @McpToolParam(required = true, description = "Beats in order") List<BeatDef> beats,
            @McpToolParam(required = false, description = "Set by the server") Boolean custom,
            @McpToolParam(required = false, description = "Language of the texts (en or pt)") String locale) {
        public TemplateDef withBeats(List<BeatDef> b) {
            return new TemplateDef(id, name, description, scope, media, source, b, custom, locale);
        }
    }

    /** Summary for listings. */
    public record ProfileSummary(String id, String name, String description, List<String> units, List<String> suggestedTemplates) {}

    public record TemplateSummary(String id, String name, String description, String scope, List<String> media, int beats, boolean custom, String source) {}

    private final Map<String, Object> profileTrees = new LinkedHashMap<>();
    private final Map<String, Object> templateTrees = new LinkedHashMap<>();
    private final JsonMapper converter = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final CustomTemplateRepository custom;

    public HarnessLibrary(CustomTemplateRepository custom) {
        this.custom = custom;
        var yaml = YAMLMapper.builder().build();
        var resolver = new PathMatchingResourcePatternResolver();
        try {
            for (Resource r : sorted(resolver.getResources("classpath:harness/profiles/*.yaml"))) {
                try (InputStream in = r.getInputStream()) {
                    Object tree = yaml.readValue(in, Object.class);
                    profileTrees.put(String.valueOf(((Map<?, ?>) tree).get("id")), tree);
                }
            }
            for (Resource r : sorted(resolver.getResources("classpath:harness/templates/*.yaml"))) {
                try (InputStream in = r.getInputStream()) {
                    Object tree = yaml.readValue(in, Object.class);
                    templateTrees.put(String.valueOf(((Map<?, ?>) tree).get("id")), tree);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // fail fast if any file does not resolve in both languages
        for (Lang l : Lang.values()) {
            profileTrees.keySet().forEach(id -> profileDef(id, l));
            templateTrees.keySet().forEach(id -> templateDef(id, l));
        }
        log.info("Harness: {} profiles, {} templates (en + pt)", profileTrees.size(), templateTrees.size());
    }

    private static List<Resource> sorted(Resource[] rs) {
        var list = new ArrayList<>(List.of(rs));
        list.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
        return list;
    }

    // ---------- localization of the YAML tree ----------

    /** Replaces every {en: x, pt: y} map with the value for the language (falls back to English). */
    @SuppressWarnings("unchecked")
    static Object localize(Object node, Lang lang) {
        if (node instanceof Map<?, ?> m) {
            if (!m.isEmpty() && LANG_KEYS.containsAll(m.keySet())) {
                Object v = m.get(lang.name());
                return localize(v != null ? v : m.get("en") != null ? m.get("en") : m.get("pt"), lang);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, localize(v, lang)));
            return out;
        }
        if (node instanceof List<?> l) return l.stream().map(x -> localize(x, lang)).toList();
        return node;
    }

    private ProfileDef profileDef(String id, Lang lang) {
        Object tree = profileTrees.get(id);
        if (tree == null) return null;
        return converter.convertValue(localize(tree, lang), ProfileDef.class);
    }

    private TemplateDef templateDef(String id, Lang lang) {
        Object tree = templateTrees.get(id);
        if (tree == null) return null;
        TemplateDef t = converter.convertValue(localize(tree, lang), TemplateDef.class);
        return new TemplateDef(t.id(), t.name(), t.description(), t.scope(), t.media(), t.source(), t.beats(), false, lang.name());
    }

    // ---------- profiles ----------

    public List<ProfileSummary> profiles(Lang lang) {
        return profileTrees.keySet().stream().map(id -> profileDef(id, lang)).filter(ProfileDef::listed)
                .map(p -> new ProfileSummary(p.id(), p.name(), p.description(), p.units(), p.suggestedTemplates())).toList();
    }

    public Optional<ProfileDef> profile(String id, Lang lang) {
        return Optional.ofNullable(id == null ? null : profileDef(id, lang));
    }

    public ProfileDef requireProfile(String id, Lang lang) {
        return profile(id, lang).orElseThrow(() -> new DomainException(t("Profile '", "Perfil '") + id + t("' does not exist. Profiles: ", "' não existe. Perfis: ")
                + profiles(lang).stream().map(ProfileSummary::id).toList()));
    }

    /** The profile and every profile it includes, in application order (included ones first). */
    public List<ProfileDef> chain(String id, Lang lang) {
        List<ProfileDef> out = new ArrayList<>();
        collect(requireProfile(id, lang), lang, out);
        return out;
    }

    private void collect(ProfileDef p, Lang lang, List<ProfileDef> out) {
        if (out.stream().anyMatch(x -> x.id().equals(p.id()))) return;
        if (p.include() != null) for (String inc : p.include()) collect(requireProfile(inc, lang), lang, out);
        out.add(p);
    }

    // ---------- templates ----------

    public List<TemplateSummary> templates(Lang lang) {
        List<TemplateSummary> out = new ArrayList<>();
        for (String id : templateTrees.keySet()) {
            TemplateDef t = templateDef(id, lang);
            out.add(new TemplateSummary(t.id(), t.name(), t.description(), t.scope(), t.media(), t.beats().size(), false, t.source()));
        }
        for (TemplateDef t : custom.list()) {
            out.add(new TemplateSummary(t.id(), t.name(), t.description(), t.scope(), t.media(), t.beats().size(), true, t.source()));
        }
        return out;
    }

    public TemplateDef requireTemplate(String id, Lang lang) {
        TemplateDef t = templateDef(id, lang);
        if (t != null) return t;
        return custom.get(id).orElseThrow(() -> new DomainException(t("Template '", "Template '") + id + t("' does not exist. Templates: ", "' não existe. Templates: ")
                + templates(lang).stream().map(TemplateSummary::id).toList()));
    }

    public boolean isBuiltinTemplate(String id) {
        return templateTrees.containsKey(id);
    }
}
