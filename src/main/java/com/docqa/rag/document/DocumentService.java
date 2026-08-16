package com.docqa.rag.document;

import com.docqa.rag.document.dto.DocumentResponse;
import com.docqa.rag.document.dto.PagedResponse;
import com.docqa.rag.document.dto.UploadAcceptedResponse;
import com.docqa.rag.ingestion.IngestionService;
import com.docqa.rag.ingestion.UploadStagingStore;
import com.docqa.rag.ingestion.extract.TextExtractorRegistry;
import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload, list, fetch and delete.
 *
 * <p>The upload path is the interesting one; the rest are thin.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_CATEGORY_LENGTH = 64;

    private final DocumentRepository documents;
    private final UploadStagingStore staging;
    private final IngestionService ingestion;
    private final TextExtractorRegistry extractors;

    public DocumentService(DocumentRepository documents,
                           UploadStagingStore staging,
                           IngestionService ingestion,
                           TextExtractorRegistry extractors) {
        this.documents = documents;
        this.staging = staging;
        this.ingestion = ingestion;
        this.extractors = extractors;
    }

    /**
     * Accepts an upload and queues it.
     *
     * <p>Order of operations, and why:
     *
     * <ol>
     *   <li><b>Reject unsupported types before touching the disk.</b> A 415 for
     *       a .xlsx should not first spool 20 MB to /tmp.</li>
     *   <li><b>Stream to disk while hashing.</b> One pass, bounded memory.</li>
     *   <li><b>Insert with {@code ON CONFLICT DO NOTHING}.</b> The database, not
     *       a prior SELECT, decides whether this is a duplicate - so two
     *       simultaneous uploads of the same file cannot both win.</li>
     *   <li><b>Queue only if we inserted.</b> A duplicate must not be
     *       re-embedded; that is the whole point of the idempotency
     *       requirement, and re-embedding would also duplicate the cost.</li>
     * </ol>
     *
     * <p>The one subtlety is a duplicate of a document that previously FAILED.
     * Treating that as "already have it, nothing to do" would leave the tenant
     * permanently unable to retry a transient failure - a rate limit during
     * embedding would poison that file forever. So a FAILED duplicate is
     * re-opened and re-queued, guarded by a conditional UPDATE so that two
     * concurrent retries do not both start ingesting.
     */
    public UploadAcceptedResponse upload(TenantId tenantId,
                                         MultipartFile file,
                                         @Nullable String requestedTitle,
                                         @Nullable String requestedCategory) {

        String filename = sanitizeFilename(file.getOriginalFilename());
        String contentType = Optional.ofNullable(file.getContentType())
                .orElse("application/octet-stream");

        // (1) 415 before any I/O. Throws UnsupportedDocumentTypeException.
        extractors.resolve(filename, contentType);

        if (file.isEmpty()) {
            throw new EmptyUploadException("The uploaded file is empty.");
        }

        String title = normaliseTitle(requestedTitle, filename);
        String category = normaliseCategory(requestedCategory);

        // (2) Single pass: to disk, and hashed.
        UploadStagingStore.StagedUpload staged;
        try (var input = file.getInputStream()) {
            staged = staging.stage(input);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded file", e);
        }

        UUID documentId = UUID.randomUUID();
        StoredDocument candidate = new StoredDocument(
                documentId, tenantId, title, category, filename, contentType,
                staged.sha256(), staged.sizeBytes(), null, 0,
                DocumentStatus.PROCESSING, null, Instant.now(), Instant.now());

        // (3) The database arbitrates.
        boolean inserted = documents.insertIfAbsent(candidate);

        if (!inserted) {
            return handleDuplicate(tenantId, staged, filename);
        }

        // (4) Queue. Throws IngestionQueueFullException -> 503.
        ingestion.submit(new IngestionService.IngestionJob(
                tenantId, documentId, staged.path(), filename, contentType, title, category));

        log.info("Accepted upload '{}' ({} bytes) as document {}",
                filename, staged.sizeBytes(), documentId);

        return new UploadAcceptedResponse(documentId, DocumentStatus.PROCESSING, false,
                "Upload accepted. Ingestion is running in the background; poll "
                        + "GET /api/v1/documents/" + documentId + " for status.");
    }

    private UploadAcceptedResponse handleDuplicate(TenantId tenantId,
                                                   UploadStagingStore.StagedUpload staged,
                                                   String filename) {
        StoredDocument existing = documents.findByContentHash(tenantId, staged.sha256())
                .orElseThrow(() -> new IllegalStateException(
                        "Insert reported a conflict but no row matches the content hash"));

        if (existing.status() == DocumentStatus.FAILED
                && documents.reopenFailed(tenantId, existing.id())) {
            // Retry a previously failed ingestion, reusing the staged bytes.
            ingestion.submit(new IngestionService.IngestionJob(
                    tenantId, existing.id(), staged.path(), filename,
                    existing.contentType(), existing.title(), existing.category()));

            log.info("Re-queued previously failed document {} after re-upload", existing.id());
            return new UploadAcceptedResponse(existing.id(), DocumentStatus.PROCESSING, true,
                    "This file was uploaded before and failed to process. Ingestion has been "
                            + "retried.");
        }

        staging.deleteQuietly(staged.path());
        log.info("Duplicate upload of '{}' ignored; document {} already exists with status {}",
                filename, existing.id(), existing.status());

        return new UploadAcceptedResponse(existing.id(), existing.status(), true,
                "This file has already been uploaded; no duplicate chunks were created.");
    }

    public PagedResponse<DocumentResponse> list(TenantId tenantId,
                                                @Nullable DocumentStatus status,
                                                @Nullable String category,
                                                int page,
                                                int size) {
        String normalisedCategory = normaliseCategory(category);
        long total = documents.count(tenantId, status, normalisedCategory);
        var items = documents
                .findPage(tenantId, status, normalisedCategory, size, (long) page * size)
                .stream()
                .map(DocumentResponse::from)
                .toList();
        return PagedResponse.of(items, page, size, total);
    }

    public DocumentResponse get(TenantId tenantId, UUID id) {
        return documents.findById(tenantId, id)
                .map(DocumentResponse::from)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /**
     * Deletes a document and, by cascade, every chunk and embedding belonging
     * to it.
     *
     * <p>FR-3's "answers must stop citing it immediately" needs no extra work
     * and no cache invalidation, because retrieval reads live rows: once the
     * delete commits, the next vector query cannot see those chunks. There is
     * no denormalised copy of chunk text anywhere in the read path - which is
     * precisely why {@code message_sources} stores a snapshot instead of being
     * that copy.
     */
    public void delete(TenantId tenantId, UUID id) {
        if (!documents.delete(tenantId, id)) {
            throw new DocumentNotFoundException(id);
        }
        log.info("Deleted document {} and all of its chunks", id);
    }

    /** Never trust a client-supplied filename as a path. */
    static String sanitizeFilename(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "upload";
        }
        String base = raw.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "").strip();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            return "upload";
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private static String normaliseTitle(@Nullable String requested, String filename) {
        String title = (requested == null || requested.isBlank()) ? filename : requested.strip();
        title = title.replaceAll("[\\p{Cntrl}]", "");
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    /**
     * Categories are upper-cased and character-restricted but <em>not</em>
     * checked against a fixed enum.
     *
     * <p>The brief lists FEES, HR, EXAM and TRANSPORT as examples. Hard-coding
     * those four would mean the next school with an "ADMISSIONS" folder needs a
     * code change and a deploy to file a document, which is the wrong shape for
     * something this open-ended. Normalising the case is what actually matters:
     * without it, "fees" and "FEES" become two categories and a category filter
     * silently returns nothing. Recorded as an assumption in the README.
     */
    static @Nullable String normaliseCategory(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String category = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]", "_");
        return category.length() > MAX_CATEGORY_LENGTH
                ? category.substring(0, MAX_CATEGORY_LENGTH)
                : category;
    }

    /** Visible for tests that assert staged files are cleaned up. */
    Path stagingPathOf(UploadStagingStore.StagedUpload staged) {
        return staged.path();
    }
}
