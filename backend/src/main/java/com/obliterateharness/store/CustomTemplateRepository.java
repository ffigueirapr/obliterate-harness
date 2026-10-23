package com.obliterateharness.store;

import com.obliterateharness.graph.JsonCodec;
import com.obliterateharness.harness.HarnessLibrary.BeatDef;
import com.obliterateharness.harness.HarnessLibrary.TemplateDef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Structure templates made by the user (by hand or derived from an existing work). */
@Repository
public class CustomTemplateRepository {
    private final JdbcClient jdbc;
    private final JsonCodec json;

    public CustomTemplateRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void upsert(TemplateDef t, String locale, String fromProject) {
        jdbc.sql("""
                INSERT INTO custom_template (id, name, description, scope, locale, source, from_project, beats)
                VALUES (:id, :name, :description, :scope, :locale, :source, :from, CAST(:beats AS jsonb))
                ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, scope=EXCLUDED.scope,
                    locale=EXCLUDED.locale, source=EXCLUDED.source, from_project=coalesce(EXCLUDED.from_project, custom_template.from_project),
                    beats=EXCLUDED.beats, updated_at=now()
                """).param("id", t.id()).param("name", t.name()).param("description", t.description())
                .param("scope", t.scope() == null ? "obra" : t.scope()).param("locale", locale).param("source", t.source())
                .param("from", fromProject).param("beats", json.write(t.beats())).update();
    }

    public List<TemplateDef> list() {
        return jdbc.sql("SELECT * FROM custom_template ORDER BY name").query(this::map).list();
    }

    public Optional<TemplateDef> get(String id) {
        return jdbc.sql("SELECT * FROM custom_template WHERE id=:id").param("id", id).query(this::map).optional();
    }

    public Optional<String> fromProject(String id) {
        return jdbc.sql("SELECT from_project FROM custom_template WHERE id=:id").param("id", id).query(String.class).optional();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM custom_template WHERE id=:id").param("id", id).update() > 0;
    }

    private TemplateDef map(ResultSet rs, int i) throws SQLException {
        return new TemplateDef(rs.getString("id"), rs.getString("name"), rs.getString("description"), rs.getString("scope"),
                List.of(), rs.getString("source"), json.readList(rs.getString("beats"), BeatDef.class), true, rs.getString("locale"));
    }
}
