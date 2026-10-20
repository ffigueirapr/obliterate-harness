package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.AttrDef;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.ObjectTypeDef;
import com.obliterateharness.domain.RelTypeDef;
import com.obliterateharness.graph.ChangeExecutor;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.JsonCodec;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.harness.HarnessLibrary.BeatDef;
import com.obliterateharness.harness.HarnessLibrary.ProfileDef;
import com.obliterateharness.harness.SystemNames.Field;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.store.AuditRepository;
import org.neo4j.driver.TransactionContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

/**
 * Translates a project's schema to another language (backlog item 10): the types, attributes, enum options, relations,
 * object types, applied templates and beats that came from the profile packs are renamed to the target language, and
 * the nodes' data is migrated with them (attribute keys, enum values, mirrored facts keep their rigidity/revealAt).
 * Types and relations the user created (not in the packs) are left as they are. Content (names, summaries, text) is
 * not translated: that is the LLM's job, through propose_changes.
 */
@Service
public class LocalizationService {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Report(String projectId, String from, String to, List<String> nodeTypes, List<String> relTypes, List<String> objectTypes,
                         int nodesMigrated, int templatesTranslated, List<String> untouched, List<String> notes) {}

    private record TypePair(NodeTypeDef src, NodeTypeDef tgt) {}

    private final HarnessLibrary library;
    private final Graph graph;
    private final SchemaStore schema;
    private final JsonCodec json;
    private final ProjectService projects;
    private final ChangeExecutor executor;
    private final AuditRepository audit;

    public LocalizationService(HarnessLibrary library, Graph graph, SchemaStore schema, JsonCodec json, ProjectService projects,
                               ChangeExecutor executor, AuditRepository audit) {
        this.library = library;
        this.graph = graph;
        this.schema = schema;
        this.json = json;
        this.projects = projects;
        this.executor = executor;
        this.audit = audit;
    }

    public Report localize(String projectId, Lang target) {
        var project = projects.get(projectId);
        Lang source = Lang.parse(project.locale(), Lang.pt);
        if (source == target) {
            return new Report(projectId, source.name(), target.name(), List.of(), List.of(), List.of(), 0, 0, List.of(),
                    List.of(t("The project is already in this language.", "O projeto já está nesse idioma.")));
        }
        String profile = project.profile() != null && library.profile(project.profile(), source).isPresent() ? project.profile() : "nucleo";
        List<ProfileDef> srcChain = library.chain(profile, source), tgtChain = library.chain(profile, target);

        // pack correspondences (same YAML, so lists line up by position)
        Map<String, TypePair> types = new LinkedHashMap<>();
        Map<String, NodeTypeDef> typesBySystem = new HashMap<>();
        Map<String, RelTypeDef> rels = new LinkedHashMap<>();
        Map<String, RelTypeDef> relsBySystem = new HashMap<>();
        Map<String, ObjectTypeDef[]> objects = new LinkedHashMap<>();
        Map<String, String> typeNames = new HashMap<>();
        for (int p = 0; p < srcChain.size(); p++) {
            ProfileDef s = srcChain.get(p), d = tgtChain.get(p);
            for (int i = 0; i < size(s.nodeTypes()); i++) {
                NodeTypeDef a = s.nodeTypes().get(i), b = d.nodeTypes().get(i);
                types.put(a.name().toLowerCase(), new TypePair(a, b));
                if (a.systemKey() != null) typesBySystem.put(a.systemKey(), a);
                typeNames.put(a.name().toLowerCase(), b.name());
            }
            for (int i = 0; i < size(s.relTypes()); i++) {
                RelTypeDef a = s.relTypes().get(i), b = d.relTypes().get(i);
                rels.put(a.name().toLowerCase(), b);
                if (a.systemKey() != null) relsBySystem.put(a.systemKey(), b);
            }
            for (int i = 0; i < size(s.objectTypes()); i++) {
                ObjectTypeDef a = s.objectTypes().get(i), b = d.objectTypes().get(i);
                objects.put(a.name().toLowerCase(), new ObjectTypeDef[]{a, b});
                typeNames.put(a.name().toLowerCase(), b.name());
            }
        }

        Report report = graph.write(tx -> migrate(tx, projectId, source, target, types, typesBySystem, rels, relsBySystem, objects, typeNames));
        audit.record(projectId, null, Actor.system.name(), List.of(new ChangeExecutor.OpResult(0, "localize", "project", projectId, null,
                Map.of("locale", source.name()), Map.of("locale", target.name(), "nodeTypes", report.nodeTypes(), "relTypes", report.relTypes()))));
        return report;
    }

