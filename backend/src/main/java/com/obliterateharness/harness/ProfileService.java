package com.obliterateharness.harness;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.AttrDef;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.ObjectTypeDef;
import com.obliterateharness.domain.Operation;
import com.obliterateharness.domain.RelTypeDef;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.JsonCodec;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.harness.HarnessLibrary.ProfileDef;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ChangesetService;
import com.obliterateharness.service.ProjectService;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Applies production profiles to projects, in the project's language: generates schema operations (types, relations,
 * objects) and records them in a changeset. Re-applying is safe: existing types only get what is missing (attributes,
 * allowed pairs, system keys). Types are matched by system key first, then by name.
 */
@Service
public class ProfileService {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final HarnessLibrary library;
    private final Graph graph;
    private final SchemaStore schema;
    private final ChangesetService changesets;
    private final ProjectService projects;
    private final JsonCodec json;

    public ProfileService(HarnessLibrary library, Graph graph, SchemaStore schema, ChangesetService changesets, ProjectService projects, JsonCodec json) {
        this.library = library;
        this.graph = graph;
        this.schema = schema;
        this.changesets = changesets;
        this.projects = projects;
        this.json = json;
    }

    /** Applies the profile (and the ones it includes) to the project and records the profile on the project. */
    public ChangesetService.Proposal apply(String projectId, String profileId, Actor actor) {
        Lang lang = projects.locale(projectId);
        var chain = library.chain(profileId, lang);
        var ops = schemaOps(projectId, chain, n -> true);
        projects.update(projectId, null, null, profileId, null);
        if (ops.isEmpty()) {
            return new ChangesetService.Proposal(null, "applied", Map.of(), List.of(),
                    t("The schema already had everything in the profile.", "O schema já continha tudo do perfil."), null, null);
        }
        return changesets.propose(projectId, actor, null, t("Profile ", "Perfil ") + library.requireProfile(profileId, lang).name(), ops);
    }

    /** Makes sure the given core kits exist (by system key), e.g. before applying a template. */
    public void ensureCore(String projectId, Set<String> systemKeys) {
        Lang lang = projects.locale(projectId);
        var ops = schemaOps(projectId, library.chain("nucleo", lang), k -> k != null && systemKeys.contains(k));
        if (!ops.isEmpty()) changesets.propose(projectId, Actor.system, null, t("Harness core types", "Tipos do núcleo do harness"), ops);
    }

    /**
     * @param include filter by system key (types without one are excluded when filtering), or accept all
     */
    List<Operation> schemaOps(String projectId, List<ProfileDef> chain, Predicate<String> include) {
        Map<String, ObjectTypeDef> ots = new LinkedHashMap<>();
        Map<String, NodeTypeDef> nts = new LinkedHashMap<>();
        Map<String, RelTypeDef> rts = new LinkedHashMap<>();
        for (ProfileDef p : chain) {
            if (p.objectTypes() != null) p.objectTypes().stream().filter(x -> include.test(null)).forEach(x -> ots.put(x.name().toLowerCase(), x));
            if (p.nodeTypes() != null) p.nodeTypes().stream().filter(x -> include.test(x.systemKey())).forEach(x -> nts.put(x.name().toLowerCase(), x));
            if (p.relTypes() != null) p.relTypes().stream().filter(x -> include.test(x.systemKey())).forEach(x -> rts.put(x.name().toLowerCase(), x));
        }
        var existing = graph.read(tx -> schema.cache(tx, projectId));
        List<Operation> ops = new ArrayList<>();
        for (ObjectTypeDef ot : ots.values()) {
            ObjectTypeDef cur = existing.objectType(ot.name());
            if (cur != null) {
                var fields = mergeAttrs(cur.fieldList(), ot.fieldList());
                if (fields.size() == cur.fieldList().size()) continue;
                ot = new ObjectTypeDef(null, null, cur.name(), cur.description(), fields);
            }
            ops.add(op("upsert_object_type", definition(ot), null));
        }
        for (NodeTypeDef nt : nts.values()) {
            var cur = (nt.systemKey() != null ? existing.nodeTypeBySystem(nt.systemKey()) : java.util.Optional.<NodeTypeDef>empty())
                    .or(() -> existing.nodeType(nt.name())).orElse(null);
            if (cur != null) {
                var attrs = mergeAttrs(cur.attrs(), nt.attrs());
                boolean sysMissing = nt.systemKey() != null && cur.systemKey() == null;
                if (attrs.size() == cur.attrs().size() && !sysMissing) continue;
                NodeTypeDef merged = new NodeTypeDef(null, null, cur.name(), cur.base(), cur.description(), cur.color(), cur.strict(), attrs,
                        cur.systemKey() != null ? cur.systemKey() : nt.systemKey());
                ops.add(op("upsert_node_type", definition(merged), cur.id()));
                continue;
            }
            ops.add(op("upsert_node_type", definition(nt), null));
        }
        for (RelTypeDef rt : rts.values()) {
            var cur = (rt.systemKey() != null ? existing.relTypeBySystem(rt.systemKey()) : java.util.Optional.<RelTypeDef>empty())
                    .or(() -> existing.relType(rt.name())).orElse(null);
            if (cur != null) {
                boolean sysMissing = rt.systemKey() != null && cur.systemKey() == null;
                List<RelTypeDef.Endpoint> allowed = cur.allowed();
                // an empty list means "any pair": nothing to add
                if (cur.allowed() != null && !cur.allowed().isEmpty() && rt.allowed() != null && !rt.allowed().isEmpty()) {
                    var union = new LinkedHashSet<>(cur.allowed());
                    for (var e : rt.allowed()) {
                        // pairs naming types the project lacks (e.g. another language) are skipped
                        if (endpointKnown(existing, e.from()) && endpointKnown(existing, e.to())) union.add(e);
                    }
                    allowed = List.copyOf(union);
                }
                boolean pairsAdded = !Objects.equals(allowed, cur.allowed());
                if (!pairsAdded && !sysMissing) continue;
                RelTypeDef merged = new RelTypeDef(null, null, cur.name(), cur.base(), cur.description(), cur.symmetric(), cur.inverseName(),
                        cur.temporal(), allowed, cur.systemKey() != null ? cur.systemKey() : rt.systemKey());
                ops.add(op("upsert_rel_type", definition(merged), cur.id()));
                continue;
            }
            ops.add(op("upsert_rel_type", definition(rt), null));
        }
        return ops;
    }

    private static boolean endpointKnown(SchemaStore.Cache cache, String pattern) {
        return pattern == null || pattern.equals("*") || pattern.startsWith("@") || cache.nodeType(pattern).isPresent();
    }

    private static List<AttrDef> mergeAttrs(Collection<AttrDef> current, Collection<AttrDef> extra) {
        List<AttrDef> out = new ArrayList<>(current);
        Set<String> keys = new LinkedHashSet<>();
        current.forEach(a -> keys.add(a.key()));
        for (AttrDef a : extra) if (keys.add(a.key())) out.add(a);
        return out;
    }

    private Map<String, Object> definition(Object def) {
        Map<String, Object> m = json.convert(def, MAP);
        m.remove("id");
        m.remove("projectId");
        return m;
    }

    private Operation op(String op, Map<String, Object> definition, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        if (id != null) m.put("id", id);
        m.put("definition", definition);
        return json.convert(m, Operation.class);
    }
}
