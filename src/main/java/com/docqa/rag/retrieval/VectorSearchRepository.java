package com.docqa.rag.retrieval;

import com.docqa.rag.config.RagProperties;
import com.docqa.rag.persistence.PgVector;
import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The retrieval query. This is the file to read first.
 *
 * <h2>Everything that narrows the result set happens in SQL</h2>
 *
 * <p>Tenant, category, document status, the top-K cut and the similarity
 * threshold are all applied by Postgres. Nothing is fetched and then discarded
 * in Java:
 *
 * <ul>
 *   <li><b>Correctness.</b> Post-filtering a K-row ANN result by tenant returns
 *       fewer than K rows - sometimes zero - because the index returned the
 *       globally nearest chunks, most of which belong to somebody else. The
 *       system then refuses a question it could have answered, and the bug only
 *       appears once a second tenant has data.</li>
 *   <li><b>Isolation.</b> A filter applied after the fact can be forgotten on
 *       one code path. In SQL it is part of the query plan.</li>
 *   <li><b>Cost.</b> Shipping 200 candidate rows of 1536 floats to discard 195
 *       of them is ~1.2 MB per question.</li>
 * </ul>
 *
 * <h2>Filtered HNSW search</h2>
 *
 * <p>pgvector's HNSW index walks the graph and applies {@code WHERE} to what it
 * finds. With a selective filter - one tenant out of many - the walk can run out
 * of candidates before finding {@code LIMIT} matching rows, and quietly return
 * fewer results than asked for. Two per-transaction settings fix it:
 * {@code hnsw.ef_search} widens the search beam, and
 * {@code hnsw.iterative_scan} (pgvector 0.8+) lets the scan resume instead of
 * stopping short. They are set with {@code set_config(..., is_local => true)} so
 * they cannot leak onto other queries sharing the pooled connection.
 */
@Repository
public class VectorSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchRepository.class);

    /**
     * The ANN scan lives alone in the CTE, over {@code document_chunks} and
     * nothing else. Joining {@code documents} inside it would give the planner a
     * reason to prefer a hash join over the HNSW index, which is the sequential
     * scan NFR-6 prohibits. The join happens outside, against at most
     * {@code candidateLimit} rows.
     */
    private static final String SEARCH_SQL = """
            WITH candidates AS (
                SELECT c.id,
                       1 - (c.embedding <=> CAST(:queryVector AS vector)) AS similarity
                  FROM document_chunks c
                 WHERE c.tenant_id = :tenantId
                   AND (CAST(:category AS text) IS NULL OR c.category = :category)
                 ORDER BY c.embedding <=> CAST(:queryVector AS vector)
                 LIMIT :candidateLimit
            )
            SELECT c.id           AS chunk_id,
                   c.document_id  AS document_id,
                   c.chunk_index  AS chunk_index,
                   c.page_number  AS page_number,
                   c.section      AS section,
                   c.content      AS content,
                   d.title        AS document_title,
                   d.category     AS document_category,
                   cand.similarity AS similarity
              FROM candidates cand
              JOIN document_chunks c ON c.id = cand.id AND c.tenant_id = :tenantId
              JOIN documents d       ON d.id = c.document_id AND d.tenant_id = :tenantId
             WHERE cand.similarity >= :threshold
               AND d.status = 'READY'
             ORDER BY cand.similarity DESC
             LIMIT :topK
            """;

    /** Diagnostic; runs only when nothing cleared the threshold. */
    private static final String NEAR_MISS_SQL = """
            SELECT d.title AS document_title,
                   c.page_number AS page_number,
                   1 - (c.embedding <=> CAST(:queryVector AS vector)) AS similarity
              FROM document_chunks c
              JOIN documents d ON d.id = c.document_id AND d.tenant_id = :tenantId
             WHERE c.tenant_id = :tenantId
               AND (CAST(:category AS text) IS NULL OR c.category = :category)
             ORDER BY c.embedding <=> CAST(:queryVector AS vector)
             LIMIT 1
            """;

    private final JdbcClient jdbc;
    private final RagProperties.Retrieval config;

    public VectorSearchRepository(JdbcClient jdbc, RagProperties properties) {
        this.jdbc = jdbc;
        this.config = properties.retrieval();
    }

    /**
     * @param tenantId  never null; there is no unscoped overload of this method
     * @param category  optional metadata filter, applied by Postgres
     * @param threshold minimum cosine similarity, applied by Postgres
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> search(TenantId tenantId,
                                       float[] queryEmbedding,
                                       @Nullable String category,
                                       int topK,
                                       double threshold) {
        applySearchTuning();

        return jdbc.sql(SEARCH_SQL)
                .param("queryVector", PgVector.toLiteral(queryEmbedding))
                .param("tenantId", tenantId.value())
                .param("category", category)
                .param("candidateLimit", config.candidateLimit())
                .param("threshold", threshold)
                .param("topK", topK)
                .query(VectorSearchRepository::map)
                .list();
    }

    /**
     * How close was the best chunk when nothing cleared the bar?
     *
     * <p>This is the number you need to tune the threshold, and it is invisible
     * in a system that only logs "no results". It runs at most once per refused
     * question - and a refused question makes no model call at all, so the extra
     * round trip replaces a two-second LLM call rather than adding to one.
     */
    @Transactional(readOnly = true)
    public Optional<NearMiss> findNearest(TenantId tenantId,
                                          float[] queryEmbedding,
                                          @Nullable String category) {
        applySearchTuning();
        return jdbc.sql(NEAR_MISS_SQL)
                .param("queryVector", PgVector.toLiteral(queryEmbedding))
                .param("tenantId", tenantId.value())
                .param("category", category)
                .query((rs, rowNum) -> new NearMiss(
                        rs.getString("document_title"),
                        (Integer) rs.getObject("page_number"),
                        rs.getDouble("similarity")))
                .optional();
    }

    public record NearMiss(String documentTitle, @Nullable Integer pageNumber, double similarity) {}

    private void applySearchTuning() {
        jdbc.sql("SELECT set_config('hnsw.ef_search', :ef, true)")
                .param("ef", String.valueOf(config.hnswEfSearch()))
                .query(String.class)
                .optional();
        try {
            jdbc.sql("SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true)")
                    .query(String.class)
                    .optional();
        } catch (RuntimeException e) {
            // pgvector < 0.8 does not know this setting. Recall is slightly
            // worse under a selective filter; retrieval still works.
            log.debug("hnsw.iterative_scan unavailable on this pgvector build");
        }
    }

    private static RetrievedChunk map(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievedChunk(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getString("document_category"),
                rs.getInt("chunk_index"),
                (Integer) rs.getObject("page_number"),
                rs.getString("section"),
                rs.getString("content"),
                rs.getDouble("similarity"));
    }
}
