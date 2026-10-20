-- Contexts made by the user (or by an LLM through MCP). Built-in contexts live in YAML (harness/contexts).
-- A context's schema (types, relations, rules, directives) lives in its blueprint project in the graph.
CREATE TABLE context_def (
    id                    TEXT PRIMARY KEY,
    name                  TEXT        NOT NULL,
    description           TEXT,
    icon                  TEXT,
    color                 TEXT,
    locale                TEXT        NOT NULL DEFAULT 'en',
    blueprint_project_id  TEXT        NOT NULL,
    base_context          TEXT,
    instructions          TEXT,
    space_labels          JSONB,
    tabs                  JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
