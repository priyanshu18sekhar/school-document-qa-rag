package com.docqa.rag.document;

import com.docqa.rag.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upload through to deletion, over a real Postgres.
 *
 * <p>Covers FR-1 (accept, validate, 202), FR-2 (async, idempotent, batched,
 * status transitions) and FR-3 (list, fetch, delete stops citations).
 */
class DocumentLifecycleIT extends AbstractPostgresIT {

    private static final String TENANT = "springfield-elementary";

    private static final String FEE_POLICY = """
            The late fee for term 2 is 500 rupees per week after the due date.
            """;
    private static final String TRANSPORT_POLICY = """
            The school bus for route 4 departs from the north gate at 7:15 am.
            """;

    // ---- FR-1: upload ------------------------------------------------------

    @Test
    @DisplayName("upload returns 202 with PROCESSING and a Location header")
    void uploadIsAcceptedImmediately() {
        ResponseEntity<Map> response = upload(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status")).isEqualTo("PROCESSING");
        assertThat(response.getBody().get("duplicate")).isEqualTo(false);
        assertThat(response.getHeaders().getLocation()).isNotNull();
    }

    @Test
    @DisplayName("an unsupported type is rejected with 415 and a readable message")
    void unsupportedTypeIsRejected() {
        ResponseEntity<Map> response = upload(TENANT, "budget.xlsx", "not a document", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(String.valueOf(response.getBody().get("detail")))
                .contains("Unsupported file type")
                .contains("pdf");
        // No stack trace, and the correlation id is there for support.
        assertThat(response.getBody()).containsKey("correlationId");
        assertThat(String.valueOf(response.getBody())).doesNotContain("Exception");
    }

    @Test
    @DisplayName("a request with no tenant header is a 400, never a silent default tenant")
    void tenantHeaderIsMandatory() {
        ResponseEntity<Map> response = http.get()
                .uri("/api/v1/documents")
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("detail"))).contains("X-Tenant-Id");
    }

    // ---- FR-2: ingestion ---------------------------------------------------

    @Test
    @DisplayName("ingestion moves PROCESSING to READY and records the chunk count")
    void ingestionReachesReady() {
        UUID id = uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ResponseEntity<Map> detail = get("/api/v1/documents/" + id, TENANT);
        assertThat(detail.getBody().get("status")).isEqualTo("READY");
        assertThat((Integer) detail.getBody().get("chunkCount")).isPositive();
        assertThat(detail.getBody().get("errorMessage")).isNull();
        assertThat(chunkCountFor(id)).isEqualTo(detail.getBody().get("chunkCount"));
    }

    @Test
    @DisplayName("embeddings are generated in batches, not one call per chunk")
    void embeddingsAreBatched() {
        // Long enough to produce many chunks, so one-call-per-chunk would be
        // obvious in the call count.
        StringBuilder longDocument = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            longDocument.append("Clause ").append(i)
                    .append(" of the fee policy describes a distinct rule for payment.\n\n");
        }
        embeddingModel.reset();

        UUID id = uploadAndWait(TENANT, "long-policy.txt", longDocument.toString(), "FEES");

