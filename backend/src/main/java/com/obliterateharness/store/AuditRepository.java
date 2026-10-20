package com.obliterateharness.store;

import com.obliterateharness.graph.ChangeExecutor;
import com.obliterateharness.graph.JsonCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class AuditRepository {

    public record Entry(long id, String changesetId, String actor, String op, String entityKind, String entityId,
                        Object before, Object after, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public AuditRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String projectId, String changesetId, String actor, List<ChangeExecutor.OpResult> results) {
        for (var r : results) {
            jdbc.sql("""
                    INSERT INTO audit_log (project_id, changeset_id, actor, op, entity_kind, entity_id, before, after)
                    VALUES (:p, :cs, :actor, :op, :kind, :eid, CAST(:before AS jsonb), CAST(:after AS jsonb))
                    """)
                    .param("p", projectId).param("cs", changesetId).param("actor", actor).param("op", r.op())
                    .param("kind", r.entityKind()).param("eid", r.entityId())
                    .param("before", json.write(r.before())).param("after", json.write(r.after()))
                    .update();
        }
    }

    public List<Entry> list(String projectId, String entityId, int limit) {
        return jdbc.sql("""
                SELECT * FROM audit_log WHERE project_id=:p AND (CAST(:eid AS text) IS NULL OR entity_id=:eid)
                ORDER BY created_at DESC, id DESC LIMIT :limit
                """).param("p", projectId).param("eid", entityId).param("limit", limit)
                .query((rs, i) -> new Entry(rs.getLong("id"), rs.getString("changeset_id"), rs.getString("actor"), rs.getString("op"),
                        rs.getString("entity_kind"), rs.getString("entity_id"),
                        rs.getString("before") == null ? null : json.mapper().readTree(rs.getString("before")),
                        rs.getString("after") == null ? null : json.mapper().readTree(rs.getString("after")),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
