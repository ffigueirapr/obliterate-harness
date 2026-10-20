package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.BaseKind;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Ids;
import com.obliterateharness.domain.Manuscript;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.Operation;
import com.obliterateharness.domain.Views;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.GraphReader;
import com.obliterateharness.graph.JsonCodec;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.harness.HarnessLibrary.BeatDef;
import com.obliterateharness.harness.HarnessLibrary.TemplateDef;
import com.obliterateharness.harness.SystemNames.Field;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ChangesetService;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.store.CustomTemplateRepository;
import org.neo4j.driver.SimpleQueryRunner;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

/**
 * Structure templates applied to a project and coverage analysis: which beats already have a unit, which are off
 * position and which are missing for how far the story has gone. Also user-made templates, including templates
 * derived from an existing work (e.g. a dissected book).
 */
@Service
public class TemplateService {

    /** Position tolerance (fraction of the work) for a beat to count as in place. */
    static final double TOLERANCE = 0.12;

    public record UnitPos(String id, String name, double position) {}

    /** @param status ok, off_position, missing, optional_missing or future */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record BeatStatus(String beatId, String key, String name, double expected, String function, String criteria,
                             boolean required, List<UnitPos> units, String status, String note) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Coverage(String templateNodeId, String templateName, String unitType, String scopeUnitId, int unitsWritten, int unitsPlanned,
                           double progress, List<BeatStatus> beats, List<Views.Ref> unitsWithoutBeat, List<String> summary) {}

    public record AppliedTemplate(String id, String name, String templateId, String unitType, Integer plannedUnits, String scopeUnitId, int beats) {}

    private final HarnessLibrary library;
    private final ProfileService profiles;
    private final ChangesetService changesets;
    private final ProjectService projects;
    private final Graph graph;
    private final SchemaStore schema;
    private final JsonCodec json;
    private final GraphReader reader;
    private final SystemNames names;
    private final CustomTemplateRepository custom;

    public TemplateService(HarnessLibrary library, ProfileService profiles, ChangesetService changesets, ProjectService projects,
                           Graph graph, SchemaStore schema, JsonCodec json, GraphReader reader, SystemNames names, CustomTemplateRepository custom) {
        this.library = library;
        this.profiles = profiles;
        this.changesets = changesets;
        this.projects = projects;
        this.graph = graph;
        this.schema = schema;
        this.json = json;
        this.reader = reader;
        this.names = names;
        this.custom = custom;
    }

