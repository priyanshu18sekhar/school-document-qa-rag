package com.docqa.rag.ingestion;

import com.docqa.rag.ingestion.chunk.TextChunk;
import com.docqa.rag.persistence.PgVector;
import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * Writes chunks and their embeddings.
 *
 * <p>Uses {@link JdbcTemplate#batchUpdate} rather than a loop of single inserts.
 * A 50-page PDF is ~150 rows each carrying a 1536-float vector; one round trip
 * per row turns a 200 ms write into several seconds, and every one of those
 * round trips holds the same transaction - and therefore the same pooled
 * connection - open for longer.
 *
 * <p>The embedding is bound as a {@code String} and cast in SQL. pgvector
 * accepts its text input format on any parameter that can be cast to
 * {@code vector}, so this avoids adding a driver extension purely to register a
 * type. Values are machine-generated floats, never user input.
 */
@Repository
public class ChunkWriteRepository {

    private static final String INSERT_SQL = """
            INSERT INTO document_chunks (
                id, document_id, tenant_id, category, chunk_index,
                page_number, section, content, token_count, embedding
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
            """;

    private final JdbcTemplate jdbc;

    public ChunkWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param chunks     chunk metadata, index-aligned with {@code embeddings}
     * @param embeddings one vector per chunk, in the same order
     */
    public void insertAll(TenantId tenantId,
                          UUID documentId,
                          @Nullable String category,
                          List<TextChunk> chunks,
                          List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "chunk/embedding count mismatch: %d vs %d"
                            .formatted(chunks.size(), embeddings.size()));
        }
        if (chunks.isEmpty()) {
            return;
        }

        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TextChunk chunk = chunks.get(i);
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, documentId);
                // tenant_id is written here, but the composite foreign key in
                // the V1 migration is what guarantees it matches the document.
                ps.setString(3, tenantId.value());
                if (category == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, category);
                }
                ps.setInt(5, chunk.index());
                if (chunk.pageNumber() == null) {
                    ps.setNull(6, Types.INTEGER);
                } else {
                    ps.setInt(6, chunk.pageNumber());
                }
                if (chunk.section() == null) {
                    ps.setNull(7, Types.VARCHAR);
                } else {
                    ps.setString(7, chunk.section());
                }
                ps.setString(8, chunk.content());
                ps.setInt(9, chunk.tokenCount());
                ps.setString(10, PgVector.toLiteral(embeddings.get(i)));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    /**
     * Clears chunks for a document before re-ingesting it.
     *
     * <p>Needed when a previously FAILED document is re-uploaded: a failure part
     * way through would otherwise leave orphaned chunks that collide with the
     * {@code (document_id, chunk_index)} unique constraint on the retry.
     */
    public int deleteByDocument(TenantId tenantId, UUID documentId) {
        return jdbc.update(
                "DELETE FROM document_chunks WHERE tenant_id = ? AND document_id = ?",
                tenantId.value(), documentId);
    }

    public int countByDocument(TenantId tenantId, UUID documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE tenant_id = ? AND document_id = ?",
                Integer.class, tenantId.value(), documentId);
        return count == null ? 0 : count;
    }
}
