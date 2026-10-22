package com.obliterateharness.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

import static com.obliterateharness.i18n.I18n.t;

/** Read models returned by the REST API and by MCP. */
public final class Views {
    private Views() {}

    /** Detail level: id (minimal), summary (default) or full. */
    public enum Detail {
        id, summary, full;

        public static Detail parse(String v) {
            if (v == null || v.isBlank()) return summary;
            for (Detail d : values()) if (d.name().equalsIgnoreCase(v)) return d;
            throw new DomainException(t("Invalid detail: '", "detail inválido: '") + v + t("'. Valid: id, summary, full", "'. Válidos: id, summary, full"));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Node(
            String id,
            String type,
            String base,
            String space,
            String name,
            String summary,
            String description,
            List<String> tags,
            String criticality,
            Map<String, Object> attributes,
            Map<String, String> attributeCriticality,
            Map<String, List<String>> refs,
            Long version,
            String createdBy,
            String updatedBy,
            String updatedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Edge(
            String id,
            String relType,
            String base,
            String kind,
            String from,
            String fromName,
            String to,
            String toName,
            String description,
            String criticality,
            List<String> tags,
            Long version
    ) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Fact(
            String id,
            String text,
            String rigidity,
            String criticality,
            String origin,
            List<Ref> about,
            Long version
    ) {}

    public record Ref(String id, String name) {}

    /** Directive: a short instruction injected in the LLM's responses when its trigger matches. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Directive(String id, String text, List<String> when, List<String> bases, List<String> types,
                            int priority, boolean enabled, String source) {}

    /**
     * @param profile        the context preset its schema came from (built-in contexts), if any
     * @param locale         language of the project's schema and content (en or pt)
     * @param context        id of the context (domain) the project belongs to
     * @param blueprint      true for a context's definition project (holds the context's schema; hidden from project lists)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Project(
            String id,
            String name,
            String description,
            String profile,
            boolean requireReviewForLlm,
            String createdAt,
            String locale,
            String context,
            Boolean blueprint
    ) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Subgraph(List<Node> nodes, List<Edge> edges, Boolean truncated) {}

    /**
     * Focus on a node: the node and everything connected to it (directly or indirectly), with each one's distance in hops.
     *
     * @param levels  largest distance reached
     * @param byLevel how many nodes at each distance (1 = directly connected)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Focus(Ref center, List<Node> nodes, List<Edge> edges, Map<String, Integer> distance, int levels,
                        Map<Integer, Integer> byLevel, boolean truncated) {}

    /** Indirect link between two visible nodes, with the shortest path connecting them. */
    public record IndirectLink(String from, String to, int hops, List<String> pathNodeIds, List<String> pathRelTypes) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record SchemaSummary(
            List<NodeTypeDef> nodeTypes,
            List<RelTypeDef> relTypes,
            List<ObjectTypeDef> objectTypes,
            Map<String, Long> nodeCountByType
    ) {}
}
