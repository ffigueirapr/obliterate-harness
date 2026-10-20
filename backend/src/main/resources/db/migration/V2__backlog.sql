-- Aliases ($x) that stay valid for the whole session, across batches
CREATE TABLE session_ref (
    session_id  TEXT        NOT NULL REFERENCES work_session (id) ON DELETE CASCADE,
    ref         TEXT        NOT NULL,
    entity_id   TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, ref)
);

-- Versions of a unit's manuscript (prose/script), one row per saved draft
CREATE TABLE unit_text_version (
    id          BIGSERIAL PRIMARY KEY,
    project_id  TEXT        NOT NULL,
    node_id     TEXT        NOT NULL,
    attr_key    TEXT        NOT NULL,
    version     INT         NOT NULL,
    content     TEXT        NOT NULL,
    words       INT         NOT NULL,
    actor       TEXT        NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (node_id, attr_key, version)
);
CREATE INDEX unit_text_version_node ON unit_text_version (project_id, node_id, version DESC);

-- User-made structure templates (created by hand or derived from an existing work)
CREATE TABLE custom_template (
    id            TEXT PRIMARY KEY,
    name          TEXT        NOT NULL,
    description   TEXT,
    scope         TEXT        NOT NULL DEFAULT 'obra',
    locale        TEXT        NOT NULL DEFAULT 'en',
    source        TEXT,
    from_project  TEXT,
    beats         JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