        int chunks = chunkCountFor(id);
        assertThat(chunks).as("the document should produce several chunks").isGreaterThan(3);
        assertThat(embeddingModel.textCount()).isEqualTo(chunks);
        assertThat(embeddingModel.callCount())
                .as("%d chunks must not mean %d provider calls", chunks, chunks)
                .isLessThan(chunks);
    }

    @Test
    @DisplayName("re-uploading identical bytes does not create duplicate chunks")
    void reuploadIsIdempotent() {
        UUID first = uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        int chunksAfterFirst = chunkCountFor(first);

        ResponseEntity<Map> second = upload(TENANT, "fee-policy-copy.txt", FEE_POLICY, "FEES");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("duplicate")).isEqualTo(true);
        assertThat(UUID.fromString(String.valueOf(second.getBody().get("documentId"))))
                .as("the same content hash must resolve to the same document")
                .isEqualTo(first);

        assertThat(chunkCountFor(first)).isEqualTo(chunksAfterFirst);
        assertThat(countDocuments()).isEqualTo(1);
    }

    @Test
    @DisplayName("two tenants uploading the same file get two independent documents")
    void idempotencyIsPerTenant() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        uploadAndWait("shelbyville-high", "fee-policy.txt", FEE_POLICY, "FEES");

        assertThat(countDocuments())
                .as("the unique constraint is on (tenant_id, content_hash), not content_hash")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a zero-byte upload is rejected at the door with 400")
    void zeroByteUploadIsRejected() {
        ResponseEntity<Map> response = upload(TENANT, "empty.txt", "", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody())).doesNotContain("Exception");
    }

    @Test
    @DisplayName("a file with no extractable text reaches FAILED with a readable reason")
    void unreadableDocumentTransitionsToFailed() {
        // Not zero bytes, so it is legitimately accepted - we do not parse
        // documents on the request thread. The failure surfaces asynchronously
        // as a terminal status with an explanation, which is what FR-2 asks for.
        ResponseEntity<Map> response = upload(TENANT, "blank.txt", "   \n  \n ", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID id = UUID.fromString(String.valueOf(response.getBody().get("documentId")));
        awaitStatus(TENANT, id, DocumentStatus.FAILED);

        ResponseEntity<Map> detail = get("/api/v1/documents/" + id, TENANT);
        String error = String.valueOf(detail.getBody().get("errorMessage"));
        assertThat(error).isNotBlank().doesNotContain("Exception").doesNotContain("at com.docqa");
        assertThat((Integer) detail.getBody().get("chunkCount")).isZero();
    }

    @Test
    @DisplayName("re-uploading a failed document retries it instead of returning the failure forever")
    void failedDocumentCanBeRetried() {
        // A transient failure - a rate limit during embedding - must not poison
        // that file permanently just because its hash is already on record.
        ResponseEntity<Map> first = upload(TENANT, "policy.txt", "   \n ", null);
        UUID id = UUID.fromString(String.valueOf(first.getBody().get("documentId")));
        awaitStatus(TENANT, id, DocumentStatus.FAILED);

        ResponseEntity<Map> retry = upload(TENANT, "policy.txt", "   \n ", null);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retry.getBody().get("duplicate")).isEqualTo(true);
        assertThat(retry.getBody().get("status")).isEqualTo("PROCESSING");
        assertThat(UUID.fromString(String.valueOf(retry.getBody().get("documentId"))))
                .isEqualTo(id);
    }

    // ---- FR-3: management --------------------------------------------------

    @Test
    @DisplayName("the list is paginated, tenant-scoped and newest first")
    void listIsPaginatedAndScoped() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        uploadAndWait(TENANT, "transport.txt", TRANSPORT_POLICY, "TRANSPORT");
        uploadAndWait("shelbyville-high", "other.txt", "Some other school's rules.", "FEES");

        ResponseEntity<Map> page = get("/api/v1/documents?page=0&size=1", TENANT);

        assertThat(page.getBody().get("totalElements")).isEqualTo(2);
        assertThat(page.getBody().get("totalPages")).isEqualTo(2);
        assertThat((List<?>) page.getBody().get("items")).hasSize(1);

        ResponseEntity<Map> filtered = get("/api/v1/documents?category=TRANSPORT", TENANT);
        assertThat(filtered.getBody().get("totalElements")).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting a document removes its chunks and stops it being cited immediately")
    void deleteStopsCitations() {
        UUID id = uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ResponseEntity<Map> before = ask(TENANT, "What is the late fee for term 2?", null);
        assertThat(before.getBody().get("refused")).isEqualTo(false);
        assertThat((List<?>) before.getBody().get("sources")).isNotEmpty();

        ResponseEntity<Void> delete = http.delete()
                .uri("/api/v1/documents/" + id)
                .header("X-Tenant-Id", TENANT)
                .retrieve()
                .toBodilessEntity();
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(chunkCountFor(id))
                .as("chunks must go with the document via ON DELETE CASCADE")
                .isZero();

        chatModel.reset();
        ResponseEntity<Map> after = ask(TENANT, "What is the late fee for term 2?", null);

        assertThat(after.getBody().get("refused"))
                .as("the only grounding is gone, so the answer must be a refusal")
                .isEqualTo(true);
        assertThat((List<?>) after.getBody().get("sources")).isEmpty();
        assertThat(chatModel.callCount()).isZero();
    }

    @Test
    @DisplayName("conversation history survives deleting the document it cited")
    void historySurvivesDocumentDeletion() {
        UUID documentId = uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ResponseEntity<Map> answer = ask(TENANT, "What is the late fee for term 2?", null);
        UUID conversationId = UUID.fromString(
                String.valueOf(answer.getBody().get("conversationId")));

        http.delete().uri("/api/v1/documents/" + documentId)
                .header("X-Tenant-Id", TENANT).retrieve().toBodilessEntity();

        ResponseEntity<Map> history = get("/api/v1/conversations/" + conversationId, TENANT);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) history.getBody().get("messages");
        Map<String, Object> assistantTurn = messages.stream()
                .filter(message -> "ASSISTANT".equals(message.get("role")))
                .findFirst()
                .orElseThrow();

        List<Map<String, Object>> sources =
                (List<Map<String, Object>>) assistantTurn.get("sources");
        assertThat(sources)
                .as("the snapshot keeps the historical answer explainable")
                .hasSize(1);
        assertThat(sources.getFirst())
                .containsEntry("documentTitle", "fee-policy.txt")
                .containsEntry("available", false);   // chunk_id was nulled on delete
    }

    @Test
    @DisplayName("a category filter restricts retrieval to that category")
    void categoryFilterRestrictsRetrieval() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        uploadAndWait(TENANT, "transport.txt", TRANSPORT_POLICY, "TRANSPORT");
        chatModel.reset();

        // Phrased with heavy vocabulary overlap on purpose: the offline test
        // embedder is lexical, not semantic, so a paraphrase would score low
        // for reasons that say nothing about the code under test.
        ResponseEntity<Map> response = ask(
                TENANT, "The school bus for route 4 departs from the north gate at what time?",
                "TRANSPORT");

        List<Map<String, Object>> sources =
                (List<Map<String, Object>>) response.getBody().get("sources");
        assertThat(sources).isNotEmpty();
        assertThat(sources).allSatisfy(source ->
                assertThat(source.get("documentTitle")).isEqualTo("transport.txt"));
    }

    @Test
    @DisplayName("categories are case-normalised so 'fees' and 'FEES' are one category")
    void categoryIsCaseNormalised() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "fees");

        ResponseEntity<Map> detail = get("/api/v1/documents?category=FEES", TENANT);
        assertThat(detail.getBody().get("totalElements")).isEqualTo(1);
    }

    private int countDocuments() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class);
        return count == null ? 0 : count;
    }
}