    private Report migrate(TransactionContext tx, String projectId, Lang source, Lang target, Map<String, TypePair> types,
                           Map<String, NodeTypeDef> typesBySystem, Map<String, RelTypeDef> rels, Map<String, RelTypeDef> relsBySystem,
                           Map<String, ObjectTypeDef[]> objects, Map<String, String> typeNames) {
        var cache = schema.cache(tx, projectId);
        List<String> nodeChanges = new ArrayList<>(), relChanges = new ArrayList<>(), objChanges = new ArrayList<>(), untouched = new ArrayList<>(),
                notes = new ArrayList<>();
        UnaryMap typeName = n -> n == null ? null : typeNames.getOrDefault(n.toLowerCase(), n);

        // ---- object types (composite fields)
        Map<String, Map<String, String>> objFieldMaps = new HashMap<>();
        for (ObjectTypeDef ot : schema.objectTypes(tx, projectId)) {
            ObjectTypeDef[] pair = objects.get(ot.name().toLowerCase());
            if (pair == null) {
                untouched.add(ot.name());
                continue;
            }
            Map<String, String> fieldMap = keyMap(pair[0].fieldList(), pair[1].fieldList());
            objFieldMaps.put(pair[1].name().toLowerCase(), fieldMap);
            List<AttrDef> fields = new ArrayList<>();
            for (AttrDef f : ot.fieldList()) fields.add(translateAttr(f, findAttr(pair[0].fieldList(), pair[1].fieldList(), f.key()), typeName));
            schema.saveObjectType(tx, new ObjectTypeDef(ot.id(), projectId, pair[1].name(), pair[1].description(), fields));
            objChanges.add(ot.name() + " → " + pair[1].name());
        }

        // ---- node types and their nodes
        int migrated = 0;
        List<String> touchedNodes = new ArrayList<>();
        for (NodeTypeDef nt : schema.nodeTypes(tx, projectId)) {
            TypePair pair = types.get(nt.name().toLowerCase());
            if (pair == null && nt.systemKey() != null && typesBySystem.containsKey(nt.systemKey())) {
                pair = types.get(typesBySystem.get(nt.systemKey()).name().toLowerCase());
            }
            if (pair == null) {
                untouched.add(nt.name());
                continue;
            }
            Map<String, String> keys = keyMap(pair.src().attrs(), pair.tgt().attrs());
            // never rename onto a key the user already uses for something else
            keys.entrySet().removeIf(e -> !e.getKey().equals(e.getValue()) && nt.attr(e.getValue()).isPresent());
            Map<String, Map<String, String>> enums = new HashMap<>();
            Map<String, String> composite = new HashMap<>();
            List<AttrDef> attrs = new ArrayList<>();
            for (AttrDef a : nt.attrs()) {
                AttrDef tgt = keys.containsKey(a.key()) ? findAttr(pair.src().attrs(), pair.tgt().attrs(), a.key()) : null;
                AttrDef srcDef = pair.src().attr(a.key()).orElse(null);
                if (tgt != null && srcDef != null && srcDef.options() != null && tgt.options() != null && srcDef.options().size() == tgt.options().size()) {
                    Map<String, String> em = new HashMap<>();
                    for (int i = 0; i < srcDef.options().size(); i++) em.put(srcDef.options().get(i), tgt.options().get(i));
                    enums.put(a.key(), em);
                }
                AttrDef translated = translateAttr(a, tgt, typeName);
                if (translated.objectType() != null) composite.put(a.key(), translated.objectType().toLowerCase());
                attrs.add(translated);
            }
            String newName = pair.tgt().name();
            if (!newName.equalsIgnoreCase(nt.name()) && cache.nodeType(newName).filter(o -> !o.id().equals(nt.id())).isPresent()) {
                notes.add(t("Type '", "Tipo '") + nt.name() + t("' kept its name: the project already has a type named '", "' manteve o nome: o projeto já tem um tipo chamado '")
                        + newName + "'");
                newName = nt.name();
            }
            schema.saveNodeType(tx, new NodeTypeDef(nt.id(), projectId, newName, nt.base(), pair.tgt().description() != null ? pair.tgt().description() : nt.description(),
                    nt.color(), nt.strict(), attrs, nt.systemKey() != null ? nt.systemKey() : pair.tgt().systemKey()));
            nodeChanges.add(nt.name() + " → " + newName);

            var nodes = tx.run("MATCH (n:N {projectId:$p, typeId:$t}) RETURN n.id AS id, n.attrs AS attrs, n.attrMeta AS meta",
                    params("p", projectId, "t", nt.id())).list();
            for (var r : nodes) {
                String id = r.get("id").asString();
                Map<String, Object> old = json.readMap(r.get("attrs").asString(null));
                Map<String, Object> neu = new LinkedHashMap<>();
                for (var e : old.entrySet()) {
                    String k = keys.getOrDefault(e.getKey(), e.getKey());
                    Object v = e.getValue();
                    var em = enums.get(e.getKey());
                    if (em != null && v instanceof String s) v = em.getOrDefault(s, s);
                    if (em != null && v instanceof List<?> l) v = l.stream().map(x -> x instanceof String s ? em.getOrDefault(s, s) : x).toList();
                    String objName = composite.get(e.getKey());
                    if (objName != null && objFieldMaps.containsKey(objName)) v = renameKeys(v, objFieldMaps.get(objName));
                    neu.put(k, v);
                }
                Map<String, Object> oldMeta = json.readMap(r.get("meta").asString(null));
                Map<String, Object> newMeta = new LinkedHashMap<>();
                oldMeta.forEach((k, v) -> newMeta.put(keys.getOrDefault(k, k), v));
                tx.run("MATCH (n:N {id:$id}) SET n.attrs=$attrs, n.attrMeta=$meta, n.attrsText=$text, n.typeName=$type",
                        params("id", id, "attrs", json.write(neu), "meta", json.write(newMeta), "text", attrsText(neu), "type", newName)).consume();
                for (var e : keys.entrySet()) {
                    if (e.getKey().equals(e.getValue())) continue;
                    tx.run("MATCH (n:N {id:$id})-[r:R {kind:'attr', attrKey:$old}]->() SET r.attrKey=$new, r.name=$new",
                            params("id", id, "old", e.getKey(), "new", e.getValue())).consume();
                    // mirrored facts keep their rigidity / revealAt: rename them instead of recreating
                    tx.run("""
                            MATCH (f:Fact {originId:$id, originKind:'attr', originKey:$old})
                            SET f.originKey=$new, f.id='f_' + $id + '__' + $new
                            """, params("id", id, "old", e.getKey(), "new", e.getValue())).consume();
                }
                touchedNodes.add(id);
                migrated++;
            }
        }

        // ---- relation types
        for (RelTypeDef rt : schema.relTypes(tx, projectId)) {
            if (SchemaStore.CONTAINS.equals(rt.name())) continue;
            RelTypeDef tgt = rels.get(rt.name().toLowerCase());
            if (tgt == null && rt.systemKey() != null) tgt = relsBySystem.get(rt.systemKey());
            if (tgt == null) {
                untouched.add(rt.name());
                // still fix endpoints that name renamed types
                List<RelTypeDef.Endpoint> allowed = mapEndpoints(rt.allowed(), typeName);
                if (!Objects.equals(allowed, rt.allowed())) {
                    schema.saveRelType(tx, new RelTypeDef(rt.id(), projectId, rt.name(), rt.base(), rt.description(), rt.symmetric(), rt.inverseName(),
                            rt.temporal(), allowed, rt.systemKey()));
                }
                continue;
            }
            String newName = tgt.name();
            String clash = newName;
            if (!newName.equalsIgnoreCase(rt.name()) && schema.relTypes(tx, projectId).stream().anyMatch(o -> o.name().equalsIgnoreCase(clash) && !o.id().equals(rt.id()))) {
                notes.add(t("Relation '", "Relação '") + rt.name() + t("' kept its name: another relation is already called '", "' manteve o nome: outra relação já se chama '") + newName + "'");
                newName = rt.name();
            }
            schema.saveRelType(tx, new RelTypeDef(rt.id(), projectId, newName, rt.base(), tgt.description() != null ? tgt.description() : rt.description(),
                    rt.symmetric(), tgt.inverseName() != null ? tgt.inverseName() : rt.inverseName(), rt.temporal(), mapEndpoints(rt.allowed(), typeName),
                    rt.systemKey() != null ? rt.systemKey() : tgt.systemKey()));
            tx.run("MATCH ()-[r:R {projectId:$p, relTypeId:$id}]->() SET r.name=$name", params("p", projectId, "id", rt.id(), "name", newName)).consume();
            relChanges.add(rt.name() + " → " + newName);
        }

        // ---- applied templates and their beats
        var fresh = schema.cache(tx, projectId);
        NodeTypeDef tplType = fresh.nodeTypeBySystem(Reserved.TEMPLATE).orElse(null);
        NodeTypeDef beatType = fresh.nodeTypeBySystem(Reserved.BEAT).orElse(null);
        String beatOf = fresh.relTypeBySystem(Reserved.BEAT_OF).map(RelTypeDef::id).orElse("__none__");
        int templates = 0;
        if (tplType != null && beatType != null) {
            var tpls = tx.run("MATCH (t:N {projectId:$p, typeId:$tt}) RETURN t.id AS id, t.attrs AS attrs", params("p", projectId, "tt", tplType.id())).list();
            for (var r : tpls) {
                String tid = r.get("id").asString();
                Map<String, Object> ta = json.readMap(r.get("attrs").asString(null));
                Object unitType = Field.UNIT_TYPE.read(ta);
                if (unitType instanceof String u) ta.put(Field.UNIT_TYPE.keyFor(tplType), typeName.apply(u));
                Object tplId = Field.TEMPLATE_ID.read(ta);
                boolean builtin = tplId instanceof String s && library.isBuiltinTemplate(s);
                if (builtin) {
                    var src = I18n.with(source, () -> library.requireTemplate((String) tplId, source));
                    var dst = I18n.with(target, () -> library.requireTemplate((String) tplId, target));
                    if (dst.source() != null) ta.put(Field.SOURCE.keyFor(tplType), dst.source());
                    tx.run("MATCH (t:N {id:$id}) SET t.name=$name, t.summary=$summary, t.attrs=$attrs, t.attrsText=$text",
                            params("id", tid, "name", dst.name(), "summary", dst.description(), "attrs", json.write(ta), "text", attrsText(ta))).consume();
                    var beats = tx.run("MATCH (b:N)-[:R {relTypeId:$bo}]->(:N {id:$t}) RETURN b.id AS id, b.attrs AS attrs", params("bo", beatOf, "t", tid)).list();
                    for (var br : beats) {
                        Map<String, Object> ba = json.readMap(br.get("attrs").asString(null));
                        Object key = Field.BEAT_KEY.read(ba);
                        int idx = -1;
                        for (int i = 0; i < src.beats().size(); i++) if (Objects.equals(src.beats().get(i).key(), key)) idx = i;
                        if (idx < 0 || idx >= dst.beats().size()) continue;
                        BeatDef db = dst.beats().get(idx);
                        ba.put(Field.BEAT_KEY.keyFor(beatType), db.key());
                        if (db.function() != null) ba.put(Field.FUNCTION.keyFor(beatType), db.function());
                        if (db.criteria() != null) ba.put(Field.CRITERIA.keyFor(beatType), db.criteria());
                        tx.run("MATCH (b:N {id:$id}) SET b.name=$name, b.summary=$summary, b.attrs=$attrs, b.attrsText=$text",
                                params("id", br.get("id").asString(), "name", db.name(), "summary", db.function(), "attrs", json.write(ba),
                                        "text", attrsText(ba))).consume();
                    }
                    templates++;
                } else {
                    tx.run("MATCH (t:N {id:$id}) SET t.attrs=$attrs", params("id", tid, "attrs", json.write(ta))).consume();
                }
            }
        }

        // ---- project directives that filter by type name
        var dirs = tx.run("MATCH (d:Directive {projectId:$p}) RETURN d.id AS id, d.types AS types", Map.of("p", projectId)).list();
        for (var r : dirs) {
            List<String> ty = r.get("types").asList(v -> v.asString(), List.of());
            List<String> mapped = ty.stream().map(typeName::apply).toList();
            if (!mapped.equals(ty)) tx.run("MATCH (d:Directive {id:$id}) SET d.types=$t", params("id", r.get("id").asString(), "t", mapped)).consume();
        }

        tx.run("MATCH (p:Project {id:$id}) SET p.locale=$l", params("id", projectId, "l", target.name())).consume();
        // regenerate mirrored fact texts (they carry type names and labels)
        I18n.with(target, () -> {
            executor.resyncFacts(tx, projectId, touchedNodes);
            return null;
        });
        notes.add(t("Content (names, summaries, texts) was not translated; ask the LLM to translate it with propose_changes if you want.",
                "O conteúdo (nomes, resumos, textos) não foi traduzido; peça à LLM para traduzir com propose_changes se quiser."));
        return new Report(projectId, source.name(), target.name(), nodeChanges, relChanges, objChanges, migrated, templates, untouched, notes);
    }

