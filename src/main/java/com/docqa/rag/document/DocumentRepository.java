package com.docqa.rag.document;

import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All access to {@code documents}.
 *
 * <p><b>Every method takes a {@link TenantId} and every statement filters on it.</b>
 * There is no "find by id" that does not also constrain the tenant, and no
 * overload that skips it. This is the single most important convention in the
 * codebase: it means a cross-tenant read cannot be introduced by forgetting
 * something, only by deliberately writing a new method that omits the
 * parameter - which is visible in review and in the diff.
 *
 * <p>Plain {@link JdbcClient} rather than JPA. Three reasons, in order of
 * weight: the retrieval query is a hand-tuned pgvector query with a CTE, an
 * operator-class-specific ORDER BY and session GUCs, none of which survive an
 * ORM abstraction intact; the {@code vector} type needs explicit casting that
 * Hibernate would need a custom type for anyway; and JPA's dirty-checking and
 * lazy loading would make it genuinely hard to prove, by reading the code, that
 * every statement is tenant-scoped. When the security property you care most
 * about is "which rows can this query see", seeing the SQL is worth a great
 * deal.
 */
@Repository
public class DocumentRepository {

    private static final String COLUMNS = """
            id, tenant_id, title, category, filename, content_type, content_hash,
            size_bytes, page_count, chunk_count, status, error_message, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new document, or does nothing if this tenant already uploaded
     * these exact bytes.
     *
     * <p>FR-2's idempotency requirement, implemented as
     * {@code ON CONFLICT DO NOTHING} against the {@code (tenant_id, content_hash)}
     * unique constraint rather than as a SELECT-then-INSERT. The read-then-write
     * version has a race: two simultaneous uploads of the same file both see
     * "not present", both insert, and one gets a constraint violation that
     * surfaces as a 500. Letting the database arbitrate makes the race
     * impossible rather than unlikely.
     *
     * @return {@code true} if a row was inserted, {@code false} if it already existed
     */
    public boolean insertIfAbsent(StoredDocument document) {
        int inserted = jdbc.sql("""
                INSERT INTO documents (
                    id, tenant_id, title, category, filename, content_type,
                    content_hash, size_bytes, status
                ) VALUES (
                    :id, :tenantId, :title, :category, :filename, :contentType,
                    :contentHash, :sizeBytes, :status
                )
                ON CONFLICT (tenant_id, content_hash) DO NOTHING
                """)
                .param("id", document.id())
                .param("tenantId", document.tenantId().value())
                .param("title", document.title())
                .param("category", document.category())
                .param("filename", document.filename())
                .param("contentType", document.contentType())
                .param("contentHash", document.contentHash())
                .param("sizeBytes", document.sizeBytes())
                .param("status", document.status().name())
                .update();
        return inserted > 0;
    }

    public Optional<StoredDocument> findById(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM documents WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value())
                .param("id", id)
                .query(DocumentRepository::map)
                .optional();
    }

    public Optional<StoredDocument> findByContentHash(TenantId tenantId, String contentHash) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM documents WHERE tenant_id = :tenantId AND content_hash = :hash")
                .param("tenantId", tenantId.value())
                .param("hash", contentHash)
                .query(DocumentRepository::map)
                .optional();
    }

    public List<StoredDocument> findPage(TenantId tenantId,
                                         @Nullable DocumentStatus status,
                                         @Nullable String category,
                                         int limit,
                                         long offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM documents
                 WHERE tenant_id = :tenantId
                   AND (CAST(:status AS text) IS NULL OR status = :status)
                   AND (CAST(:category AS text) IS NULL OR category = :category)
                 ORDER BY created_at DESC, id
                 LIMIT :limit OFFSET :offset
                """)
                .param("tenantId", tenantId.value())
                .param("status", status == null ? null : status.name())
                .param("category", category)
                .param("limit", limit)
                .param("offset", offset)
                .query(DocumentRepository::map)
                .list();
    }

    public long count(TenantId tenantId, @Nullable DocumentStatus status, @Nullable String category) {
        return jdbc.sql("""
                SELECT count(*) FROM documents
                WHERE tenant_id = :tenantId
                  AND (CAST(:status AS text) IS NULL OR status = :status)
                  AND (CAST(:category AS text) IS NULL OR category = :category)
                """)
                .param("tenantId", tenantId.value())
                .param("status", status == null ? null : status.name())
                .param("category", category)
                .query(Long.class)
                .single();
    }

    public void markReady(TenantId tenantId, UUID id, int chunkCount, @Nullable Integer pageCount) {
        jdbc.sql("""
                UPDATE documents
                   SET status = 'READY',
                       chunk_count = :chunkCount,
                       page_count = :pageCount,
                       error_message = NULL,
                       updated_at = now()
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("chunkCount", chunkCount)
                .param("pageCount", pageCount)
                .param("tenantId", tenantId.value())
                .param("id", id)
                .update();
    }

    public void markFailed(TenantId tenantId, UUID id, String reason) {
        jdbc.sql("""
                UPDATE documents
                   SET status = 'FAILED',
                       chunk_count = 0,
                       error_message = :reason,
                       updated_at = now()
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                // Bound so a pathological parser message cannot bloat the row
                // or the error response.
                .param("reason", truncate(reason, 1000))
                .param("tenantId", tenantId.value())
                .param("id", id)
                .update();
    }

    /**
     * Puts a previously FAILED document back into PROCESSING so a re-upload can
     * retry it. Guarded on the current status so that two concurrent re-uploads
     * cannot both win: the second sees 0 rows updated and skips re-ingestion.
     */
    public boolean reopenFailed(TenantId tenantId, UUID id) {
        return jdbc.sql("""
                UPDATE documents
                   SET status = 'PROCESSING', error_message = NULL, updated_at = now()
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'FAILED'
                """)
                .param("tenantId", tenantId.value())
                .param("id", id)
                .update() > 0;
    }

    /** Chunks disappear via {@code ON DELETE CASCADE}; see V1 migration. */
    public boolean delete(TenantId tenantId, UUID id) {
        return jdbc.sql("DELETE FROM documents WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value())
                .param("id", id)
                .update() > 0;
    }

    private static StoredDocument map(ResultSet rs, int rowNum) throws SQLException {
        return new StoredDocument(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getString("tenant_id")),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getString("content_hash"),
                rs.getLong("size_bytes"),
                (Integer) rs.getObject("page_count"),
                rs.getInt("chunk_count"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "Unknown error.";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
