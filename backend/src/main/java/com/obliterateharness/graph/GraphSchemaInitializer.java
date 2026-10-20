package com.obliterateharness.graph;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Cria constraints e índices do Neo4j na subida (idempotente). */
@Component
@Order(0)
public class GraphSchemaInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(GraphSchemaInitializer.class);

    private static final List<String> STATEMENTS = List.of(
            "CREATE CONSTRAINT project_id IF NOT EXISTS FOR (p:Project) REQUIRE p.id IS UNIQUE",
            "CREATE CONSTRAINT node_id IF NOT EXISTS FOR (n:N) REQUIRE n.id IS UNIQUE",
            "CREATE CONSTRAINT fact_id IF NOT EXISTS FOR (f:Fact) REQUIRE f.id IS UNIQUE",
            "CREATE CONSTRAINT node_type_id IF NOT EXISTS FOR (t:NodeType) REQUIRE t.id IS UNIQUE",
            "CREATE CONSTRAINT rel_type_id IF NOT EXISTS FOR (t:RelType) REQUIRE t.id IS UNIQUE",
            "CREATE CONSTRAINT object_type_id IF NOT EXISTS FOR (t:ObjectType) REQUIRE t.id IS UNIQUE",
            "CREATE CONSTRAINT directive_id IF NOT EXISTS FOR (d:Directive) REQUIRE d.id IS UNIQUE",
            "CREATE INDEX node_project_type IF NOT EXISTS FOR (n:N) ON (n.projectId, n.typeId)",
            "CREATE INDEX node_project_sort IF NOT EXISTS FOR (n:N) ON (n.projectId, n.sortKey)",
            "CREATE INDEX fact_project IF NOT EXISTS FOR (f:Fact) ON (f.projectId, f.criticality)",
            "CREATE INDEX fact_origin IF NOT EXISTS FOR (f:Fact) ON (f.originId)",
            "CREATE INDEX node_type_name IF NOT EXISTS FOR (t:NodeType) ON (t.projectId, t.nameLower)",
            "CREATE INDEX rel_type_name IF NOT EXISTS FOR (t:RelType) ON (t.projectId, t.nameLower)",
            "CREATE INDEX object_type_name IF NOT EXISTS FOR (t:ObjectType) ON (t.projectId, t.nameLower)",
            "CREATE INDEX rel_id IF NOT EXISTS FOR ()-[r:R]-() ON (r.id)",
            "CREATE INDEX rel_project IF NOT EXISTS FOR ()-[r:R]-() ON (r.projectId)",
            "CREATE FULLTEXT INDEX node_text IF NOT EXISTS FOR (n:N) ON EACH [n.name, n.summary, n.description, n.attrsText]",
            "CREATE FULLTEXT INDEX fact_text IF NOT EXISTS FOR (f:Fact) ON EACH [f.text]"
    );

    private final Driver driver;

    public GraphSchemaInitializer(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (var session = driver.session()) {
            for (String s : STATEMENTS) session.run(s).consume();
        }
        log.info("Neo4j: {} constraints/índices garantidos", STATEMENTS.size());
    }
}
