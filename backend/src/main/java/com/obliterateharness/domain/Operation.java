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
        @McpToolParam(required = false, description = "Alias (e.g. \"$ana\") for what this op creates; other ops (and, with a sessionId, later batches) can use it instead of the id")
        String ref,
        @McpToolParam(required = false, description = "Target id (update/delete). Accepts an alias \"$x\"")
        String id,
        @McpToolParam(required = false, description = "Version you read of the target; required on update/delete")
        Long expectedVersion,
        @McpToolParam(required = false, description = "create_node: node type name (e.g. \"Character\")")
        String type,
        @McpToolParam(required = false, description = "world, characters, story, analysis or templates (default from the base kind)")
        String space,
        @McpToolParam(required = false, description = "Node name")
        String name,
        @McpToolParam(required = false, description = "Short summary (1-2 sentences) used in briefings and validation")
        String summary,
        @McpToolParam(required = false, description = "Long description")
        String description,
        @McpToolParam(required = false, description = "Tags")
        List<String> tags,
        @McpToolParam(required = false, description = "normal, critical or foundational")
        String criticality,
        @McpToolParam(required = false, description = "Facts: law, norm or flavor")
        String rigidity,
        @McpToolParam(required = false, description = "Attributes per the type schema; null removes; node_ref takes node id(s)")
        Map<String, Object> attributes,
        @McpToolParam(required = false, description = "Criticality per attribute, e.g. {\"power_cost\": \"foundational\"}")
        Map<String, String> attributeCriticality,
        @McpToolParam(required = false, description = "Units (base Unit): parent id")
        String parent,
        @McpToolParam(required = false, description = "Units (base Unit): position among siblings (default: last)")
        Double order,
        @McpToolParam(required = false, description = "create_edge: source node id")
        String from,
        @McpToolParam(required = false, description = "create_edge: target node id")
        String to,
        @McpToolParam(required = false, description = "create_edge: relation type name")
        String relType,
        @McpToolParam(required = false, description = "Anchor: unit from which the relation holds")
        String fromUnit,
        @McpToolParam(required = false, description = "Anchor: unit until which the relation holds (inclusive)")
        String toUnit,
        @McpToolParam(required = false, description = "Facts / directives: text")
        String text,
        @McpToolParam(required = false, description = "Facts: ids of the nodes the fact is about")
        List<String> about,
        @McpToolParam(required = false, description = "upsert_*_type: definition (name, base, description, attributes/fields, allowed...). "
                + "upsert_directive: {when:[session|context|write|validate|always], bases:[...], types:[...], priority, enabled}")
        Map<String, Object> definition,
        @McpToolParam(required = false, description = "Units: cast — ids of the nodes that appear in this unit. Creates the appears-in relations "
                + "in one go; on update_node it replaces the cast")
        List<String> cast,
        @McpToolParam(required = false, description = "Facts: id of the unit where this fact is revealed to the reader. Before it, the fact is a "
                + "secret and writing_context lists it as something not to reveal")
        String revealAt
) {
    public OpType opType() {
        return OpType.parse(op);
    }

    public Operation withId(String newId) {
        return new Operation(op, ref, newId, expectedVersion, type, space, name, summary, description, tags, criticality, rigidity,
                attributes, attributeCriticality, parent, order, from, to, relType, fromUnit, toUnit, text, about, definition, cast, revealAt);
    }

    public Operation resolved(UnaryOperator<String> r, Map<String, Object> resolvedAttributes, List<String> resolvedAbout, List<String> resolvedCast) {
        return new Operation(op, ref, r.apply(id), expectedVersion, type, space, name, summary, description, tags, criticality, rigidity,
                resolvedAttributes, attributeCriticality, r.apply(parent), order, r.apply(from), r.apply(to), relType,
                r.apply(fromUnit), r.apply(toUnit), text, resolvedAbout, definition, resolvedCast, r.apply(revealAt));
    }
}
