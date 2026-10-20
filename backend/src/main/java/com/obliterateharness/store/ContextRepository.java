package com.obliterateharness.store;

import com.obliterateharness.graph.JsonCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Contexts created by the user or by an LLM. Their schema lives in the blueprint project (graph). */
@Repository
public class ContextRepository {

    public record Row(String id, String name, String description, String icon, String color, String locale, String blueprintProjectId,
                      String baseContext, String instructions, Map<String, String> spaceLabels, List<String> tabs) {}

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public ContextRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Row r) {
        jdbc.sql("""
                INSERT INTO context_def (id, name, description, icon, color, locale, blueprint_project_id, base_context, instructions, space_labels, tabs)
                VALUES (:id, :name, :description, :icon, :color, :locale, :bp, :base, :instructions, CAST(:labels AS jsonb), CAST(:tabs AS jsonb))
                """).param("id", r.id()).param("name", r.name()).param("description", r.description()).param("icon", r.icon())
                .param("color", r.color()).param("locale", r.locale()).param("bp", r.blueprintProjectId()).param("base", r.baseContext())
                .param("instructions", r.instructions()).param("labels", r.spaceLabels() == null ? null : json.write(r.spaceLabels()))
                .param("tabs", r.tabs() == null ? null : json.write(r.tabs())).update();
    }

    /** Updates the given fields (null keeps the current value). */
    public void update(String id, String name, String description, String icon, String color, String instructions,
                       Map<String, String> spaceLabels, List<String> tabs) {
        jdbc.sql("""
                UPDATE context_def SET name = coalesce(:name, name), description = coalesce(:description, description),
                    icon = coalesce(:icon, icon), color = coalesce(:color, color), instructions = coalesce(:instructions, instructions),
                    space_labels = coalesce(CAST(:labels AS jsonb), space_labels), tabs = coalesce(CAST(:tabs AS jsonb), tabs), updated_at = now()
                WHERE id = :id
                """).param("id", id).param("name", name).param("description", description).param("icon", icon).param("color", color)
                .param("instructions", instructions).param("labels", spaceLabels == null ? null : json.write(spaceLabels))
                .param("tabs", tabs == null ? null : json.write(tabs)).update();
    }

    public List<Row> list() {
        return jdbc.sql("SELECT * FROM context_def ORDER BY created_at").query(this::map).list();
    }

    public Optional<Row> get(String id) {
        return jdbc.sql("SELECT * FROM context_def WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM context_def WHERE id = :id").param("id", id).update() > 0;
    }

    private Row map(ResultSet rs, int i) throws SQLException {
        String labels = rs.getString("space_labels");
        String tabs = rs.getString("tabs");
        @SuppressWarnings("unchecked")
        Map<String, String> l = labels == null ? null : (Map<String, String>) (Map<?, ?>) json.readMap(labels);
        return new Row(rs.getString("id"), rs.getString("name"), rs.getString("description"), rs.getString("icon"), rs.getString("color"),
                rs.getString("locale"), rs.getString("blueprint_project_id"), rs.getString("base_context"), rs.getString("instructions"),
                l, tabs == null ? null : json.readList(tabs, String.class));
    }
}
