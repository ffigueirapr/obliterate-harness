package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.AttrDef;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Manuscript;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.NotFoundException;
import com.obliterateharness.domain.Operation;
import com.obliterateharness.domain.Views;
import com.obliterateharness.domain.Views.Detail;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.GraphReader;
import com.obliterateharness.graph.JsonCodec;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.service.ChangesetService;
import com.obliterateharness.store.TextVersionRepository;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.obliterateharness.graph.SchemaStore.params;
import static com.obliterateharness.i18n.I18n.t;

/**
 * Unit-level operations: manuscript text (set/append in chunks, versions, diff), cast in bulk, and validating a unit's
 * text against the canon.
 */
@Service
public class UnitService {

    public record TextWrite(String unitId, String attribute, long nodeVersion, int textVersion, long words, int characters, String changesetId) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record TextRead(String unitId, String unitName, String attribute, Integer textVersion, long words, String text) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Validation(
            Views.Ref unit,
            long words,
            List<Views.Ref> mentionedNotInCast,
            List<Views.Ref> castNotMentioned,
            List<String> possibleNewEntities,
            List<String> warnings,
            List<String> stateToCheck,
            List<Views.Fact> rulesToCheck,
            List<NarrativeService.Secret> secretsToCheck,
            List<String> directives,
            List<String> instructions
    ) {}

    private final Graph graph;
    private final GraphReader reader;
    private final SchemaStore schema;
    private final ChangesetService changesets;
    private final TextVersionRepository versions;
    private final NarrativeService narrative;
    private final GuidanceService guidance;
    private final SystemNames names;
    private final JsonCodec json;

    public UnitService(Graph graph, GraphReader reader, SchemaStore schema, ChangesetService changesets, TextVersionRepository versions,
                       NarrativeService narrative, GuidanceService guidance, SystemNames names, JsonCodec json) {
        this.graph = graph;
        this.reader = reader;
        this.schema = schema;
        this.changesets = changesets;
        this.versions = versions;
        this.narrative = narrative;
        this.guidance = guidance;
        this.names = names;
        this.json = json;
    }

    // ------------------------------------------------------------------ manuscript text

    private record Target(Views.Node unit, NodeTypeDef type, AttrDef attr) {}

    private Target target(String projectId, String unitId, String attribute) {
        return graph.read(tx -> {
            var unit = reader.node(tx, projectId, unitId, Detail.full);
            var type = schema.cache(tx, projectId).requireNodeType(unit.type());
            AttrDef attr = attribute != null && !attribute.isBlank()
                    ? type.attr(attribute).orElseThrow(() -> new DomainException(t("Attribute '", "Atributo '") + attribute
                    + t("' does not exist in type ", "' não existe no tipo ") + type.name()))
                    : Manuscript.textAttr(type).orElseThrow(() -> new DomainException(t("Type '", "Tipo '") + type.name()
                    + t("' has no manuscript attribute (role manuscript or a text_long named text/texto/script/roteiro); pass 'attribute'",
                    "' não tem atributo de manuscrito (role manuscript ou um text_long chamado text/texto/script/roteiro); informe 'attribute'")));
            if (!"text_long".equalsIgnoreCase(attr.dataType())) {
                throw new DomainException(t("Attribute '", "Atributo '") + attr.key() + t("' is not text_long", "' não é text_long"));
            }
            return new Target(unit, type, attr);
        });
    }

