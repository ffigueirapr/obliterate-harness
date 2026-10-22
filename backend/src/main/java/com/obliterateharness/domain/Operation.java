package com.obliterateharness.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * One write operation. Every change (human or LLM) goes through operations, grouped in changesets.
 * Flat format: each op uses only the relevant fields. The @McpToolParam annotations generate the JSON Schema
 * of the propose_changes tool (only 'op' is required).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Operation(
        @McpToolParam(required = true, description = "create_node, update_node, delete_node, create_edge, update_edge, delete_edge, "
                + "create_fact, update_fact, delete_fact, upsert_node_type, upsert_rel_type, upsert_object_type, delete_node_type, delete_rel_type, "
                + "upsert_directive, delete_directive")
        String op,
        @McpToolParam(required = false, description = "Alias (e.g. \"$team\") for what this op creates; other ops (and, with a sessionId, later batches) can use it instead of the id")
        String ref,
        @McpToolParam(required = false, description = "Target id (update/delete). Accepts an alias \"$x\"")
        String id,
        @McpToolParam(required = false, description = "Version you read of the target; required on update/delete")
        Long expectedVersion,
        @McpToolParam(required = false, description = "create_node: node type name (e.g. \"Team\")")
        String type,
        @McpToolParam(required = false, description = "knowledge, actors or analysis (default from the base kind)")
        String space,
        @McpToolParam(required = false, description = "Node name")
        String name,
        @McpToolParam(required = false, description = "Short summary (1-2 sentences) used in briefings and searches")
        String summary,
        @McpToolParam(required = false, description = "Long description")
        String description,
        @McpToolParam(required = false, description = "Tags")
        List<String> tags,
        @McpToolParam(required = false, description = "normal, critical or foundational")
        String criticality,
        @McpToolParam(required = false, description = "Facts: law (never break), norm (default, deviations need a reason) or flavor (informative)")
        String rigidity,
        @McpToolParam(required = false, description = "Attributes per the type schema; null removes; node_ref takes node id(s)")
        Map<String, Object> attributes,
        @McpToolParam(required = false, description = "Criticality per attribute, e.g. {\"discount_policy\": \"critical\"}")
        Map<String, String> attributeCriticality,
        @McpToolParam(required = false, description = "create_edge: source node id")
        String from,
        @McpToolParam(required = false, description = "create_edge: target node id")
        String to,
        @McpToolParam(required = false, description = "create_edge: relation type name")
        String relType,
        @McpToolParam(required = false, description = "Facts / directives: text")
        String text,
        @McpToolParam(required = false, description = "Facts: ids of the nodes the fact is about")
        List<String> about,
        @McpToolParam(required = false, description = "upsert_*_type: definition (name, base, description, attributes/fields, allowed...). "
                + "upsert_directive: {when:[session|context|write|validate|always], bases:[...], types:[...], priority, enabled}")
        Map<String, Object> definition
) {
    public OpType opType() {
        return OpType.parse(op);
    }

    public Operation withId(String newId) {
        return new Operation(op, ref, newId, expectedVersion, type, space, name, summary, description, tags, criticality, rigidity,
                attributes, attributeCriticality, from, to, relType, text, about, definition);
    }

    public Operation resolved(UnaryOperator<String> r, Map<String, Object> resolvedAttributes, List<String> resolvedAbout) {
        return new Operation(op, ref, r.apply(id), expectedVersion, type, space, name, summary, description, tags, criticality, rigidity,
                resolvedAttributes, attributeCriticality, r.apply(from), r.apply(to), relType, text, resolvedAbout, definition);
    }
}
