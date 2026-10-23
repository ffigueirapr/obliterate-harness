-- The narrative module is back (as the "narrative" context): manuscript drafts and user-made structure templates.
-- V4 dropped these tables when the platform became domain-neutral; recreate them as they were in V2.
CREATE TABLE IF NOT EXISTS unit_text_version (
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
CREATE INDEX IF NOT EXISTS unit_text_version_node ON unit_text_version (project_id, node_id, version DESC);

CREATE TABLE IF NOT EXISTS custom_template (
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