    /**
     * Creates the Template node and one Beat node per beat, linked by the beat-of relation.
     *
     * @param unitType     unit type the template measures (e.g. Chapter); defaults to the profile's
     * @param plannedUnits planned total of those units; without it, positions are relative to what exists
     */
    public ChangesetService.Proposal apply(String projectId, String templateId, String unitType, Integer plannedUnits, String scopeUnitId, Actor actor) {
        Lang lang = projects.locale(projectId);
        TemplateDef tpl = library.requireTemplate(templateId, lang);
        var project = projects.get(projectId);
        String unit = unitType;
        if (unit == null || unit.isBlank()) {
            unit = library.profile(project.profile(), lang).map(HarnessLibrary.ProfileDef::templateUnit).orElse(null);
        }
        if (unit == null) {
            throw new DomainException(t("Pass unitType (the narrative unit type the template measures, e.g. Chapter)",
                    "Informe unitType (o tipo de unidade narrativa que o template mede, ex.: Capítulo)"));
        }
        final String unitName = unit;
        var nodeType = graph.read(tx -> schema.cache(tx, projectId)).nodeType(unitName)
                .orElseThrow(() -> new DomainException(t("Type '", "Tipo '") + unitName + t("' does not exist in the project", "' não existe no projeto")));
        if (nodeType.baseKind() != BaseKind.Unit) {
            throw new DomainException("'" + unitName + t("' is not a narrative unit (base Unit)", "' não é uma unidade narrativa (base Unit)"));
        }
        if (plannedUnits != null && plannedUnits < 1) throw new DomainException(t("plannedUnits must be >= 1", "plannedUnits deve ser >= 1"));
        if (scopeUnitId != null && !scopeUnitId.isBlank()) {
            var scope = graph.read(tx -> reader.node(tx, projectId, scopeUnitId, Views.Detail.summary));
            if (!"Unit".equals(scope.base())) throw new DomainException(t("scopeUnitId must be a narrative unit", "scopeUnitId deve ser uma unidade narrativa"));
        }

        profiles.ensureCore(projectId, Reserved.TEMPLATE_KIT);
        var kit = graph.read(tx -> new Object[]{
                names.nodeType(tx, projectId, Reserved.TEMPLATE).orElse(null),
                names.nodeType(tx, projectId, Reserved.BEAT).orElse(null),
                names.relTypeName(tx, projectId, Reserved.BEAT_OF).orElse(null)});
        NodeTypeDef tplType = (NodeTypeDef) kit[0], beatType = (NodeTypeDef) kit[1];
        String beatOf = (String) kit[2];
        if (tplType == null || beatType == null || beatOf == null) {
            throw new DomainException(t("The project lacks the template kit (Template, Beat, beat-of relation); apply a profile first",
                    "O projeto não tem o kit de templates (Template, Beat, relação de beat); aplique um perfil antes"));
        }

        List<Operation> ops = new ArrayList<>();
        Map<String, Object> tAttrs = new LinkedHashMap<>();
        tAttrs.put(Field.TEMPLATE_ID.keyFor(tplType), tpl.id());
        tAttrs.put(Field.UNIT_TYPE.keyFor(tplType), nodeType.name());
        if (plannedUnits != null) tAttrs.put(Field.PLANNED_UNITS.keyFor(tplType), plannedUnits);
        if (tpl.source() != null) tAttrs.put(Field.SOURCE.keyFor(tplType), truncate(tpl.source(), 250));
        if (scopeUnitId != null && !scopeUnitId.isBlank()) tAttrs.put(Field.SCOPE.keyFor(tplType), scopeUnitId);
        ops.add(op(Map.of("op", "create_node", "ref", "$tpl", "type", tplType.name(), "space", "templates", "name", tpl.name(),
                "summary", nz(tpl.description()), "attributes", tAttrs)));
        int i = 0;
        for (BeatDef b : tpl.beats()) {
            String ref = "$b" + (i++);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put(Field.BEAT_KEY.keyFor(beatType), truncate(b.key(), 250));
            a.put(Field.POSITION.keyFor(beatType), b.position());
            if (b.function() != null) a.put(Field.FUNCTION.keyFor(beatType), b.function());
            if (b.criteria() != null) a.put(Field.CRITERIA.keyFor(beatType), b.criteria());
            if (b.devices() != null && !b.devices().isEmpty()) a.put(Field.DEVICES.keyFor(beatType), b.devices().stream().map(d -> truncate(d, 250)).toList());
            a.put(Field.REQUIRED.keyFor(beatType), b.required() == null || b.required());
            ops.add(op(Map.of("op", "create_node", "ref", ref, "type", beatType.name(), "space", "templates", "name", truncate(b.name(), 250),
                    "summary", nz(b.function()), "attributes", a)));
            ops.add(op(Map.of("op", "create_edge", "relType", beatOf, "from", ref, "to", "$tpl")));
        }
        return changesets.propose(projectId, actor, null, "Template " + tpl.name(), ops);
    }

    public List<AppliedTemplate> applied(String projectId) {
        return graph.read(tx -> {
            String tplType = names.nodeTypeId(tx, projectId, Reserved.TEMPLATE);
            String beatOf = names.relTypeId(tx, projectId, Reserved.BEAT_OF);
            return tx.run("""
                    MATCH (t:N {projectId:$p, typeId:$tpl})
                    OPTIONAL MATCH (b:N)-[:R {relTypeId:$beatOf}]->(t)
                    RETURN t, count(b) AS beats ORDER BY t.createdAt
                    """, params("p", projectId, "tpl", tplType, "beatOf", beatOf)).list(r -> {
                Map<String, Object> a = json.readMap(r.get("t").get("attrs").asString(null));
                Object planned = Field.PLANNED_UNITS.read(a);
                return new AppliedTemplate(r.get("t").get("id").asString(), r.get("t").get("name").asString(),
                        (String) Field.TEMPLATE_ID.read(a), (String) Field.UNIT_TYPE.read(a),
                        planned instanceof Number n ? n.intValue() : null, (String) Field.SCOPE.read(a), r.get("beats").asInt());
            });
        });
    }

    public Coverage coverage(String projectId, String templateNodeId) {
        return graph.read(tx -> coverage(tx, projectId, templateNodeId));
    }