    // ------------------------------------------------------------------ helpers

    private interface UnaryMap extends java.util.function.UnaryOperator<String> {}

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    /** srcKey → tgtKey for attributes at the same position. */
    private static Map<String, String> keyMap(List<AttrDef> src, List<AttrDef> tgt) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(src.size(), tgt.size()); i++) m.put(src.get(i).key(), tgt.get(i).key());
        return m;
    }

    private static AttrDef findAttr(List<AttrDef> src, List<AttrDef> tgt, String srcKey) {
        for (int i = 0; i < Math.min(src.size(), tgt.size()); i++) if (src.get(i).key().equals(srcKey)) return tgt.get(i);
        return null;
    }

    /** The project's attribute with key/label/description/options from the target pack, and type names translated. */
    private static AttrDef translateAttr(AttrDef a, AttrDef tgt, UnaryMap typeName) {
        String refType = a.refType() == null || a.refType().startsWith("@") || "*".equals(a.refType()) ? a.refType() : typeName.apply(a.refType());
        String objectType = a.objectType() == null ? null : typeName.apply(a.objectType());
        if (tgt == null) {
            return new AttrDef(a.key(), a.label(), a.dataType(), a.required(), a.description(), a.options(), a.itemType(), refType, objectType,
                    a.jsonSchema(), a.maxLength(), a.defaultCriticality(), a.role());
        }
        return new AttrDef(tgt.key(), tgt.label(), a.dataType(), a.required(), tgt.description() != null ? tgt.description() : a.description(),
                a.options() != null && tgt.options() != null && a.options().size() == tgt.options().size() ? tgt.options() : a.options(),
                a.itemType(), refType, objectType, a.jsonSchema(), a.maxLength(), a.defaultCriticality(), a.role() != null ? a.role() : tgt.role());
    }

    private static List<RelTypeDef.Endpoint> mapEndpoints(List<RelTypeDef.Endpoint> allowed, UnaryMap typeName) {
        if (allowed == null) return null;
        return allowed.stream().map(e -> new RelTypeDef.Endpoint(endpoint(e.from(), typeName), endpoint(e.to(), typeName))).toList();
    }

    private static String endpoint(String p, UnaryMap typeName) {
        return p == null || p.equals("*") || p.startsWith("@") ? p : typeName.apply(p);
    }

    @SuppressWarnings("unchecked")
    private static Object renameKeys(Object v, Map<String, String> keys) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, x) -> out.put(keys.getOrDefault(k, k), x));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(x -> renameKeys(x, keys)).toList();
        return v;
    }

    private static String attrsText(Map<String, Object> attrs) {
        StringBuilder sb = new StringBuilder();
        attrs.forEach((k, v) -> {
            if (v instanceof String || v instanceof Number || v instanceof Boolean) sb.append(k).append(": ").append(v).append("; ");
        });
        return sb.toString();
    }
}
