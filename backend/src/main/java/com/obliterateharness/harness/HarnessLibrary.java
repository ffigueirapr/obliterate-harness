package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.ObjectTypeDef;
import com.obliterateharness.domain.RelTypeDef;
import com.obliterateharness.i18n.Lang;
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
 * Harness library, loaded from bilingual YAML on the classpath: the schema presets of the built-in contexts
 * (harness/profiles). Any text can be written as {en: ..., pt: ...}; the same file resolves to either language,
 * so both versions always line up (used to translate an existing project's schema).
 */
@Component
public class HarnessLibrary {
    private static final Logger log = LoggerFactory.getLogger(HarnessLibrary.class);
    private static final Set<String> LANG_KEYS = Set.of("en", "pt");

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record DirectiveDef(String id, String text, List<String> when, List<String> bases, List<String> types, Integer priority) {}

    /** Schema preset of a built-in context: schema pack + directives, optionally including other presets. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ProfileDef(
            String id,
            String name,
            String description,
            @JsonProperty("abstract") Boolean isAbstract,
            List<String> include,
            List<ObjectTypeDef> objectTypes,
            List<NodeTypeDef> nodeTypes,
            List<RelTypeDef> relTypes,
            String manual,
            List<DirectiveDef> directives,
            String context
    ) {
        public boolean listed() {
            return !Boolean.TRUE.equals(isAbstract);
        }
    }

    /** Summary for listings. */
    public record ProfileSummary(String id, String name, String description) {}

    private final Map<String, Object> profileTrees = new LinkedHashMap<>();
    private final JsonMapper converter = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public HarnessLibrary() {
        var yaml = YAMLMapper.builder().build();
        var resolver = new PathMatchingResourcePatternResolver();
        try {
            for (Resource r : sorted(resolver.getResources("classpath:harness/profiles/*.yaml"))) {
                try (InputStream in = r.getInputStream()) {
                    Object tree = yaml.readValue(in, Object.class);
                    profileTrees.put(String.valueOf(((Map<?, ?>) tree).get("id")), tree);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // fail fast if any file does not resolve in both languages
        for (Lang l : Lang.values()) {
            profileTrees.keySet().forEach(id -> profileDef(id, l));
        }
        log.info("Harness: {} schema presets (en + pt)", profileTrees.size());
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

    // ---------- profiles ----------

    public List<ProfileSummary> profiles(Lang lang) {
        return profileTrees.keySet().stream().map(id -> profileDef(id, lang)).filter(ProfileDef::listed)
                .map(p -> new ProfileSummary(p.id(), p.name(), p.description())).toList();
    }

    public Optional<ProfileDef> profile(String id, Lang lang) {
        return Optional.ofNullable(id == null ? null : profileDef(id, lang));
    }

    public ProfileDef requireProfile(String id, Lang lang) {
        return profile(id, lang).orElseThrow(() -> new DomainException(t("Preset '", "Preset '") + id + t("' does not exist. Presets: ", "' não existe. Presets: ")
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
}