    /**
     * Sets or appends the unit's manuscript. Saves a version of each draft and recomputes the word count.
     *
     * @param mode replace (default) or append (adds the chunk at the end, separated by a blank line unless separator is given)
     */
    public TextWrite setText(String projectId, String unitId, String text, String mode, String attribute, String separator, String note,
                             Actor actor, String sessionId) {
        if (text == null) throw new DomainException(t("'text' is required", "'text' é obrigatório"));
        Target tg = target(projectId, unitId, attribute);
        String current = tg.unit().attributes() == null ? null : (String) tg.unit().attributes().get(tg.attr().key());
        boolean append = "append".equalsIgnoreCase(mode);
        if (mode != null && !mode.isBlank() && !append && !"replace".equalsIgnoreCase(mode)) {
            throw new DomainException(t("mode must be replace or append", "mode deve ser replace ou append"));
        }
        String sep = separator != null ? separator : "\n\n";
        String full = append && current != null && !current.isEmpty() ? current + sep + text : text;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(tg.attr().key(), full);
        var op = json.convert(Map.of("op", "update_node", "id", unitId, "expectedVersion", tg.unit().version(), "attributes", attrs), Operation.class);
        var proposal = changesets.propose(projectId, actor, sessionId,
                (append ? t("Append text: ", "Acrescentar texto: ") : t("Set text: ", "Gravar texto: ")) + tg.unit().name(), List.of(op));
        long words = Manuscript.countWords(full);
        int tv = versions.save(projectId, unitId, tg.attr().key(), full, (int) words, actor.name(), note);
        long nodeVersion = proposal.changes() == null || proposal.changes().isEmpty() || proposal.changes().getFirst().version() == null
                ? tg.unit().version() : proposal.changes().getFirst().version();
        return new TextWrite(unitId, tg.attr().key(), nodeVersion, tv, words, full.length(), proposal.changesetId());
    }

    public TextRead getText(String projectId, String unitId, Integer version, String attribute) {
        Target tg = target(projectId, unitId, attribute);
        if (version != null) {
            var v = versions.get(projectId, unitId, version).orElseThrow(() -> new NotFoundException(t("Text version ", "Versão de texto ") + version
                    + t(" does not exist for this unit", " não existe para esta unidade")));
            return new TextRead(unitId, tg.unit().name(), v.attrKey(), v.version(), v.words(), v.content());
        }
        String text = tg.unit().attributes() == null ? null : (String) tg.unit().attributes().get(tg.attr().key());
        var hist = versions.history(projectId, unitId);
        return new TextRead(unitId, tg.unit().name(), tg.attr().key(), hist.isEmpty() ? null : hist.getFirst().version(),
                Manuscript.countWords(text), text == null ? "" : text);
    }

    public List<TextVersionRepository.Version> history(String projectId, String unitId) {
        graph.read(tx -> reader.node(tx, projectId, unitId, Detail.id));
        return versions.history(projectId, unitId);
    }

    /** Diff between two saved drafts; "to" omitted = the current text. */
    public TextDiff.Result diff(String projectId, String unitId, int from, Integer to) {
        var a = versions.get(projectId, unitId, from).orElseThrow(() -> new NotFoundException(t("Text version ", "Versão de texto ") + from + t(" does not exist", " não existe")));
        String b = to == null ? getText(projectId, unitId, null, a.attrKey()).text()
                : versions.get(projectId, unitId, to).orElseThrow(() -> new NotFoundException(t("Text version ", "Versão de texto ") + to + t(" does not exist", " não existe"))).content();
        return TextDiff.diff(a.content(), b, 2);
    }

    // ------------------------------------------------------------------ cast

    /** Sets (replace) or extends (add) the unit's cast in one go. */
    public ChangesetService.Proposal setCast(String projectId, String unitId, List<String> nodeIds, String mode, Actor actor, String sessionId) {
        if (nodeIds == null) throw new DomainException(t("'nodeIds' is required", "'nodeIds' é obrigatório"));
        var unit = graph.read(tx -> reader.node(tx, projectId, unitId, Detail.summary));
        List<String> cast = new ArrayList<>(nodeIds);
        if ("add".equalsIgnoreCase(mode)) {
            var current = graph.read(tx -> tx.run("MATCH (m:N)-[:R {relTypeId:$rt}]->(:N {id:$u}) RETURN m.id AS id",
                    params("rt", names.relTypeId(tx, projectId, Reserved.APPEARS_IN), "u", unitId)).list(r -> r.get("id").asString()));
            var set = new LinkedHashSet<>(current);
            set.addAll(nodeIds);
            cast = new ArrayList<>(set);
        }
        var op = json.convert(Map.of("op", "update_node", "id", unitId, "expectedVersion", unit.version(), "cast", cast), Operation.class);
        return changesets.propose(projectId, actor, sessionId, t("Cast: ", "Elenco: ") + unit.name(), List.of(op));
    }

