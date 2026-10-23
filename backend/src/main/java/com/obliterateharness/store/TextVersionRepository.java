package com.obliterateharness.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Saved drafts of a unit's manuscript. */
@Repository
public class TextVersionRepository {

    public record Version(int version, String attrKey, int words, String actor, String note, OffsetDateTime createdAt, String content) {}

    private final JdbcClient jdbc;

    public TextVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int save(String projectId, String nodeId, String attrKey, String content, int words, String actor, String note) {
        int next = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM unit_text_version WHERE node_id=:n AND attr_key=:k")
                .param("n", nodeId).param("k", attrKey).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO unit_text_version (project_id, node_id, attr_key, version, content, words, actor, note)
                VALUES (:p, :n, :k, :v, :c, :w, :a, :note)
                """).param("p", projectId).param("n", nodeId).param("k", attrKey).param("v", next).param("c", content)
                .param("w", words).param("a", actor).param("note", note).update();
        return next;
    }

    /** History without content (cheap). */
    public List<Version> history(String projectId, String nodeId) {
        return jdbc.sql("""
                SELECT version, attr_key, words, actor, note, created_at FROM unit_text_version
                WHERE project_id=:p AND node_id=:n ORDER BY version DESC
                """).param("p", projectId).param("n", nodeId)
                .query((rs, i) -> new Version(rs.getInt("version"), rs.getString("attr_key"), rs.getInt("words"), rs.getString("actor"),
                        rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class), null))
                .list();
    }

    public Optional<Version> get(String projectId, String nodeId, int version) {
        return jdbc.sql("SELECT * FROM unit_text_version WHERE project_id=:p AND node_id=:n AND version=:v")
                .param("p", projectId).param("n", nodeId).param("v", version)
                .query((rs, i) -> new Version(rs.getInt("version"), rs.getString("attr_key"), rs.getInt("words"), rs.getString("actor"),
                        rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("content")))
                .optional();
    }

    public void copyProject(String fromProject, String toProject, java.util.Map<String, String> nodeIdMap) {
        for (var e : nodeIdMap.entrySet()) {
            jdbc.sql("""
                    INSERT INTO unit_text_version (project_id, node_id, attr_key, version, content, words, actor, note, created_at)
                    SELECT :to, :newId, attr_key, version, content, words, actor, note, created_at
                    FROM unit_text_version WHERE project_id=:from AND node_id=:oldId
                    """).param("to", toProject).param("newId", e.getValue()).param("from", fromProject).param("oldId", e.getKey()).update();
        }
    }
}
