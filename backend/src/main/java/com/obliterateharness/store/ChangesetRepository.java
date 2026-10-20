package com.obliterateharness.store;

import com.obliterateharness.domain.NotFoundException;
import com.obliterateharness.domain.Operation;
import com.obliterateharness.graph.JsonCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class ChangesetRepository {

    public record Row(String id, String projectId, String actor, String sessionId, String title, String status,
                      List<Operation> operations, Object result, String error, OffsetDateTime createdAt,
                      OffsetDateTime decidedAt, String decidedBy) {}

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public ChangesetRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Row r) {
        jdbc.sql("""
                INSERT INTO changeset (id, project_id, actor, session_id, title, status, operations, result, error, decided_at, decided_by)
                VALUES (:id, :p, :actor, :session, :title, :status, CAST(:ops AS jsonb), CAST(:result AS jsonb), :error, :decidedAt, :decidedBy)
                """)
                .param("id", r.id()).param("p", r.projectId()).param("actor", r.actor()).param("session", r.sessionId())
                .param("title", r.title()).param("status", r.status()).param("ops", json.write(r.operations()))
                .param("result", json.write(r.result())).param("error", r.error())
                .param("decidedAt", r.decidedAt()).param("decidedBy", r.decidedBy())
                .update();
    }

    public void decide(String id, String status, Object result, String error, String decidedBy) {
        jdbc.sql("""
                UPDATE changeset SET status=:status, result=CAST(:result AS jsonb), error=:error, decided_at=now(), decided_by=:by
                WHERE id=:id
                """)
                .param("id", id).param("status", status).param("result", json.write(result)).param("error", error).param("by", decidedBy)
                .update();
    }

    public void setError(String id, String error) {
        jdbc.sql("UPDATE changeset SET error=:error WHERE id=:id").param("id", id).param("error", error).update();
    }

    public Row get(String projectId, String id) {
        return jdbc.sql("SELECT * FROM changeset WHERE id=:id AND project_id=:p").param("id", id).param("p", projectId)
                .query(this::map).optional()
                .orElseThrow(() -> new NotFoundException("Changeset '" + id + "' não existe neste projeto"));
    }

    public List<Row> list(String projectId, String status, int limit) {
        return jdbc.sql("""
                SELECT * FROM changeset WHERE project_id=:p AND (CAST(:status AS text) IS NULL OR status=:status)
                ORDER BY created_at DESC LIMIT :limit
                """).param("p", projectId).param("status", status).param("limit", limit).query(this::map).list();
    }

    public long countPending(String projectId) {
        return jdbc.sql("SELECT count(*) FROM changeset WHERE project_id=:p AND status='pending'").param("p", projectId)
                .query(Long.class).single();
    }

    private Row map(ResultSet rs, int i) throws SQLException {
        String result = rs.getString("result");
        return new Row(rs.getString("id"), rs.getString("project_id"), rs.getString("actor"), rs.getString("session_id"),
                rs.getString("title"), rs.getString("status"), json.readList(rs.getString("operations"), Operation.class),
                result == null ? null : json.mapper().readTree(result), rs.getString("error"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decided_by"));
    }
}
