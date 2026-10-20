package com.obliterateharness.store;

import com.obliterateharness.domain.NotFoundException;
import com.obliterateharness.graph.JsonCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SessionRepository {

    public record Session(String id, String projectId, String goal, OffsetDateTime createdAt, OffsetDateTime lastActivityAt) {}

    public record Checkpoint(long id, String sessionId, String summary, List<String> decisions, List<String> todos,
                             List<String> focusNodeIds, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public SessionRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void create(String id, String projectId, String goal) {
        jdbc.sql("INSERT INTO work_session (id, project_id, goal) VALUES (:id, :p, :goal)")
                .param("id", id).param("p", projectId).param("goal", goal).update();
    }

    public Session get(String projectId, String id) {
        return jdbc.sql("SELECT * FROM work_session WHERE id=:id AND project_id=:p").param("id", id).param("p", projectId)
                .query((rs, i) -> new Session(rs.getString("id"), rs.getString("project_id"), rs.getString("goal"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("last_activity_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new NotFoundException(com.obliterateharness.i18n.I18n.t("Session '", "Sessão '") + id
                        + com.obliterateharness.i18n.I18n.t("' does not exist in this project", "' não existe neste projeto")));
    }

    /** Aliases ($x → id) remembered for the session, so later batches can reuse them. */
    public java.util.Map<String, String> refs(String sessionId) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT ref, entity_id FROM session_ref WHERE session_id=:s").param("s", sessionId)
                .query((rs, i) -> java.util.Map.entry(rs.getString("ref"), rs.getString("entity_id"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public void saveRefs(String sessionId, java.util.Map<String, String> refs) {
        for (var e : refs.entrySet()) {
            jdbc.sql("""
                    INSERT INTO session_ref (session_id, ref, entity_id) VALUES (:s, :r, :e)
                    ON CONFLICT (session_id, ref) DO UPDATE SET entity_id=EXCLUDED.entity_id, created_at=now()
                    """).param("s", sessionId).param("r", e.getKey()).param("e", e.getValue()).update();
        }
    }

    public Optional<Session> latest(String projectId) {
        return jdbc.sql("SELECT * FROM work_session WHERE project_id=:p ORDER BY last_activity_at DESC LIMIT 1").param("p", projectId)
                .query((rs, i) -> new Session(rs.getString("id"), rs.getString("project_id"), rs.getString("goal"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("last_activity_at", OffsetDateTime.class)))
                .optional();
    }

    public void touch(String id) {
        jdbc.sql("UPDATE work_session SET last_activity_at=now() WHERE id=:id").param("id", id).update();
    }

    public void addCheckpoint(String sessionId, String projectId, String summary, List<String> decisions, List<String> todos, List<String> focus) {
        jdbc.sql("""
                INSERT INTO checkpoint (session_id, project_id, summary, decisions, todos, focus_node_ids)
                VALUES (:s, :p, :summary, CAST(:d AS jsonb), CAST(:t AS jsonb), CAST(:f AS jsonb))
                """)
                .param("s", sessionId).param("p", projectId).param("summary", summary)
                .param("d", json.write(decisions)).param("t", json.write(todos)).param("f", json.write(focus))
                .update();
        touch(sessionId);
    }

    public List<Checkpoint> checkpoints(String sessionId, int limit) {
        return jdbc.sql("SELECT * FROM checkpoint WHERE session_id=:s ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("s", sessionId).param("limit", limit)
                .query((rs, i) -> new Checkpoint(rs.getLong("id"), rs.getString("session_id"), rs.getString("summary"),
                        json.readList(rs.getString("decisions"), String.class), json.readList(rs.getString("todos"), String.class),
                        json.readList(rs.getString("focus_node_ids"), String.class), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