    public List<Coverage> coverageAll(String projectId) {
        return applied(projectId).stream().map(x -> coverage(projectId, x.id())).toList();
    }

    Coverage coverage(SimpleQueryRunner q, String projectId, String templateNodeId) {
        String tplType = names.nodeTypeId(q, projectId, Reserved.TEMPLATE);
        var trows = q.run("MATCH (t:N {id:$id, projectId:$p, typeId:$tpl}) RETURN t",
                params("id", templateNodeId, "p", projectId, "tpl", tplType)).list(r -> r.get("t"));
        if (trows.isEmpty()) {
            throw new DomainException("'" + templateNodeId + t("' is not a template applied to this project (see milestone_coverage / templates)",
                    "' não é um template aplicado neste projeto (veja milestone_coverage / templates)"));
        }
        Value tv = trows.getFirst();
        Map<String, Object> tAttrs = json.readMap(tv.get("attrs").asString(null));
        String unitType = (String) Field.UNIT_TYPE.read(tAttrs);
        Object plannedRaw = Field.PLANNED_UNITS.read(tAttrs);
        String scope = (String) Field.SCOPE.read(tAttrs);

        List<Value> units = scope == null
                ? q.run("MATCH (u:N:Unit {projectId:$p, typeName:$type}) RETURN u ORDER BY u.sortKey",
                params("p", projectId, "type", unitType)).list(r -> r.get("u"))
                : q.run("MATCH (:N {id:$s})-[:R*1..10 {kind:'structure'}]->(u:N:Unit {typeName:$type}) RETURN DISTINCT u ORDER BY u.sortKey",
                params("s", scope, "type", unitType)).list(r -> r.get("u"));
        int written = units.size();
        int planned = Math.max(written, plannedRaw instanceof Number n ? n.intValue() : written);
        Map<String, Double> pos = new LinkedHashMap<>();
        Map<String, String> unitNames = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            String id = units.get(i).get("id").asString();
            pos.put(id, planned <= 1 ? 0.0 : (double) i / (planned - 1));
            unitNames.put(id, units.get(i).get("name").asString());
        }
        double progress = written == 0 ? 0 : (planned <= 1 ? 1.0 : (double) (written - 1) / (planned - 1));

