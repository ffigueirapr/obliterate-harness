package com.obliterateharness.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Cleans up a project's relational data (changesets, audit, sessions, text versions). */
@Repository
public class ProjectDataRepository {
    private final JdbcClient jdbc;

    public ProjectDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void deleteProject(String projectId) {
        jdbc.sql("DELETE FROM checkpoint WHERE project_id = :p").param("p", projectId).update();
        jdbc.sql("DELETE FROM work_session WHERE project_id = :p").param("p", projectId).update();
        jdbc.sql("DELETE FROM audit_log WHERE project_id = :p").param("p", projectId).update();
        jdbc.sql("DELETE FROM changeset WHERE project_id = :p").param("p", projectId).update();
        jdbc.sql("DELETE FROM unit_text_version WHERE project_id = :p").param("p", projectId).update();
    }
}
