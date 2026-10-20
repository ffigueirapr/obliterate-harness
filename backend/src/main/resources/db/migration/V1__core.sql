-- Changesets: toda escrita passa por aqui (humano aplica direto; LLM pode exigir revisão)
CREATE TABLE changeset (
    id           TEXT PRIMARY KEY,
    project_id   TEXT        NOT NULL,
    actor        TEXT        NOT NULL,
    session_id   TEXT,
    title        TEXT,
    status       TEXT        NOT NULL,
    operations   JSONB       NOT NULL,
    result       JSONB,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ,
    decided_by   TEXT
);
CREATE INDEX changeset_project_status ON changeset (project_id, status, created_at DESC);

-- Auditoria por entidade: quem mudou o quê (humano vs. LLM), com antes/depois
CREATE TABLE audit_log (
    id            BIGSERIAL PRIMARY KEY,
    project_id    TEXT        NOT NULL,
    changeset_id  TEXT,
    actor         TEXT        NOT NULL,
    op            TEXT        NOT NULL,
    entity_kind   TEXT        NOT NULL,
    entity_id     TEXT        NOT NULL,
    before        JSONB,
    after         JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_entity ON audit_log (project_id, entity_id, created_at DESC);
CREATE INDEX audit_project ON audit_log (project_id, created_at DESC);

-- Sessões de trabalho da LLM: o estado vive aqui, não na janela de contexto
CREATE TABLE work_session (
    id                TEXT PRIMARY KEY,
    project_id        TEXT        NOT NULL,
    goal              TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX work_session_project ON work_session (project_id, last_activity_at DESC);

CREATE TABLE checkpoint (
    id             BIGSERIAL PRIMARY KEY,
    session_id     TEXT        NOT NULL REFERENCES work_session (id) ON DELETE CASCADE,
    project_id     TEXT        NOT NULL,
    summary        TEXT        NOT NULL,
    decisions      JSONB,
    todos          JSONB,
    focus_node_ids JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX checkpoint_session ON checkpoint (session_id, created_at DESC);
