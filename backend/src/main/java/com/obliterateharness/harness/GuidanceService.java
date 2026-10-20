package com.obliterateharness.harness;

import com.obliterateharness.domain.Views;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.GraphReader;
import com.obliterateharness.i18n.Lang;
import com.obliterateharness.service.ProjectService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Directives: short instructions with a trigger, attached to the LLM's responses ("guidance").
 * They come from the project's profile (YAML, in the project's language) and from the project itself
 * (:Directive nodes editable in the UI or with upsert_directive).
 */
@Service
public class GuidanceService {
    /** Directive limit per response, so the context does not bloat. */
    static final int MAX = 8;

    private final HarnessLibrary library;
    private final ProjectService projects;
    private final Graph graph;
    private final GraphReader reader;

    public GuidanceService(HarnessLibrary library, ProjectService projects, Graph graph, GraphReader reader) {
        this.library = library;
        this.projects = projects;
        this.graph = graph;
        this.reader = reader;
    }

    /** Every directive that applies to the project (profile + project), for listing. */
    public List<Views.Directive> all(String projectId) {
        var project = projects.get(projectId);
        Lang lang = Lang.parse(project.locale(), Lang.pt);
        List<Views.Directive> out = new ArrayList<>();
        if (project.profile() != null && library.profile(project.profile(), lang).isPresent()) {
            for (var p : library.chain(project.profile(), lang)) {
                if (p.directives() == null) continue;
                for (var d : p.directives()) {
                    out.add(new Views.Directive(d.id(), d.text(), d.when() == null ? List.of("always") : d.when(),
                            d.bases() == null ? List.of() : d.bases(), d.types() == null ? List.of() : d.types(),
                            d.priority() == null ? 0 : d.priority(), true, "profile:" + p.id()));
                }
            }
        }
        out.addAll(graph.read(tx -> reader.directives(tx, projectId)));
        return out;
    }

    /**
     * Directives that apply to an event.
     *
     * @param event session, context, write or validate
     * @param bases base kinds in play (empty = no filter by base)
     * @param types type names in play
     */
    public List<String> forEvent(String projectId, String event, Collection<String> bases, Collection<String> types) {
        Set<String> b = Set.copyOf(bases);
        Set<String> ty = Set.copyOf(types);
        return all(projectId).stream()
                .filter(Views.Directive::enabled)
                .filter(d -> d.when().contains("always") || d.when().contains(event))
                .filter(d -> d.bases().isEmpty() || d.bases().stream().anyMatch(b::contains))
                .filter(d -> d.types().isEmpty() || d.types().stream().anyMatch(x -> ty.stream().anyMatch(x::equalsIgnoreCase)))
                .sorted(Comparator.comparingInt(Views.Directive::priority).reversed())
                .limit(MAX)
                .map(Views.Directive::text)
                .toList();
    }
}