    // ------------------------------------------------------------------ validation

    private static final Set<String> STOP = Set.copyOf(List.of(
            "the", "a", "an", "and", "but", "or", "he", "she", "it", "they", "we", "i", "you", "his", "her", "their", "this", "that", "then", "when",
            "what", "where", "who", "why", "how", "there", "here", "in", "on", "at", "of", "for", "with", "as", "if", "no", "not", "yes", "so",
            "o", "os", "as", "um", "uma", "e", "mas", "ou", "ele", "ela", "eles", "elas", "nós", "eu", "você", "seu", "sua", "este", "esta", "isso",
            "então", "quando", "onde", "quem", "por", "porque", "como", "não", "sim", "lá", "aqui", "no", "na", "nos", "nas", "do", "da", "dos", "das",
            "de", "em", "para", "com", "se", "mr", "mrs", "sr", "sra", "dona", "dom", "god", "deus"));

    private static final Pattern PROPER = Pattern.compile("(?<![\\p{L}])(\\p{Lu}[\\p{L}'’-]+(?:\\s+(?:de|da|do|dos|das|of|the|van|von)?\\s*\\p{Lu}[\\p{L}'’-]+)*)");

    /**
     * Checks a unit's text against the graph. Deterministic findings (who is mentioned, who is in the cast, names not in
     * the graph, characters marked dead) plus a checklist (laws, norms, secrets, state, directives) for the LLM to judge.
     */
    public Validation validate(String projectId, String unitId, String text) {
        Target tg = target(projectId, unitId, null);
        String body = text != null ? text : (tg.unit().attributes() == null ? null : (String) tg.unit().attributes().get(tg.attr().key()));
        if (body == null || body.isBlank()) {
            throw new DomainException(t("The unit has no text to validate; pass 'text' or write it first (unit_text_set)",
                    "A unidade não tem texto para validar; passe 'text' ou grave antes (unit_text_set)"));
        }
        String norm = fold(body);
        var ctx = narrative.writingContext(projectId, unitId, 40000, 0, List.of("secrets"));
        return graph.read(tx -> {
            String appears = names.relTypeId(tx, projectId, Reserved.APPEARS_IN);
            var castIds = new LinkedHashSet<>(tx.run("MATCH (m:N)-[:R {relTypeId:$rt}]->(:N {id:$u}) RETURN m.id AS id",
                    params("rt", appears, "u", unitId)).list(r -> r.get("id").asString()));
            // every non-unit node of the project, to see who the text mentions
            var all = tx.run("""
                    MATCH (n:N {projectId:$p}) WHERE NOT n:Unit AND NOT n:Note
                    RETURN n.id AS id, n.name AS name, n.base AS base, n.attrs AS attrs, n.typeName AS type
                    """, Map.of("p", projectId)).list();
            List<Views.Ref> mentioned = new ArrayList<>();
            Set<String> mentionedIds = new LinkedHashSet<>();
            Set<String> knownFolded = new LinkedHashSet<>();
            List<String> warnings = new ArrayList<>();
            for (var r : all) {
                String name = r.get("name").asString();
                knownFolded.add(fold(name));
                for (String part : name.split("\\s+")) if (part.length() >= 4) knownFolded.add(fold(part));
                if (mentions(norm, name, "Agent".equals(r.get("base").asString(null)))) {
                    String id = r.get("id").asString();
                    mentioned.add(new Views.Ref(id, name));
                    mentionedIds.add(id);
                    Map<String, Object> a = json.readMap(r.get("attrs").asString(null));
                    Object status = a.get("status");
                    if (status != null && Set.of("dead", "morto", "morta").contains(status.toString().toLowerCase(Locale.ROOT))) {
                        warnings.add(name + t(" is marked as dead but is mentioned (flashback/memory? if not, it is a plot hole)",
                                " está marcado como morto mas é citado (flashback/memória? se não, é furo)"));
                    }
                }
            }
            List<Views.Ref> notInCast = mentioned.stream().filter(m -> !castIds.contains(m.id())).toList();
            List<Views.Ref> castNotMentioned = new ArrayList<>();
            if (!castIds.isEmpty()) {
                for (var n : reader.nodes(tx, projectId, castIds, Detail.id)) {
                    if (!mentions(norm, n.name(), true)) castNotMentioned.add(new Views.Ref(n.id(), n.name()));
                }
            }
            // capitalized names that repeat and are not in the graph
            Map<String, Integer> counts = new LinkedHashMap<>();
            Matcher m = PROPER.matcher(body);
            while (m.find()) {
                String cand = m.group(1).trim();
                int start = m.start(1);
                if (sentenceStart(body, start) && !cand.contains(" ")) continue;
                String f = fold(cand);
                if (STOP.contains(f) || f.length() < 3 || knownFolded.contains(f)) continue;
                boolean partKnown = false;
                for (String p : f.split("\\s+")) if (knownFolded.contains(p)) partKnown = true;
                if (partKnown) continue;
                counts.merge(cand, 1, Integer::sum);
            }
            List<String> newEntities = counts.entrySet().stream().filter(e -> e.getValue() >= 2 || e.getKey().contains(" "))
                    .sorted((a, b) -> b.getValue() - a.getValue()).limit(20).map(e -> e.getKey() + " (" + e.getValue() + "×)").toList();

            List<String> state = new ArrayList<>();
            for (var e : ctx.inPlay()) {
                if (!mentionedIds.contains(e.id())) continue;
                for (String s : e.state()) state.add(e.name() + ": " + s);
            }
            for (String id : mentionedIds) {
                if (ctx.inPlay().stream().anyMatch(x -> x.id().equals(id))) continue;
                var st = reader.stateAt(tx, projectId, id, unitId);
                for (var ed : st.ended()) state.add(st.entity().name() + t(": [ended] ", ": [encerrado] ") + ed.fromName() + " " + ed.relType() + " " + ed.toName());
                for (var ed : st.notYet()) state.add(st.entity().name() + t(": [not yet] ", ": [ainda não] ") + ed.fromName() + " " + ed.relType() + " " + ed.toName());
            }
            var rules = reader.facts(tx, projectId, mentionedIds.isEmpty() ? List.of(unitId) : List.copyOf(mentionedIds), "critical", null, 80);
            List<String> directives = guidance.forEvent(projectId, "validate", List.of(), List.of());
            List<String> instructions = List.of(
                    t("Read the text against rulesToCheck: a 'law' must never be broken; a 'norm' may be, only with a reason shown in the text.",
                            "Leia o texto contra rulesToCheck: 'law' nunca pode ser violada; 'norm' pode, só com motivo mostrado no texto."),
                    t("Check that nothing in secretsToCheck is revealed or strongly hinted here.", "Confira que nada de secretsToCheck é revelado ou insinuado com força aqui."),
                    t("Compare stateToCheck with what happens in the text (possession, knowledge, location).",
                            "Compare stateToCheck com o que acontece no texto (posse, conhecimento, local)."),
                    t("Register in the graph: missing cast (unit_cast_set), new entities worth keeping, and state changes with fromUnit/toUnit anchors.",
                            "Registre no grafo: elenco faltando (unit_cast_set), entidades novas que valem guardar e mudanças de estado com âncoras fromUnit/toUnit."));
            return new Validation(new Views.Ref(unitId, tg.unit().name()), Manuscript.countWords(body), mentioned.isEmpty() ? List.of() : notInCast,
                    castNotMentioned, newEntities, warnings, state, rules, ctx.secrets(), directives, instructions);
        });
    }

    private static boolean mentions(String foldedText, String name, boolean agent) {
        String f = fold(name);
        if (f.length() >= 3 && containsWord(foldedText, f)) return true;
        if (agent) {
            // characters are often called by first or last name only
            for (String part : f.split("\\s+")) {
                if (part.length() >= 4 && !STOP.contains(part) && containsWord(foldedText, part)) return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }

    private static boolean sentenceStart(String text, int pos) {
        int k = pos - 1;
        while (k >= 0 && Character.isWhitespace(text.charAt(k))) k--;
        return k < 0 || ".!?—\"“«\n:".indexOf(text.charAt(k)) >= 0;
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
