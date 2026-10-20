package com.obliterateharness.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.TransactionContext;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/** Atalho para transações de leitura/escrita no Neo4j. */
@Component
public class Graph {
    private final Driver driver;

    public Graph(Driver driver) {
        this.driver = driver;
    }

    public <T> T read(Function<TransactionContext, T> work) {
        try (var session = driver.session()) {
            return session.executeRead(work::apply);
        }
    }

    public <T> T write(Function<TransactionContext, T> work) {
        try (var session = driver.session()) {
            return session.executeWrite(work::apply);
        }
    }

    /** Executa e sempre desfaz: valida um changeset sem gravar nada. */
    public <T> T dryRun(Function<org.neo4j.driver.Transaction, T> work) {
        try (var session = driver.session(); var tx = session.beginTransaction()) {
            try {
                return work.apply(tx);
            } finally {
                tx.rollback();
            }
        }
    }
}
