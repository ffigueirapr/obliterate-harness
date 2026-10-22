package com.obliterateharness.api;

import com.obliterateharness.domain.Views;
import com.obliterateharness.domain.Views.Detail;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.GraphReader;
import com.obliterateharness.graph.GraphReader.NodeFilter;
import com.obliterateharness.service.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{pid}")
public class GraphController {

    public record NodeDetail(Views.Node node, List<Views.Edge> edges, List<Views.Fact> facts) {}

    public record ViewInput(List<String> types, List<String> bases, List<String> tags, List<String> spaces, String minCriticality,
                            Integer limit, Boolean includeIndirect, Integer maxHops, List<String> relTypes,
                            Boolean includeContext) {}

    public record ViewResult(List<Views.Node> nodes, List<Views.Edge> edges, List<Views.IndirectLink> indirect, List<String> contextIds,
                             long total, boolean truncated) {}

    public record IndirectInput(List<String> ids, Integer maxHops, List<String> relTypes, Integer limit) {}

    private final Graph graph;
    private final GraphReader reader;
    private final ProjectService projects;

    public GraphController(Graph graph, GraphReader reader, ProjectService projects) {
        this.graph = graph;
        this.reader = reader;
        this.projects = projects;
    }

    @GetMapping("/nodes")
    public List<Views.Node> nodes(@PathVariable String pid, @RequestParam(required = false) String q,
                                  @RequestParam(required = false) List<String> types, @RequestParam(required = false) List<String> bases,
                                  @RequestParam(required = false) List<String> tags, @RequestParam(required = false) List<String> spaces,
                                  @RequestParam(required = false) String minCriticality,
                                  @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(required = false) String detail) {
        projects.get(pid);
        var f = new NodeFilter(types, bases, tags, spaces, minCriticality);
        return graph.read(tx -> q == null || q.isBlank()
                ? reader.list(tx, pid, f, limit, offset, Detail.parse(detail))
                : reader.search(tx, pid, q, f, limit, Detail.parse(detail)));
    }

    @GetMapping("/nodes/{id}")
    public NodeDetail node(@PathVariable String pid, @PathVariable String id) {
        return graph.read(tx -> new NodeDetail(reader.node(tx, pid, id, Detail.full),
                reader.edgesOf(tx, pid, List.of(id)), reader.facts(tx, pid, List.of(id), null, null, 200)));
    }

    /** Filtered view for the screen: filtered nodes, direct edges between them and (optionally) indirect links. */
    @PostMapping("/graph/view")
    public ViewResult view(@PathVariable String pid, @RequestBody ViewInput in) {
        projects.get(pid);
        int limit = in.limit() == null ? 1000 : Math.min(in.limit(), 5000);
        var f = new NodeFilter(in.types(), in.bases(), in.tags(), in.spaces(), in.minCriticality());
        return graph.read(tx -> {
            long total = reader.count(tx, pid, f);
            var nodes = reader.list(tx, pid, f, limit, 0, Detail.summary);
            var ids = nodes.stream().map(Views.Node::id).toList();
            List<String> contextIds = List.of();
            if (Boolean.TRUE.equals(in.includeContext()) && !ids.isEmpty()) {
                // direct neighbours outside the filter (e.g. the teams and documents a product links to), shown as reference
                contextIds = tx.run("MATCH (a:N)-[:R]-(b:N {projectId:$p}) WHERE a.id IN $ids AND NOT b.id IN $ids RETURN DISTINCT b.id AS id LIMIT 500",
                        java.util.Map.of("p", pid, "ids", ids)).list(r -> r.get("id").asString());
                nodes = new java.util.ArrayList<>(nodes);
                nodes.addAll(reader.nodes(tx, pid, contextIds, Detail.summary));
                ids = nodes.stream().map(Views.Node::id).toList();
            }
            var edges = reader.edgesBetween(tx, pid, ids);
            List<Views.IndirectLink> indirect = Boolean.TRUE.equals(in.includeIndirect())
                    ? reader.indirectLinks(tx, pid, ids, in.maxHops() == null ? 3 : in.maxHops(), in.relTypes(), 2000)
                    : List.of();
            return new ViewResult(nodes, edges, indirect, contextIds, total, total > limit);
        });
    }

    @PostMapping("/graph/indirect")
    public List<Views.IndirectLink> indirect(@PathVariable String pid, @RequestBody IndirectInput in) {
        return graph.read(tx -> reader.indirectLinks(tx, pid, in.ids(), in.maxHops() == null ? 3 : in.maxHops(), in.relTypes(),
                in.limit() == null ? 2000 : in.limit()));
    }

    @GetMapping("/graph/neighborhood")
    public Views.Subgraph neighborhood(@PathVariable String pid, @RequestParam String nodeId, @RequestParam(defaultValue = "1") int hops,
                                       @RequestParam(required = false) List<String> relTypes, @RequestParam(defaultValue = "200") int limit) {
        return graph.read(tx -> reader.neighborhood(tx, pid, nodeId, hops, relTypes, limit, Detail.summary));
    }

    /** Focus: the node and what is linked to it. hops=1 → direct only; all=true → everything linked, even indirectly. */
    @GetMapping("/graph/focus")
    public Views.Focus focus(@PathVariable String pid, @RequestParam String nodeId, @RequestParam(defaultValue = "1") int hops,
                             @RequestParam(defaultValue = "false") boolean all, @RequestParam(required = false) List<String> relTypes,
                             @RequestParam(defaultValue = "2000") int limit) {
        return graph.read(tx -> reader.focus(tx, pid, nodeId, all ? GraphReader.ALL_LEVELS : hops, relTypes, limit, Detail.summary));
    }

    @GetMapping("/graph/path")
    public Views.Subgraph path(@PathVariable String pid, @RequestParam String from, @RequestParam String to,
                               @RequestParam(defaultValue = "6") int maxHops) {
        return graph.read(tx -> reader.path(tx, pid, from, to, maxHops));
    }

    @GetMapping("/facts")
    public List<Views.Fact> facts(@PathVariable String pid, @RequestParam(required = false) List<String> about,
                                  @RequestParam(required = false) String minCriticality, @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "200") int limit) {
        projects.get(pid);
        return graph.read(tx -> reader.facts(tx, pid, about, minCriticality, q, limit));
    }
}
