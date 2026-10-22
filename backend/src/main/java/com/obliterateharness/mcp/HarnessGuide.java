package com.obliterateharness.mcp;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Operating manual (resources, English and Portuguese) and ready-made workflows (prompts). */
@Component
public class HarnessGuide {

    private final String manual;
    private final String manualPt;

    public HarnessGuide() {
        manual = read("harness/manual.md");
        manualPt = read("harness/manual_pt.md");
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @McpResource(uri = "harness://manual", name = "manual", title = "Obliterate Harness operating manual",
            description = "How the LLM should operate the platform: contexts, data model, operations, criticality and workflows.",
            mimeType = "text/markdown")
    public String manualResource() {
        return manual;
    }

    @McpResource(uri = "harness://manual/pt", name = "manual_pt", title = "Manual de operação do Obliterate Harness (português)",
            description = "Portuguese version of the operating manual.",
            mimeType = "text/markdown")
    public String manualPtResource() {
        return manualPt;
    }

    @McpPrompt(name = "design_context", title = "Design a context",
            description = "Guides the creation of a new context (domain): its node types, relations, rules and AI directives, agreed with the user.")
    public String designContext(@McpArg(name = "domain", description = "What the context is about (e.g. a product area, a research topic)", required = false) String domain) {
        return """
                Let's design a context in Obliterate Harness. Read the resource harness://manual before acting.
                Domain the user has in mind: %s

                Steps:
                1. Ask the user (one question at a time, never assuming answers): what the knowledge base is for, who will ask
                   questions of it, which kinds of things it must track (people, teams, products, documents, rules...), which
                   rules are binding, and the language (en or pt).
                2. Check context_list: if a built-in or existing context is close, propose starting from it (baseContext).
                3. Propose a lean schema as text: node types (each on a base kind, with typed attributes; a 'link' role for
                   attributes holding the URL of the source document), relation types with their allowed pairs, binding rules
                   (facts with rigidity law/norm) and directives (how the AI must behave). Only write it after the user agrees.
                4. context_create, then write the definition into its blueprint project with propose_changes (dryRun first).
                5. Create the first project in the context (project_create) if the user wants, and session_checkpoint.
                Never invent facts about the user's domain: ask.
                """.formatted(domain == null || domain.isBlank() ? "(not given)" : domain);
    }

    @McpPrompt(name = "ingest_document", title = "Ingest a document",
            description = "Files a new document into the graph: finds where it fits, links it to what already exists and records its source link.")
    public String ingestDocument(@McpArg(name = "projectId", description = "Project id", required = true) String projectId,
                                 @McpArg(name = "source", description = "Link to the document's true source (S3, OneDrive, SharePoint...)", required = false) String source) {
        return """
                Project %s. Document source: %s.
                1. session_begin (or session_resume) and read the context instructions and directives in the briefing.
                2. Look for duplicates: node_search by the document's name, source link and content hash. Same hash = nothing to do;
                   a new version = a new document node linked to the previous one, which is marked as replaced.
                3. Classify it: which existing nodes it is about (products, teams, rules, clients...), always with node_search and
                   graph_focus before creating anything new. Ask the user when the fit is ambiguous.
                4. Write in one propose_changes batch: the document node (summary of at most ~120 words, source link, area that
                   sent it, confidentiality) plus its relations. New rules found in the document start as drafts until an owner
                   confirms them. Never copy the full text or personal data into the graph.
                5. session_checkpoint with what was filed and what is pending confirmation.
                """.formatted(projectId, source == null || source.isBlank() ? "(ask the user)" : source);
    }

    @McpPrompt(name = "answer_from_graph", title = "Answer from the graph",
            description = "Answers a question using only what the graph holds, citing the source nodes and documents.")
    public String answerFromGraph(@McpArg(name = "projectId", description = "Project id", required = true) String projectId,
                                  @McpArg(name = "question", description = "The user's question", required = true) String question) {
        return """
                Project %s. Question: %s
                1. session_begin (or session_resume); read the foundational facts and directives in the briefing.
                2. Find the entry points with node_search, then read only what you need: graph_focus around them, node_get for
                   attributes, facts_list for the binding rules about them, graph_path to explain how two things relate.
                3. Answer only with what the graph holds. Cite each node you used and, when it has one, the link to its source
                   document. Say what is a draft or unconfirmed, and who owns the topic when the graph has no answer.
                """.formatted(projectId, question);
    }
}