        var beatRows = q.run("""
                MATCH (b:N)-[:R {relTypeId:$beatOf}]->(t:N {id:$t})
                OPTIONAL MATCH (u:N)-[:R {relTypeId:$mapsTo}]->(b)
                RETURN b, collect(u.id) AS units
                """, params("beatOf", names.relTypeId(q, projectId, Reserved.BEAT_OF), "t", templateNodeId,
                "mapsTo", names.relTypeId(q, projectId, Reserved.MAPS_TO_BEAT))).list();
        List<BeatStatus> beats = new ArrayList<>();
        java.util.Set<String> mappedUnits = new java.util.HashSet<>();
        for (var r : beatRows) {
            Value b = r.get("b");
            Map<String, Object> a = json.readMap(b.get("attrs").asString(null));
            double expected = Field.POSITION.read(a) instanceof Number n ? n.doubleValue() : 0;
            boolean required = !Boolean.FALSE.equals(Field.REQUIRED.read(a));
            List<UnitPos> ups = new ArrayList<>();
            for (Object o : r.get("units").asList()) {
                String uid = (String) o;
                if (pos.containsKey(uid)) {
                    ups.add(new UnitPos(uid, unitNames.get(uid), round(pos.get(uid))));
                    mappedUnits.add(uid);
                }
            }
            String status;
            String note = null;
            if (!ups.isEmpty()) {
                double best = ups.stream().mapToDouble(u -> Math.abs(u.position() - expected)).min().orElse(1);
                status = best <= TOLERANCE ? "ok" : "off_position";
                if (!"ok".equals(status)) {
                    note = t("expected around ", "esperado em ~") + pct(expected) + t(", it is at ", ", está em ") + pct(ups.getFirst().position());
                }
            } else if (written > 0 && expected <= progress + TOLERANCE / 2) {
                status = required ? "missing" : "optional_missing";
                note = t("the story is already past ", "a história já passou de ") + pct(expected) + t(" without this beat", " sem este beat");
            } else {
                status = "future";
            }
            beats.add(new BeatStatus(b.get("id").asString(), str(Field.BEAT_KEY.read(a)), b.get("name").asString(), expected,
                    str(Field.FUNCTION.read(a)), str(Field.CRITERIA.read(a)), required, ups, status, note));
        }
        beats.sort(java.util.Comparator.comparingDouble(BeatStatus::expected));
        List<Views.Ref> without = units.stream().map(u -> u.get("id").asString()).filter(id -> !mappedUnits.contains(id))
                .map(id -> new Views.Ref(id, unitNames.get(id))).toList();
        List<String> summary = new ArrayList<>();
        long missing = beats.stream().filter(b -> b.status().equals("missing")).count();
        long off = beats.stream().filter(b -> b.status().equals("off_position")).count();
        long ok = beats.stream().filter(b -> b.status().equals("ok")).count();
        summary.add(ok + t(" of ", " de ") + beats.size() + t(" beats in place; ", " beats no lugar; ") + missing + t(" missing; ", " faltando; ")
                + off + t(" off position.", " fora de posição."));
        beats.stream().filter(b -> b.status().equals("future")).findFirst()
                .ifPresent(b -> summary.add(t("Next expected beat: ", "Próximo beat esperado: ") + b.name() + " (~" + pct(b.expected()) + ")."));
        if (plannedRaw == null) {
            summary.add(t("No planned total of units: positions are relative to what exists (", "Sem total planejado de unidades: posições relativas ao que já existe (")
                    + written + ").");
        }
        return new Coverage(templateNodeId, tv.get("name").asString(), unitType, scope, written, planned, round(progress), beats, without, summary);
    }

    // ------------------------------------------------------------------ custom templates

    /**
     * Derives a template from an existing work: one beat per unit of the given type, in narrative order, with the position,
     * function (summary / goal) and criteria (beats that unit fulfilled in the source, hooks it plants or pays, child units).
     */
    public TemplateDef derive(String fromProjectId, String unitType, String name, String description, boolean includeChildren) {
        var source = projects.get(fromProjectId);
        TemplateDef derived = graph.read(tx -> {
            var cache = schema.cache(tx, fromProjectId);
            NodeTypeDef ut = cache.requireNodeType(unitType);
            if (ut.baseKind() != BaseKind.Unit) {
                throw new DomainException("'" + unitType + t("' is not a narrative unit (base Unit)", "' não é uma unidade narrativa (base Unit)"));
            }
            var units = reader.unitsInOrder(tx, fromProjectId, ut.name(), null, Views.Detail.full);
            if (units.isEmpty()) throw new DomainException(t("The project has no units of type '", "O projeto não tem unidades do tipo '") + ut.name() + "'");
            String mapsTo = names.relTypeId(tx, fromProjectId, Reserved.MAPS_TO_BEAT);
            String plants = names.relTypeId(tx, fromProjectId, Reserved.PLANTS);
            String pays = names.relTypeId(tx, fromProjectId, Reserved.PAYS);
            List<BeatDef> beats = new ArrayList<>();
            int n = units.size();
            for (int i = 0; i < n; i++) {
                var u = units.get(i);
                Map<String, Object> attrs = u.attributes() == null ? Map.of() : u.attributes();
                String function = firstText(u.summary(), attrs.get("goal"), attrs.get("objective"), attrs.get("objetivo"));
                if (function == null) {
                    function = Manuscript.textAttr(ut).map(a -> attrs.get(a.key())).map(v -> truncate(String.valueOf(v), 300)).orElse(null);
                }
                List<String> criteria = new ArrayList<>();
                var fulfilled = tx.run("MATCH (:N {id:$u})-[:R {relTypeId:$r}]->(b:N) RETURN b.name AS n", params("u", u.id(), "r", mapsTo))
                        .list(r -> r.get("n").asString());
                if (!fulfilled.isEmpty()) criteria.add(t("Fulfills: ", "Cumpre: ") + String.join(", ", fulfilled) + ".");
                var planted = tx.run("MATCH (:N {id:$u})-[:R {relTypeId:$r}]->(h:N) RETURN h.name AS n", params("u", u.id(), "r", plants))
                        .list(r -> r.get("n").asString());
                if (!planted.isEmpty()) criteria.add(t("Plants: ", "Planta: ") + String.join(", ", planted) + ".");
                var paid = tx.run("MATCH (:N {id:$u})-[:R {relTypeId:$r}]->(h:N) RETURN h.name AS n", params("u", u.id(), "r", pays))
                        .list(r -> r.get("n").asString());
                if (!paid.isEmpty()) criteria.add(t("Pays off: ", "Paga: ") + String.join(", ", paid) + ".");
                if (includeChildren) {
                    var kids = reader.childUnits(tx, fromProjectId, u.id(), Views.Detail.summary);
                    if (!kids.isEmpty()) {
                        criteria.add(t("Parts: ", "Partes: ") + kids.stream()
                                .map(k -> k.name() + (k.summary() != null ? " — " + truncate(k.summary(), 140) : "")).reduce((x, y) -> x + "; " + y).orElse("") + ".");
                    }
                }
                List<String> devices = new ArrayList<>();
                devices.addAll(planted);
                beats.add(new BeatDef(slug(u.name(), i), u.name(), n <= 1 ? 0.0 : round((double) i / (n - 1)), function,
                        criteria.isEmpty() ? null : String.join(" ", criteria), devices.isEmpty() ? null : devices, true));
            }
            String tplName = name != null && !name.isBlank() ? name.trim() : t("Structure of ", "Estrutura de ") + source.name();
            String id = "tpl_" + slug(tplName, 0) + "_" + Ids.next("x").substring(2, 6);
            return new TemplateDef(id, tplName, description != null ? description
                    : t("Derived from ", "Derivado de ") + source.name() + " (" + n + " " + ut.name() + ")", "obra", List.of(),
                    source.name(), beats, true, projects.locale(fromProjectId).name());
        });
        custom.upsert(derived, derived.locale(), fromProjectId);
        return derived;
    }

    /** Creates or replaces a user-made template. */
    public TemplateDef upsert(TemplateDef def) {
        if (def.name() == null || def.name().isBlank()) throw new DomainException(t("Template name is required", "Nome do template é obrigatório"));
        if (def.beats() == null || def.beats().isEmpty()) throw new DomainException(t("A template needs at least one beat", "Um template precisa de ao menos um beat"));
        if (def.id() != null && library.isBuiltinTemplate(def.id())) {
            throw new DomainException(t("Built-in templates cannot be changed; use another id", "Templates embutidos não podem ser alterados; use outro id"));
        }
        for (BeatDef b : def.beats()) {
            if (b.name() == null || b.name().isBlank()) throw new DomainException(t("Every beat needs a name", "Todo beat precisa de nome"));
            if (b.position() < 0 || b.position() > 1) throw new DomainException(t("Beat position must be between 0 and 1: ", "Posição do beat deve estar entre 0 e 1: ") + b.name());
        }
        List<BeatDef> beats = new ArrayList<>();
        for (int i = 0; i < def.beats().size(); i++) {
            BeatDef b = def.beats().get(i);
            beats.add(new BeatDef(b.key() == null || b.key().isBlank() ? slug(b.name(), i) : b.key(), b.name(), b.position(), b.function(), b.criteria(),
                    b.devices(), b.required()));
        }
        String id = def.id() == null || def.id().isBlank() ? "tpl_" + slug(def.name(), 0) + "_" + Ids.next("x").substring(2, 6) : def.id();
        String locale = def.locale() == null ? com.obliterateharness.i18n.I18n.lang().name() : def.locale();
        var saved = new TemplateDef(id, def.name().trim(), def.description(), def.scope() == null ? "obra" : def.scope(), def.media(), def.source(),
                beats, true, locale);
        custom.upsert(saved, locale, null);
        return saved;
    }

    public void delete(String id) {
        if (library.isBuiltinTemplate(id)) throw new DomainException(t("Built-in templates cannot be deleted", "Templates embutidos não podem ser apagados"));
        if (!custom.delete(id)) throw new DomainException(t("Custom template '", "Template próprio '") + id + t("' does not exist", "' não existe"));
    }

    // ------------------------------------------------------------------ helpers

    private Operation op(Map<String, Object> m) {
        Map<String, Object> clean = new LinkedHashMap<>(m);
        clean.values().removeIf(v -> "".equals(v));
        return json.convert(clean, Operation.class);
    }

    private static String firstText(Object... values) {
        for (Object v : values) if (v instanceof String s && !s.isBlank()) return truncate(s, 400);
        return null;
    }

    static String slug(String s, int fallback) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "u" + fallback : n;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
