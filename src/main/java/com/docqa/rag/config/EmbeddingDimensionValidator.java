package com.docqa.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the embedding dimension in the database does not match the
 * configured model.
 *
 * <p>This is the one configuration mistake that produces no error and no
 * symptom until it is far too late. Swapping {@code spring.ai.model.embedding}
 * from OpenAI (1536 dimensions) to Ollama's nomic-embed-text (768) against a
 * database whose {@code vector} column is fixed at 1536 gives you: ingestion
 * failing per-row deep inside a batch insert, or - worse, if the dimensions
 * happen to match between two different models - a table full of vectors from
 * two incompatible embedding spaces, where similarity scores are meaningless
 * and every answer is subtly wrong with no error anywhere.
 *
 * <p>Ten lines at startup turn that into a message that names both numbers and
 * says what to do about it.
 */
@Component
public class EmbeddingDimensionValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDimensionValidator.class);

    private final JdbcClient jdbc;
    private final RagProperties properties;

    public EmbeddingDimensionValidator(JdbcClient jdbc, RagProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int configured = properties.embedding().dimensions();

        // atttypmod carries the declared dimension for a pgvector column.
        Integer actual = jdbc.sql("""
                SELECT a.atttypmod
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                 WHERE c.relname = 'document_chunks' AND a.attname = 'embedding'
                """)
                .query(Integer.class)
                .optional()
                .orElse(null);

        if (actual == null) {
            log.warn("Could not read the embedding column dimension; skipping the check");
            return;
        }
        if (actual != configured) {
            throw new IllegalStateException("""
                    Embedding dimension mismatch: rag.embedding.dimensions is %d but the \
                    document_chunks.embedding column is vector(%d).

                    pgvector fixes the dimension in the column type, so changing embedding \
                    model requires a migration, not just a config change. Either set \
                    rag.embedding.dimensions back to %d, or add a migration that recreates \
                    the column at %d and re-ingest every document - existing vectors from a \
                    different model are not comparable and must not be mixed."""
                    .formatted(configured, actual, actual, configured));
        }
        log.info("Embedding dimension check passed: vector({})", configured);
    }
}
