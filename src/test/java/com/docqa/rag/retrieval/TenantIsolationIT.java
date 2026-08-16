package com.docqa.rag.retrieval;

import com.docqa.rag.tenant.TenantId;
import com.docqa.rag.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Tenant A must never retrieve a chunk belonging to tenant B. You will be
 * tested on this specifically."
 *
 * <p>The setup is deliberately adversarial: both tenants upload documents whose
 * text is <em>identical apart from one number</em>. Their embeddings are
 * therefore equally similar to the question, so any tenant filter that is weak,
 * applied late, or applied in Java rather than in SQL will let the wrong one
 * through - and the wrong number is exactly the harm the brief describes.
 */
class TenantIsolationIT extends AbstractPostgresIT {

    private static final String TENANT_A = "springfield-elementary";
    private static final String TENANT_B = "shelbyville-high";

    private static final String QUESTION = "What is the late fee for term 2?";

    private static final String DOC_A = """
            The late fee for term 2 is 500 rupees per week after the due date.
            """;
    private static final String DOC_B = """
            The late fee for term 2 is 999 rupees per week after the due date.
            """;

    @Autowired private VectorSearchRepository vectorSearch;
    @Autowired private RetrievalService retrieval;

    @Test
    @DisplayName("a question from tenant A never returns tenant B's chunks")
    void retrievalIsScopedToTheAskingTenant() {
        uploadAndWait(TENANT_A, "fee-policy.txt", DOC_A, "FEES");
        uploadAndWait(TENANT_B, "fee-policy.txt", DOC_B, "FEES");

        ResponseEntity<Map> response = ask(TENANT_A, QUESTION, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> sources =
                (List<Map<String, Object>>) response.getBody().get("sources");

        assertThat(sources).as("tenant A must get grounding from its own document").isNotEmpty();
        assertThat(sources)
                .as("no snippet may contain tenant B's figure")
                .noneSatisfy(source ->
                        assertThat(String.valueOf(source.get("snippet"))).contains("999"));
        assertThat(sources)
                .allSatisfy(source ->
                        assertThat(String.valueOf(source.get("snippet"))).contains("500"));

        // And the prompt the model saw contained only tenant A's text - a leak
        // into the prompt is a leak even if it never reaches the response body.
        assertThat(chatModel.lastPromptText()).contains("500").doesNotContain("999");
    }

    @Test
    @DisplayName("the repository itself refuses to cross tenants, not just the API layer")
    void repositoryLevelIsolation() {
        uploadAndWait(TENANT_A, "fee-policy.txt", DOC_A, "FEES");
        uploadAndWait(TENANT_B, "fee-policy.txt", DOC_B, "FEES");

        float[] query = embeddingModel.embed(QUESTION);

        // Threshold of 0 and a large K: ask for everything the index can find.
        // If any filtering were happening above this layer, this call would
        // return both tenants' rows.
        List<RetrievedChunk> forA = vectorSearch.search(
                TenantId.of(TENANT_A), query, null, 50, 0.0);
        List<RetrievedChunk> forB = vectorSearch.search(
                TenantId.of(TENANT_B), query, null, 50, 0.0);

        assertThat(forA).isNotEmpty();
        assertThat(forB).isNotEmpty();
        assertThat(forA).allSatisfy(chunk -> assertThat(chunk.content()).contains("500"));
        assertThat(forB).allSatisfy(chunk -> assertThat(chunk.content()).contains("999"));

        assertThat(forA).extracting(RetrievedChunk::chunkId)
                .doesNotContainAnyElementsOf(forB.stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    @DisplayName("a crafted category filter cannot widen the tenant scope")
    void craftedCategoryFilterCannotEscape() {
        uploadAndWait(TENANT_A, "fee-policy.txt", DOC_A, "FEES");
        uploadAndWait(TENANT_B, "fee-policy.txt", DOC_B, "FEES");

        // The category is the only caller-controlled value that reaches the
        // retrieval query. It is a bind parameter, so this is inert - but the
        // point of the test is that it stays inert if someone later "optimises"
        // the query by concatenating it.
        for (String hostile : List.of(
                "FEES' OR '1'='1",
                "FEES'; --",
                "' OR 1=1 --",
                "FEES UNION SELECT * FROM document_chunks")) {

            ResponseEntity<Map> response = ask(TENANT_A, QUESTION, hostile);

            assertThat(response.getStatusCode().is5xxServerError())
                    .as("hostile category %s must not cause a server error", hostile)
                    .isFalse();

            List<Map<String, Object>> sources =
                    (List<Map<String, Object>>) response.getBody().get("sources");
            if (sources != null) {
                assertThat(sources).noneSatisfy(source ->
                        assertThat(String.valueOf(source.get("snippet"))).contains("999"));
            }
        }
    }

    @Test
    @DisplayName("tenant B cannot read, or delete, tenant A's document by id")
    void documentsAreNotAddressableAcrossTenants() {
        UUID documentA = uploadAndWait(TENANT_A, "fee-policy.txt", DOC_A, "FEES");

        assertThat(get("/api/v1/documents/" + documentA, TENANT_B).getStatusCode())
                .as("reading another tenant's document is a 404, never a 403 - a 403 would "
                        + "confirm the id exists")
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Void> delete = http.delete()
                .uri("/api/v1/documents/" + documentA)
                .header("X-Tenant-Id", TENANT_B)
                .retrieve()
                .toBodilessEntity();
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Still there, and still tenant A's.
        assertThat(statusOf(TENANT_A, documentA)).isEqualTo("READY");
    }

    @Test
    @DisplayName("the schema itself rejects a chunk whose tenant differs from its document")
    void databaseRejectsMismatchedTenantChunk() {
        UUID documentA = uploadAndWait(TENANT_A, "fee-policy.txt", DOC_A, "FEES");

        // This is the belt-and-braces the composite foreign key exists for:
        // even a direct INSERT that bypasses every line of application code
        // cannot attach a chunk to another tenant.
        String vector = com.docqa.rag.persistence.PgVector.toLiteral(
                embeddingModel.embed("anything"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                jdbc.update("""
                        INSERT INTO document_chunks
                            (id, document_id, tenant_id, category, chunk_index,
                             page_number, section, content, token_count, embedding)
                        VALUES (?, ?, ?, 'FEES', 999, 1, NULL, 'smuggled', 3, CAST(? AS vector))
                        """,
                        UUID.randomUUID(), documentA, TENANT_B, vector)))
                .as("the composite FK must make this row unrepresentable")
                .isNotNull()
                .hasMessageContaining("document_chunks_document_fk");
    }

    @Test
    @DisplayName("a tenant with no documents at all is refused, not given someone else's")
    void emptyTenantIsRefused() {
        uploadAndWait(TENANT_A, "fee-policy.txt", DOC_A, "FEES");

        ResponseEntity<Map> response = ask("brand-new-school", QUESTION, null);

        assertThat(response.getBody().get("refused")).isEqualTo(true);
        assertThat((List<?>) response.getBody().get("sources")).isEmpty();
        assertThat(chatModel.callCount())
                .as("an empty tenant must not cost a model call either")
                .isZero();
    }
}
